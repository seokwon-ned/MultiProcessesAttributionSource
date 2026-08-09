package com.example.toolhub.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 플러그인 델리게이트 메서드(onPerformAction / onReversePerformAction)에
 * 붙여 실행에 필요한 런타임 권한을 선언한다.
 *
 * PermissionScanner가 첫 call() 시점에 등록된 델리게이트 클래스만 리플렉션
 * 스캔하여 이 애노테이션을 읽고 액션명 -> 권한[] 맵을 만든다. 이 맵은 C
 * 내부에서만 쓰인다 — ChainPermissionChecker의 Layer 2 사전 검사(grant
 * 여부만 확인하는 checkPermission)에 넘겨줄 권한 목록을 얻는 용도다. B는
 * 권한 검사를 하지 않으므로(HANDOFF 3항), 여기 선언되지 않은 권한은 체인
 * 어디서도(C 자신을 포함해서도) 강제되지 않는다.
 *
 * value()는 이 메서드가 인자(args)에 따라 실제로 요구할 수 있는 권한의
 * "정적 상한"이다 — 특정 호출에서는 그중 일부만 실제로 쓰이더라도(예:
 * mode=read일 때는 READ_CONTACTS만, mode=write일 때는 WRITE_CONTACTS까지)
 * value()에는 가능한 모든 경우의 합집합을 선언해야 한다. PermissionScanner는
 * 액션명 단위로 onPerformAction/onReversePerformAction 둘의 선언을 합쳐서
 * 캐싱하고, dispatch()는 실제로 perform인지 reverse인지와 무관하게 이 합쳐진
 * 목록 전체를 기준으로 Layer 2 검사를 한다 — 그래서 실제 호출에서 상한의
 * 일부만 쓰여도 안전은 깨지지 않는다.
 *
 * 반대로 여기 선언되지 않은 권한을 onPerformAction/onReversePerformAction
 * 내부에서 조건부로라도 사용하면 안 된다 — Layer 2는 그 권한의 존재를 전혀
 * 모르므로 검사하지 않는다. Layer 3(delegate 실행 중 SecurityException)이
 * 마지막 안전망이긴 하지만, attributionContext로 실제 시스템 API를 부르는
 * 경우에만 OS가 자동으로 막아준다 — 그 외의 자체 로직에는 그 안전망도 없다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HandlerPermission {
    String[] value();
}
