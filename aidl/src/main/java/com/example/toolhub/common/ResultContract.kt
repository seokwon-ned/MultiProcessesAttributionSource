package com.example.toolhub.common

import android.os.Bundle

/**
 * A / B / C 세 프로세스가 공유하는 결과 번들 계약.
 *
 * 핵심 원칙: 실패 경로가 어디든(체인 무효, 사전 권한 검사, 런타임
 * SecurityException) **동일한 형태**로 반환한다. A는 어느 층에서 막혔는지
 * 신경 쓰지 않고 status / deniedAt만 보고 대응을 결정할 수 있어야 한다.
 */
object ResultContract {

    const val KEY_STATUS = "status"
    const val KEY_PERMISSION = "permission"
    const val KEY_DENIED_AT = "denied_at"      // 체인 중 막힌 링크의 패키지명
    const val KEY_PHASE = "phase"              // 어느 단계에서 막혔는지
    const val KEY_MESSAGE = "message"
    const val KEY_PAYLOAD = "payload"

    // 요청 전달용 키
    const val KEY_ATTRIBUTION_SOURCE = "attribution_source"
    const val KEY_ARGS = "args"

    // ContentProvider.call()의 method 값. B가 이 문자열 자체로 perform/reverse를
    // 지정하고, actionName은 대신 arg 파라미터에 실어 보낸다 — call(authority,
    // method, arg, extras)에서 method/arg 역할을 이렇게 나눠 쓴다.
    const val METHOD_PERFORM_ACTION = "onPerformAction"
    const val METHOD_REVERSE_PERFORM_ACTION = "onReversePerformAction"

    /**
     * 메타데이터 조회. arg = actionName (null이면 전체). 실행이 아니므로 체인
     * 조립·권한 검사 없이 허브 핀 고정(T층 ①)만 적용된다. 응답 payload:
     * KEY_ACTIONS -> { actionName -> { KEY_PERMISSION_ENTRIES, KEY_CONSENT_* } }
     */
    const val METHOD_DESCRIBE_ACTION = "describeAction"

    // status 값
    const val STATUS_SUCCESS = "success"
    const val STATUS_PERMISSION_DENIED = "permission_denied"
    const val STATUS_UNTRUSTED_CHAIN = "untrusted_chain"
    /**
     * B가 보낸 call()의 모양이 C가 기대하는 계약과 안 맞는 경우(method가
     * 알려진 리터럴이 아님, actionName이 없음, attributionSource가 없음 등).
     * STATUS_ERROR와 구분하는 이유: 이건 "이번 요청 하나의 문제"가 아니라
     * "B와 C의 프로토콜 버전이 안 맞다"는 배포 문제일 가능성이 높다 —
     * B가 priv-app(OTA로만 갱신)이고 C는 벤더별로 독립 배포되므로, C가
     * 새 프로토콜로 먼저 업데이트되고 B가 아직 구버전인 롤아웃 구간에서
     * 실제로 발생할 수 있다. 로그/모니터링에서 "그냥 에러"와 구분해서
     * 걸러낼 수 있게 별도 status를 둔다.
     */
    const val STATUS_PROTOCOL_INCOMPATIBLE = "protocol_incompatible"
    const val STATUS_ERROR = "error"

    /**
     * U층(사용자 인가) 거부 — 이 (에이전트, 액션) 조합에 대한 사용자 승인
     * 레코드가 C의 ConsentStore에 없다. A는 C의 ConsentRequestActivity를 띄워
     * 승인을 받은 뒤 재시도하면 된다 (recoveryAction == RECOVERY_REQUEST_CONSENT).
     */
    const val STATUS_CONSENT_REQUIRED = "consent_required"

    /**
     * T층(전송 경로) 또는 V층(벤더 정책) 거부. 권한 요청이나 동의로 복구되지
     * 않는다 — 호출 경로 자체가 허용되지 않았거나 정책상 차단된 것.
     */
    const val STATUS_UNAUTHORIZED = "unauthorized"

    // phase 값 — A의 대응이 갈리는 지점이므로 반드시 구분해서 채운다.
    const val PHASE_CHAIN = "chain"            // 코드 버그. 권한 요청해도 소용없음
    const val PHASE_HUB_PREFLIGHT = "hub_preflight"      // B의 advisory 사전 검사 (grant/AppOps)
    const val PHASE_PLUGIN_PREFLIGHT = "plugin_preflight"
    const val PHASE_RUNTIME = "runtime"        // AppOps 계열. 실행 중 발생

    // 권한 분류 — 복구 경로가 갈린다: runtime이면 해당 링크가 grant를 받으면
    // 되고(A는 자기 UI, C는 PluginPermissionActivity), install이면 배포 문제다.
    const val PERMISSION_TYPE_RUNTIME = "runtime"
    const val PERMISSION_TYPE_INSTALL = "install"
    const val KEY_PERMISSION_TYPE = "permission_type"

    // describe 응답 키
    const val KEY_ACTIONS = "actions"                          // Bundle: actionName -> 액션 메타 Bundle
    const val KEY_PERMISSION_ENTRIES = "permission_entries"    // ArrayList<Bundle>
    const val KEY_PERM_NAME = "perm_name"
    const val KEY_PERM_IS_RUNTIME = "perm_is_runtime"
    const val KEY_PERM_GRANTED_ON_PLUGIN = "perm_granted_on_plugin"
    const val KEY_PERM_GRANTED_ON_HUB = "perm_granted_on_hub"
    const val KEY_CONSENT_REQUIRED = "consent_required"
    const val KEY_CONSENT_CATEGORIES = "consent_categories"
    const val KEY_ACTION_NAME = "action_name"

    // recoveryAction() 반환값 — 실패 Bundle을 A의 UX 분기로 매핑한다.
    const val RECOVERY_NONE = "none"
    const val RECOVERY_REQUEST_SELF_PERMISSION = "request_self_permission"
    const val RECOVERY_REQUEST_PLUGIN_PERMISSION = "request_plugin_permission"
    const val RECOVERY_REQUEST_CONSENT = "request_consent"

    // 이 object의 함수들은 C 쪽 Java 코드(ChainPermissionChecker,
    // PluginContentProvider)에서도 정적 호출 문법(ResultContract.foo(...))으로
    // 쓰인다. @JvmStatic이 없으면 Java에서는 ResultContract.INSTANCE.foo(...)
    // 로 써야 하므로 반드시 붙여야 한다.

    @JvmStatic
    @JvmOverloads
    fun success(payload: Bundle? = null): Bundle = Bundle().apply {
        putString(KEY_STATUS, STATUS_SUCCESS)
        payload?.let { putBundle(KEY_PAYLOAD, it) }
    }

    @JvmStatic
    @JvmOverloads
    fun denied(
        permission: String?,
        deniedAt: String?,
        phase: String,
        message: String? = null,
        permissionType: String? = null
    ): Bundle = Bundle().apply {
        putString(KEY_STATUS, STATUS_PERMISSION_DENIED)
        putString(KEY_PERMISSION, permission)
        putString(KEY_DENIED_AT, deniedAt)
        putString(KEY_PHASE, phase)
        message?.let { putString(KEY_MESSAGE, it) }
        permissionType?.let { putString(KEY_PERMISSION_TYPE, it) }
    }

    @JvmStatic
    fun consentRequired(actionName: String, categories: Array<String>): Bundle = Bundle().apply {
        putString(KEY_STATUS, STATUS_CONSENT_REQUIRED)
        putString(KEY_ACTION_NAME, actionName)
        putStringArray(KEY_CONSENT_CATEGORIES, categories)
    }

    @JvmStatic
    fun unauthorized(message: String): Bundle = Bundle().apply {
        putString(KEY_STATUS, STATUS_UNAUTHORIZED)
        putString(KEY_MESSAGE, message)
    }

    @JvmStatic
    fun untrustedChain(message: String): Bundle = Bundle().apply {
        putString(KEY_STATUS, STATUS_UNTRUSTED_CHAIN)
        putString(KEY_PHASE, PHASE_CHAIN)
        putString(KEY_MESSAGE, message)
    }

    @JvmStatic
    fun error(message: String?): Bundle = Bundle().apply {
        putString(KEY_STATUS, STATUS_ERROR)
        putString(KEY_MESSAGE, message ?: "unknown error")
    }

    @JvmStatic
    fun protocolIncompatible(message: String): Bundle = Bundle().apply {
        putString(KEY_STATUS, STATUS_PROTOCOL_INCOMPATIBLE)
        putString(KEY_MESSAGE, message)
    }

    /**
     * A가 이 결과로 사용자에게 권한 요청을 띄워야 하는가?
     *
     * 체인이 깨진 경우(PHASE_CHAIN)는 코드 버그이므로 권한 요청 대상이 아니다.
     * 막힌 지점이 A 자신일 때만 요청 UI가 의미를 갖는다.
     */
    @JvmStatic
    fun isActionableByCaller(result: Bundle, callerPackage: String): Boolean =
        result.getString(KEY_STATUS) == STATUS_PERMISSION_DENIED &&
            result.getString(KEY_PHASE) != PHASE_CHAIN &&
            result.getString(KEY_DENIED_AT) == callerPackage

    /**
     * 실패 결과를 A의 복구 UX 분기로 매핑한다.
     *
     * - RECOVERY_REQUEST_CONSENT: C의 ConsentRequestActivity를 띄워 승인 후 재시도.
     * - RECOVERY_REQUEST_SELF_PERMISSION: A 자신의 런타임 권한 요청 UI.
     * - RECOVERY_REQUEST_PLUGIN_PERMISSION: deniedAt 패키지(플러그인)의
     *   PluginPermissionActivity를 띄워 C의 런타임 권한 획득 후 재시도.
     * - RECOVERY_NONE: 배포/코드 문제 — 사용자 조치로 복구 불가, 안내만.
     */
    @JvmStatic
    fun recoveryAction(result: Bundle, callerPackage: String): String {
        return when (result.getString(KEY_STATUS)) {
            STATUS_CONSENT_REQUIRED -> RECOVERY_REQUEST_CONSENT
            STATUS_PERMISSION_DENIED -> {
                if (result.getString(KEY_PHASE) == PHASE_CHAIN) return RECOVERY_NONE
                val deniedAt = result.getString(KEY_DENIED_AT)
                val isRuntime = result.getString(KEY_PERMISSION_TYPE) == PERMISSION_TYPE_RUNTIME
                when {
                    deniedAt == callerPackage && isRuntime -> RECOVERY_REQUEST_SELF_PERMISSION
                    deniedAt != null && isRuntime -> RECOVERY_REQUEST_PLUGIN_PERMISSION
                    else -> RECOVERY_NONE
                }
            }
            else -> RECOVERY_NONE
        }
    }
}
