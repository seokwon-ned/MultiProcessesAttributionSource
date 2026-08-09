package com.example.toolhub.plugin;

/**
 * PermissionScanner가 핸들러 하나를 스캔한 결과. C 내부 검사(Layer 2, U층)와
 * describe 응답 생성 양쪽에 쓰인다.
 */
public final class ActionMetadata {

    /** 이 액션이 요구하는 권한의 정적 상한 (perform/reverse 합집합). */
    public final String[] permissions;

    /** RequiresUserConsent 선언 여부 (두 메서드 중 하나라도 있으면 true). */
    public final boolean consentRequired;

    /** 선언된 동의 분류 합집합. consentRequired=false면 빈 배열. */
    public final String[] consentCategories;

    public ActionMetadata(String[] permissions, boolean consentRequired, String[] consentCategories) {
        this.permissions = permissions;
        this.consentRequired = consentRequired;
        this.consentCategories = consentCategories;
    }
}
