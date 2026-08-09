package com.example.toolhub.plugin;

import android.os.Bundle;

/**
 * 인가 파이프라인의 V층 — 툴 제공자(벤더)의 자체 인가 규칙 훅 (DESIGN.md 3장).
 *
 * 권한(P층)·사용자 동의(U층)와 독립적으로, 벤더가 자기 툴의 사용 조건을
 * 강제할 수 있다: 에이전트 allowlist/denylist, 인자 검증, rate limit,
 * 시간대 제한, "허브(B) 자체 호출은 동의 없이 허용" 같은 완화 규칙 등.
 *
 * PluginContentProvider 서브클래스가 policy()에서 인스턴스를 반환하면
 * dispatch()가 실행 직전에 호출한다. 반환하지 않으면(V층 없음) 통과.
 */
public interface PluginPolicy {

    /**
     * @param originatorPackage 검증된 체인의 마지막 링크 — 모드 1이면 A,
     *                          모드 2이면 허브(B) 자신. 위조 불가능한 값이다
     *                          (CallerVerifier 통과 후 체인에서 추출).
     * @param actionName        실행하려는 액션
     * @param args              호출 인자
     * @return null이면 허용. 거부하려면 ResultContract 형식의 Bundle
     *         (보통 ResultContract.unauthorized(...))을 반환한다.
     */
    Bundle authorize(String originatorPackage, String actionName, Bundle args);
}
