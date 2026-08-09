package com.example.toolhub

import android.app.Service
import android.content.AttributionSource
import android.content.ContextParams
import android.content.Intent
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
 * 핸들러 스레드 디스패치를 담당한다.
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
 * B는 권한 검사를 하지 않는다. 체인만 진짜로 조립해서 그대로 C에 전달할 뿐이고,
 * "이 액션에 실제로 필요한 권한이 뭔지, 체인의 각 링크가 그걸 갖고 있는지"는
 * C가 자기 자신의 attributionContext로 스스로 판단한다
 * (PluginContentProvider -> ChainPermissionChecker / 델리게이트 실행 중
 * SecurityException). B가 C에 __required_permissions를 물어보던 캐싱 로직도
 * 제거했다 — B의 역할을 "체인을 조립해서 넘겨주는 라우터"로 최소화하기 위함.
 */
class ToolHubService : Service() {

    companion object {
        private const val TAG = "ToolHubService"
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var requestRegistry: RequestRegistry
    private lateinit var pluginRegistry: PluginRegistry

    /** 콜백 바인더당 한 번만 linkToDeath 하기 위한 추적 맵. */
    private val linkedDeaths = ConcurrentHashMap<IBinder, IBinder.DeathRecipient>()

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("ToolHubDispatch").apply { start() }
        handler = Handler(handlerThread.looper)
        requestRegistry = RequestRegistry(handler)
        pluginRegistry = PluginRegistry(this, handler).apply { start() }
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
     * 핸들러 스레드에서 실행. 여기선 actionId를 쪼개고 provider가 실제로
     * 존재하는지만 확인한다(discovery) — 권한 검사는 하지 않는다. C가 스스로
     * 판단해서 돌려준 결과를 그대로 A에 전달할 뿐이다.
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

        val result = callPlugin(authority, actionName, entry)
        requestRegistry.complete(entry.requestId, result)
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
