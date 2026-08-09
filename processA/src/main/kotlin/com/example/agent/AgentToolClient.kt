package com.example.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.example.toolhub.IToolHub
import com.example.toolhub.IToolHubCallback
import com.example.toolhub.common.ResultContract
import java.util.concurrent.ConcurrentHashMap

/**
 * Process A (agent) side client.
 *
 * Important: the AttributionSource handed to the tool hub must always be
 * context.attributionSource. A source built directly via
 * AttributionSource.Builder(myUid()) carries a token that isn't registered
 * with the system, so it fails the authenticity check of the 3-hop chain
 * (C -> B -> A). (2-hop chains are allowed unverified, but this is 3-hop.)
 */
class AgentToolClient(private val context: Context) {

    companion object {
        private const val TAG = "AgentToolClient"
        private const val HUB_PACKAGE = "com.example.toolhub"
        private const val HUB_SERVICE = "com.example.toolhub.ToolHubService"
    }

    fun interface ResultListener {
        fun onResult(result: Bundle)
    }

    private var hub: IToolHub? = null
    private val pending = ConcurrentHashMap<String, ResultListener>()

    private val callback = object : IToolHubCallback.Stub() {
        override fun onResult(requestId: String, result: Bundle) {
            // oneway callback, so this arrives on a binder pool thread.
            val listener = pending.remove(requestId)
            if (listener == null) {
                Log.w(TAG, "no listener for $requestId (cancelled or timed out)")
                return
            }
            listener.onResult(result)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            hub = IToolHub.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            hub = null
            // If the connection drops, in-flight requests will never get a response.
            pending.keys.toList().forEach { id ->
                pending.remove(id)?.onResult(
                    ResultContract.error("tool hub disconnected")
                )
            }
        }
    }

    fun bind() {
        val intent = Intent().apply {
            component = ComponentName(HUB_PACKAGE, HUB_SERVICE)
        }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        runCatching { context.unbindService(connection) }
        hub = null
        pending.clear()
    }

    /**
     * Requests execution of an action. Returns a requestId immediately;
     * the result arrives later via the listener.
     *
     * @param reverse if true, asks C to run onReversePerformAction instead
     *                of onPerformAction for this actionId
     * @return requestId, or null if not bound to the hub
     */
    fun execute(actionId: String, args: Bundle, reverse: Boolean = false, listener: ResultListener): String? {
        val service = hub ?: run {
            listener.onResult(ResultContract.error("tool hub not bound"))
            return null
        }

        return try {
            // System-issued source for this app itself. No chain (next == null).
            val mySource = context.attributionSource

            val requestId = service.execute(actionId, args, reverse, mySource, callback)
            pending[requestId] = listener
            requestId
        } catch (e: Exception) {
            Log.e(TAG, "execute failed", e)
            listener.onResult(ResultContract.error(e.message))
            null
        }
    }

    fun cancel(requestId: String) {
        pending.remove(requestId)
        runCatching { hub?.cancel(requestId) }
    }

    /**
     * 권한 사전검사 (DESIGN.md 7장, AUTHORIZATION_PRECHECK.md 참고). B가 AppOps 상태와
     * 권한 grant를 빠르게 판단 (C 안 깨움, ~10초 캐시).
     *
     * 반환값: null 또는 success → OK / denied Bundle → 거부 (PHASE_HUB_AUTHORIZATION_PRECHECK)
     *
     * A가 execute() 전에 호출해서 "지금 실행 가능한가?" 미리 판단 가능.
     * 예: authorizationPreCheck()가 거부하면 권한 요청 후 execute() 재시도.
     */
    fun authorizationPreCheck(actionId: String): Bundle? = try {
        hub?.authorizationPreCheck(actionId)
    } catch (e: Exception) {
        Log.e(TAG, "authorizationPreCheck failed", e)
        null
    }

    /**
     * 사전검사 결과가 통과했는가 (또는 정보 부족).
     * true면 execute() 호출 가능성 높음.
     */
    fun authorizationPreCheckOk(result: Bundle?): Boolean {
        if (result == null) return true  // 정보 부족, 통과로 봄
        return result.getString(ResultContract.KEY_STATUS) == ResultContract.STATUS_SUCCESS
    }

    /**
     * 실행 전 사전검사용 메타데이터 조회 (DESIGN.md 4장). 반환 payload:
     * KEY_PERMISSION_ENTRIES(권한별 분류/C·B grant 스냅샷), KEY_CONSENT_*.
     * A 자신의 grant는 missingSelfPermissions()로 로컬 확인하면 세 링크의
     * 사전 그림이 완성된다.
     */
    fun describeAction(actionId: String): Bundle? = try {
        hub?.describe(actionId)
    } catch (e: Exception) {
        Log.e(TAG, "describe failed", e)
        null
    }

    /**
     * describe 결과에서 A 자신이 아직 grant받지 못한 권한 목록을 뽑는다.
     * 체인 검사는 모든 링크가 모든 권한을 가져야 하므로, 목록 전체가 곧
     * A가 보유해야 할 권한 목록이다 — 이걸로 requestPermissions()를 띄운다.
     */
    fun missingSelfPermissions(describeResult: Bundle): List<String> {
        val payload = describeResult.getBundle(ResultContract.KEY_PAYLOAD) ?: return emptyList()
        val entries = payload.getParcelableArrayList(
            ResultContract.KEY_PERMISSION_ENTRIES, Bundle::class.java
        ) ?: return emptyList()
        return entries.mapNotNull { it.getString(ResultContract.KEY_PERM_NAME) }
            .filter {
                context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
    }

    /**
     * describe 결과에서 C(플러그인) 쪽에 grant가 빠진 런타임 권한 목록.
     * 비어있지 않으면 pluginPermissionIntent()로 C의 권한 요청 Activity를
     * startActivityForResult로 띄워 복구한다.
     */
    fun missingPluginPermissions(describeResult: Bundle): List<String> {
        val payload = describeResult.getBundle(ResultContract.KEY_PAYLOAD) ?: return emptyList()
        val entries = payload.getParcelableArrayList(
            ResultContract.KEY_PERMISSION_ENTRIES, Bundle::class.java
        ) ?: return emptyList()
        return entries.filter {
            it.getBoolean(ResultContract.KEY_PERM_IS_RUNTIME) &&
                !it.getBoolean(ResultContract.KEY_PERM_GRANTED_ON_PLUGIN)
        }.mapNotNull { it.getString(ResultContract.KEY_PERM_NAME) }
    }

    /** describe 결과 기준, 실행 전에 사용자 동의 UI가 필요한가. */
    fun consentRequired(describeResult: Bundle): Boolean =
        describeResult.getBundle(ResultContract.KEY_PAYLOAD)
            ?.getBoolean(ResultContract.KEY_CONSENT_REQUIRED) == true

    /**
     * C의 동의 Activity(ConsentRequestActivity)를 띄우는 인텐트.
     * 반드시 startActivityForResult로 실행해야 한다 — C가 승인 주체를
     * getCallingPackage()로 확인하므로 A는 자기 승인만 얻을 수 있다.
     * RESULT_OK 후 execute를 재시도하면 U층을 통과한다.
     */
    fun consentIntent(pluginPackage: String, actionName: String, categories: Array<String>?): Intent =
        Intent().apply {
            setClassName(pluginPackage, "com.example.toolhub.plugin.permission.ConsentRequestActivity")
            putExtra("com.example.toolhub.plugin.EXTRA_ACTION_NAME", actionName)
            putExtra("com.example.toolhub.plugin.EXTRA_CATEGORIES", categories)
        }

    /**
     * C의 런타임 권한 요청 Activity(PluginPermissionActivity)를 띄우는 인텐트.
     * deniedAt == 플러그인 && type == runtime 실패나
     * missingPluginPermissions() 결과가 있을 때 startActivityForResult로 실행.
     */
    fun pluginPermissionIntent(pluginPackage: String, permissions: Array<String>): Intent =
        Intent().apply {
            setClassName(pluginPackage, "com.example.toolhub.plugin.permission.PluginPermissionActivity")
            putExtra("com.example.toolhub.plugin.EXTRA_PERMISSIONS", permissions)
        }

    /**
     * Example of how to interpret a result.
     *
     * Even a permission denial demands completely different handling depending
     * on the cause:
     *  - PHASE_CHAIN            -> a code bug. Requesting permission is pointless.
     *  - deniedAt == my package -> show the runtime permission request UI.
     *  - deniedAt == hub/plugin -> a deployment issue. Nothing the user can do.
     */
    fun describe(result: Bundle): String = when (result.getString(ResultContract.KEY_STATUS)) {
        ResultContract.STATUS_SUCCESS -> "성공"

        ResultContract.STATUS_UNTRUSTED_CHAIN ->
            "어트리뷰션 체인 무효 (코드 버그): ${result.getString(ResultContract.KEY_MESSAGE)}"

        ResultContract.STATUS_PROTOCOL_INCOMPATIBLE ->
            "허브/플러그인 버전 호환 문제 (사용자가 할 수 있는 게 없음, 업데이트 필요): " +
                result.getString(ResultContract.KEY_MESSAGE)

        ResultContract.STATUS_CONSENT_REQUIRED ->
            "사용자 동의 필요 — consentIntent()로 플러그인의 동의 UI를 띄운 뒤 재시도: " +
                "${result.getString(ResultContract.KEY_ACTION_NAME)}"

        ResultContract.STATUS_UNAUTHORIZED ->
            "인가 거부 (전송 경로 또는 벤더 정책): ${result.getString(ResultContract.KEY_MESSAGE)}"

        ResultContract.STATUS_PERMISSION_DENIED -> {
            val perm = result.getString(ResultContract.KEY_PERMISSION)
            val at = result.getString(ResultContract.KEY_DENIED_AT)
            val phase = result.getString(ResultContract.KEY_PHASE)

            // 사전검사에서 거부된 경우 (PHASE_HUB_AUTHORIZATION_PRECHECK)
            if (phase == ResultContract.PHASE_HUB_AUTHORIZATION_PRECHECK) {
                val permType = result.getString(ResultContract.KEY_PERMISSION_TYPE)
                if (permType == ResultContract.PERMISSION_TYPE_RUNTIME) {
                    "허브 사전검사: $at 의 $perm 권한 또는 AppOps 문제. " +
                        "권한 설정 확인 또는 재시도"
                } else {
                    "허브 사전검사: $at 이(가) $perm 권한을 보유하지 않음 (배포 문제)"
                }
            } else {
                // execute에서 거부된 경우
                when (ResultContract.recoveryAction(result, context.packageName)) {
                    ResultContract.RECOVERY_REQUEST_SELF_PERMISSION ->
                        "내 런타임 권한 요청 필요: $perm"
                    ResultContract.RECOVERY_REQUEST_PLUGIN_PERMISSION ->
                        "플러그인($at)의 런타임 권한 획득 필요 — pluginPermissionIntent() 실행: $perm"
                    else ->
                        "$at 가 $perm 권한을 보유하지 않음 (배포 구성 문제)"
                }
            }
        }

        else -> "오류: ${result.getString(ResultContract.KEY_MESSAGE)}"
    }
}
