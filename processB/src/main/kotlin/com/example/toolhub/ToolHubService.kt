package com.example.toolhub

import android.app.AppOpsManager
import android.app.Service
import android.content.AttributionSource
import android.content.ContextParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.example.toolhub.common.ResultContract
import java.util.concurrent.ConcurrentHashMap

/**
 * 프로세스 B의 진입점. AIDL 구현 + 바인더 스레드 검증 + 체인 조립 +
 * 핸들러 스레드 디스패치 + advisory preflight를 담당한다 (DESIGN.md 6장).
 *
 * execute()는 바인더 스레드에서 다음만 하고 즉시 반환한다:
 *  1. callerSource.enforceCallingUid()  — 머리가 진짜 호출자인가
 *  2. require(callerSource.next == null) — 체인 깊이 고정 (A는 단일 소스만 보낸다)
 *  3. createContext(...)로 체인 조립 (chained = B -> A, 시스템에 등록된 것)
 *  4. RequestRegistry에 등록, linkToDeath로 A 사망 시 정리 예약
 *  5. 핸들러 스레드로 실제 작업을 넘긴다
 *
 * 1, 2는 핸들러 스레드로 넘어가면 Binder.getCallingUid()가 사라지므로 반드시
 * 바인더 스레드에서 끝내야 한다.
 *
 * B의 권한 검사는 advisory다 — 최종 판단은 항상 C의 인가 파이프라인
 * (T∧P∧U∧V)이 한다. B가 여기서 하는 것:
 *  - describe 릴레이/캐시: C의 메타데이터를 해석 없이 A에 전달하고 캐싱
 *  - preflight(fail-fast): 캐시된 권한 목록으로 링크별 grant + A의 AppOps
 *    상태(GET_APP_OPS_STATS 필요 — "이번만 허용" 만료 등, B만 볼 수 있다)를
 *    사전 확인해 거부될 호출이 C 프로세스를 깨우는 비용을 없앤다.
 *    거부는 PHASE_HUB_PREFLIGHT로 구분 보고. 캐시가 낡아 잘못 통과시켜도
 *    C가 재검증하므로 보안 구멍이 아니다.
 *  - executeAsHub: B 자신이 1차 소비자로 C의 툴을 쓰는 경로 (모드 2 —
 *    체인 C->B, next 없음). B manifest는 이 때문에도(그리고 체인 링크로서
 *    OS 강제 때문에도) 플러그인 권한을 미러링해야 한다.
 */
class ToolHubService : Service() {

    companion object {
        private const val TAG = "ToolHubService"
        private const val AUTHORIZATION_PRECHECK_CACHE_TTL_MS = 10000L
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var requestRegistry: RequestRegistry
    private lateinit var pluginRegistry: PluginRegistry

    /** 콜백 바인더당 한 번만 linkToDeath 하기 위한 추적 맵. */
    private val linkedDeaths = ConcurrentHashMap<IBinder, IBinder.DeathRecipient>()

    /**
     * describe 응답 캐시: authority -> KEY_ACTIONS Bundle (actionName -> 메타).
     * 내용은 해석하지 않고 relay/preflight 입력으로만 쓴다. 플러그인 패키지
     * 변경 시 PluginRegistry.onRefresh로 무효화된다.
     */
    private val metadataCache = ConcurrentHashMap<String, Bundle>()

    /**
     * 권한 사전검사 결과 캐시: actionId -> (timestamp, result Bundle).
     * A의 repeated authorizationPreCheck() 호출을 빠르게 처리하고, execute() 호출 시
     * 동일 actionId는 캐시 결과로 재사용해 중복 검사를 방지한다.
     * ~10초 TTL (AUTHORIZATION_PRECHECK_CACHE_TTL_MS).
     */
    private val authorizationPreCheckCache = ConcurrentHashMap<String, Pair<Long, Bundle>>()

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("ToolHubDispatch").apply { start() }
        handler = Handler(handlerThread.looper)
        requestRegistry = RequestRegistry(handler)
        pluginRegistry = PluginRegistry(this, handler).apply {
            onRefresh = { metadataCache.clear() }
            start()
        }
    }

    override fun onDestroy() {
        pluginRegistry.stop()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    /**
     * BootCompletedReceiver가 startService()로 이 서비스를 깨울 때 탄다 —
     * A가 아직 한 번도 bindService()하지 않았어도 부팅 직후 플러그인
     * 메타데이터를 미리 로드해두기 위함(PluginRegistry 참고). START_STICKY로
     * 시스템이 죽여도 재기동되게 한다 — 캐시가 비어있는 채로 방치되지 않도록.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IToolHub.Stub() {

        override fun execute(
            actionId: String,
            args: Bundle,
            reverse: Boolean,
            callerSource: AttributionSource,
            callback: IToolHubCallback?
        ): String {
            // 1. 머리가 진짜 호출자인지 — 위조된 AttributionSource로 다른 uid를
            // 사칭하는 것을 막는다.
            callerSource.enforceCallingUid()

            // 2. A는 체인 없이 자기 자신의 소스만 보내야 한다. 이미 next가 있다면
            // A가 스스로 체인을 조립해 보낸 것 — 코드 버그이지 권한 문제가 아니다.
            require(callerSource.next == null) {
                "callerSource must not carry a pre-built chain (next must be null)"
            }

            // 3. 시스템에 등록된 체인으로 재조립한다. AttributionSource.Builder로
            // 직접 만들면 토큰이 등록되지 않아 C에서 isTrusted()가 false가 된다.
            val chained = createContext(
                ContextParams.Builder().setNextAttributionSource(callerSource).build()
            ).attributionSource

            // requestId를 발급하기 전에 걸러야 한다 — 여기서 callback.onResult를
            // 직접 불러버리면 A의 pending 맵에 아직 등록되지 않은 requestId로 응답이
            // 도착하는 순서 경합이 생긴다. 예외로 던져 AgentToolClient의 기존
            // try/catch(execute 호출부) 경로로 처리되게 한다.
            check(!requestRegistry.isSaturated()) { "too many in-flight requests" }

            val entry = requestRegistry.register(actionId, args, reverse, chained, callback)

            if (callback != null) {
                linkCallbackDeath(callback, callerSource.uid)
            }

            // 4. 이후부터는 핸들러 스레드. Binder.getCallingUid()에 더 이상
            // 의존하지 않으므로 여기서 넘어가도 안전하다.
            handler.post { dispatch(entry) }

            return entry.requestId
        }

        override fun cancel(requestId: String) {
            requestRegistry.cancel(requestId)
        }

        /**
         * 메타데이터 relay — C의 describe 응답에서 해당 액션 부분만 그대로
         * 돌려준다 (해석하지 않는다). 캐시 히트면 C를 깨우지 않는다.
         * payload = { KEY_PERMISSION_ENTRIES, KEY_CONSENT_REQUIRED, ... }.
         */
        override fun describe(actionId: String): Bundle {
            val (authority, actionName) = splitActionId(actionId)
                ?: return ResultContract.error("malformed actionId: $actionId")
            val actions = actionsMetadataFor(authority)
                ?: return ResultContract.error("describe failed for authority '$authority'")
            val action = actions.getBundle(actionName)
                ?: return ResultContract.error("unknown action: $actionName")
            return ResultContract.success(Bundle(action).apply {
                putString(ResultContract.KEY_ACTION_NAME, actionName)
            })
        }

        /**
         * 권한 사전검사 — execute() 호출 전에 A가 선택적으로 호출.
         * 반환값: null 또는 success → 실행 가능
         *        denied Bundle (PHASE_HUB_AUTHORIZATION_PRECHECK) → 권한/AppOps 문제
         * 결과는 ~10초 캐싱되므로 A의 repeated 호출은 매우 빠르다.
         * execute()에서도 동일 actionId는 캐시 재사용으로 중복 검사 방지.
         */
        override fun authorizationPreCheck(actionId: String): Bundle {
            val cached = getValidCachedAuthorizationPreCheck(actionId)
            if (cached != null) {
                return cached
            }

            val (authority, actionName) = splitActionId(actionId)
                ?: return ResultContract.error("malformed actionId: $actionId")
            val providerInfo = packageManager.resolveContentProvider(authority, 0)
            if (providerInfo == null) {
                return ResultContract.error("no plugin found for authority '$authority'")
            }

            // B 자신의 속성소스로 사전검사 실행 (chained = B만, A 아님)
            val denial = checkAuthorizationPreConditions(authority, actionName, attributionSource)
            val result = denial ?: ResultContract.success(Bundle())

            // 결과 캐싱
            authorizationPreCheckCache[actionId] = System.currentTimeMillis() to result
            return result
        }
    }

    /**
     * B 자신이 1차 소비자로 툴을 실행하는 경로 (모드 2 — DESIGN.md 2장).
     * 체인 조립 없이 자기 attributionSource(next 없음)를 그대로 쓴다.
     * C의 인가 파이프라인에서 originator는 B 자신이 되고, 특례 없이 동일한
     * 규칙(권한/동의/정책)이 적용된다.
     */
    fun executeAsHub(
        actionId: String,
        args: Bundle,
        reverse: Boolean = false,
        onResult: ((Bundle) -> Unit)? = null
    ): String? {
        if (requestRegistry.isSaturated()) {
            onResult?.invoke(ResultContract.error("too many in-flight requests"))
            return null
        }
        val callback = onResult?.let { fn ->
            object : IToolHubCallback.Stub() {
                override fun onResult(requestId: String, result: Bundle) = fn(result)
            }
        }
        val entry = requestRegistry.register(actionId, args, reverse, attributionSource, callback)
        handler.post { dispatch(entry) }
        return entry.requestId
    }

    private fun linkCallbackDeath(callback: IToolHubCallback, callerUid: Int) {
        val callbackBinder = callback.asBinder()
        linkedDeaths.computeIfAbsent(callbackBinder) {
            val recipient = IBinder.DeathRecipient {
                Log.i(TAG, "caller uid=$callerUid died, cancelling its in-flight requests")
                requestRegistry.cancelAllFrom(callerUid)
                linkedDeaths.remove(callbackBinder)
            }
            try {
                callbackBinder.linkToDeath(recipient, 0)
            } catch (e: android.os.RemoteException) {
                // 이미 죽어있었다 — 즉시 정리.
                requestRegistry.cancelAllFrom(callerUid)
            }
            recipient
        }
    }

    /**
     * 핸들러 스레드에서 실행. actionId를 쪼개고 provider 존재 확인(discovery)
     * 후, advisory preflight로 거부될 호출을 미리 끊는다. 통과하면 C를 부르고
     * C가 돌려준 결과를 그대로 A에 전달한다 — 최종 판단은 항상 C.
     */
    private fun dispatch(entry: RequestRegistry.Entry) {
        val (authority, actionName) = splitActionId(entry.actionId) ?: run {
            requestRegistry.complete(
                entry.requestId,
                ResultContract.error("malformed actionId: ${entry.actionId}")
            )
            return
        }

        val providerInfo = packageManager.resolveContentProvider(authority, 0)
        if (providerInfo == null) {
            requestRegistry.complete(
                entry.requestId,
                ResultContract.error("no plugin found for authority '$authority'")
            )
            return
        }

        // 캐시된 사전검사 결과 확인 — execute() 호출 전 A가 authorizationPreCheck()를 했으면
        // 여기서 재사용 (중복 검사 방지).
        val cachedAuthCheck = getValidCachedAuthorizationPreCheck(entry.actionId)
        if (cachedAuthCheck != null && cachedAuthCheck.getString(ResultContract.KEY_STATUS) != ResultContract.STATUS_SUCCESS) {
            requestRegistry.complete(entry.requestId, cachedAuthCheck)
            return
        }

        val denial = checkAuthorizationPreConditions(authority, actionName, entry.chainedSource)
        if (denial != null) {
            requestRegistry.complete(entry.requestId, denial)
            return
        }

        val result = callPlugin(authority, actionName, entry)
        requestRegistry.complete(entry.requestId, result)
    }

    /**
     * 권한 사전검사 조건 확인 (DESIGN.md 6.2). 캐시된 메타데이터의 권한 목록으로:
     *  1. 체인 각 링크(B 자신 + A)의 grant를 확인 — 거부될 호출이 C 프로세스를
     *     깨우는 콜드스타트 비용을 없앤다.
     *  2. A의 AppOps 상태를 확인 — "이번만 허용" 만료, MODE_IGNORED는 grant로는
     *     안 보이고 GET_APP_OPS_STATS(signature|privileged)를 가진 B만 볼 수 있다.
     *     C는 이 검사를 할 수 없다 (C의 grant 검사와 겹치지 않는 유일 가치).
     * 어디까지나 advisory — 메타데이터가 없거나 낡았으면 그냥 통과시키고 C의
     * 재검증에 맡긴다. 거부는 PHASE_HUB_AUTHORIZATION_PRECHECK로 구분 보고한다.
     */
    private fun checkAuthorizationPreConditions(authority: String, actionName: String, chained: AttributionSource): Bundle? {
        val meta = actionsMetadataFor(authority)?.getBundle(actionName) ?: return null
        val entries = meta.getParcelableArrayList(
            ResultContract.KEY_PERMISSION_ENTRIES, Bundle::class.java
        ) ?: return null

        // 링크 목록: 머리(B 자신) + next(모드 1이면 A, 모드 2면 없음)
        val links = buildList {
            add(chained)
            chained.next?.let { add(it) }
        }

        for (permEntry in entries) {
            val permission = permEntry.getString(ResultContract.KEY_PERM_NAME) ?: continue
            for (link in links) {
                val pkg = link.packageName ?: continue
                if (packageManager.checkPermission(permission, pkg) != PackageManager.PERMISSION_GRANTED) {
                    return ResultContract.denied(
                        permission, pkg, ResultContract.PHASE_HUB_AUTHORIZATION_PRECHECK,
                        "grant missing (authorization pre-check)", permissionTypeOf(permission)
                    )
                }
                if (appOpsBlocked(permission, link.uid, pkg)) {
                    return ResultContract.denied(
                        permission, pkg, ResultContract.PHASE_HUB_AUTHORIZATION_PRECHECK,
                        "app-op not allowed (one-time grant expired or ignored)",
                        ResultContract.PERMISSION_TYPE_RUNTIME
                    )
                }
            }
        }
        return null
    }

    /**
     * grant는 있지만 AppOps 모드가 실행을 막는 상태인가. MODE_DEFAULT는 grant
     * 상태를 따르므로 통과로 본다. GET_APP_OPS_STATS가 없으면(비 priv 빌드)
     * 조회가 거부되는데, advisory 검사이므로 조용히 통과시킨다.
     */
    private fun appOpsBlocked(permission: String, uid: Int, packageName: String): Boolean {
        val op = AppOpsManager.permissionToOp(permission) ?: return false
        return try {
            val appOps = getSystemService(AppOpsManager::class.java)
            val mode = appOps.unsafeCheckOpNoThrow(op, uid, packageName)
            mode != AppOpsManager.MODE_ALLOWED && mode != AppOpsManager.MODE_DEFAULT
        } catch (e: SecurityException) {
            false
        }
    }

    private fun permissionTypeOf(permission: String): String = try {
        val info = packageManager.getPermissionInfo(permission, 0)
        if (info.protection == PermissionInfo.PROTECTION_DANGEROUS) {
            ResultContract.PERMISSION_TYPE_RUNTIME
        } else {
            ResultContract.PERMISSION_TYPE_INSTALL
        }
    } catch (e: PackageManager.NameNotFoundException) {
        ResultContract.PERMISSION_TYPE_INSTALL
    }

    /**
     * authority의 describe 응답(KEY_ACTIONS Bundle)을 캐시에서 얻거나, 없으면
     * C provider를 호출해 채운다. 실패 시 null — 호출부는 preflight를 건너뛰고
     * C의 재검증에 맡긴다.
     */
    private fun actionsMetadataFor(authority: String): Bundle? {
        metadataCache[authority]?.let { return it }
        return try {
            val uri = Uri.parse("content://$authority")
            val response = contentResolver.call(
                uri, ResultContract.METHOD_DESCRIBE_ACTION, null, Bundle()
            ) ?: return null
            if (response.getString(ResultContract.KEY_STATUS) != ResultContract.STATUS_SUCCESS) {
                return null
            }
            val actions = response.getBundle(ResultContract.KEY_PAYLOAD)
                ?.getBundle(ResultContract.KEY_ACTIONS) ?: return null
            metadataCache[authority] = actions
            actions
        } catch (e: Exception) {
            Log.w(TAG, "describe fetch failed for $authority: ${e.message}")
            null
        }
    }

    private fun getValidCachedAuthorizationPreCheck(actionId: String): Bundle? {
        val (timestamp, result) = authorizationPreCheckCache[actionId] ?: return null
        if (System.currentTimeMillis() - timestamp > AUTHORIZATION_PRECHECK_CACHE_TTL_MS) {
            authorizationPreCheckCache.remove(actionId)
            return null
        }
        return result
    }

    private fun callPlugin(authority: String, actionName: String, entry: RequestRegistry.Entry): Bundle {
        val extras = Bundle().apply {
            putParcelable(ResultContract.KEY_ATTRIBUTION_SOURCE, entry.chainedSource)
            putBundle(ResultContract.KEY_ARGS, entry.args)
        }
        // method 자체로 perform/reverse를 지정하고, 어느 액션인지(actionName)는
        // arg 파라미터에 실어 보낸다 — call(authority, method, arg, extras).
        val method = if (entry.reverse) {
            ResultContract.METHOD_REVERSE_PERFORM_ACTION
        } else {
            ResultContract.METHOD_PERFORM_ACTION
        }

        return try {
            val uri = Uri.parse("content://$authority")
            contentResolver.call(uri, method, actionName, extras)
                ?: ResultContract.error("plugin returned no result")
        } catch (e: SecurityException) {
            // C의 Layer 0(isTrusted) 또는 Layer 2 검사가 여기서 거부했을 수도 있고,
            // provider 자체 권한(signature|knownSigner)에 막혔을 수도 있다. 어느 쪽이든
            // B 입장에서는 실행 자체가 막힌 것이므로 runtime 단계로 보고한다.
            ResultContract.denied(null, authority, ResultContract.PHASE_RUNTIME, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "plugin call failed for $authority/$actionName", e)
            ResultContract.error(e.message)
        }
    }

    private fun splitActionId(actionId: String): Pair<String, String>? {
        val idx = actionId.indexOf('/')
        if (idx <= 0 || idx == actionId.length - 1) return null
        return actionId.substring(0, idx) to actionId.substring(idx + 1)
    }
}
