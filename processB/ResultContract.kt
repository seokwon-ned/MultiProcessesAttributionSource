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

    // phase 값 — A의 대응이 갈리는 지점이므로 반드시 구분해서 채운다.
    // B는 권한 검사를 하지 않으므로 "hub_preflight" 단계는 없다 — 모든 권한
    // 판단은 C 쪽(plugin_preflight, runtime)에서 일어난다.
    const val PHASE_CHAIN = "chain"            // 코드 버그. 권한 요청해도 소용없음
    const val PHASE_PLUGIN_PREFLIGHT = "plugin_preflight"
    const val PHASE_RUNTIME = "runtime"        // AppOps 계열. 실행 중 발생

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
        message: String? = null
    ): Bundle = Bundle().apply {
        putString(KEY_STATUS, STATUS_PERMISSION_DENIED)
        putString(KEY_PERMISSION, permission)
        putString(KEY_DENIED_AT, deniedAt)
        putString(KEY_PHASE, phase)
        message?.let { putString(KEY_MESSAGE, it) }
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
}
