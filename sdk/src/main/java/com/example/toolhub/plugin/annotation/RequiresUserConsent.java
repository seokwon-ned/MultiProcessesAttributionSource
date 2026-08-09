package com.example.toolhub.plugin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 액션의 실행에 사용자 명시 동의가 필요함을 툴 제공자가 선언한다.
 * 데이터 변경/삭제, 외부 발신(문자/이메일) 등이 대상이다 (ConsentCategory 참고).
 *
 * 선언의 효과 (DESIGN.md 3장 U층):
 *  - describe 응답에 consentRequired=true로 노출된다 — A가 실행 전에 동의
 *    UI가 필요함을 알 수 있다.
 *  - PluginContentProvider.dispatch()가 실행 전에 C 소유의 ConsentStore에
 *    (originator 패키지+서명해시, actionName) 승인 레코드가 있는지 확인하고,
 *    없으면 STATUS_CONSENT_REQUIRED로 거부한다. A의 주장(플래그)이 아니라
 *    C의 저장소로 판단하므로, 동의 절차를 구현하지 않은 A가 실수로 위험
 *    액션을 실행하는 것이 구조적으로 차단된다.
 *  - 승인은 C 소유의 ConsentRequestActivity에서만 기록된다. A는 그 Activity를
 *    startActivityForResult로 띄우는 역할만 한다.
 *
 * onPerformAction / onReversePerformAction 어느 한쪽에라도 붙으면 그 액션은
 * 동의 필요로 취급된다 (권한 합집합 정책과 동일한 보수적 상한).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresUserConsent {
    /** ConsentCategory 상수 — A의 동의 UI 문구/아이콘 분기용. */
    String[] categories();
}
