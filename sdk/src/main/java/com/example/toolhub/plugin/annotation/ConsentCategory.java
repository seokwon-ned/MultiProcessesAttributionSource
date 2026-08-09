package com.example.toolhub.plugin.annotation;

/**
 * RequiresUserConsent.categories()에 쓰는 표준 분류. A의 동의 UI가 문구를
 * 분기하는 데 쓴다. 벤더 고유 분류가 필요하면 자체 문자열을 써도 되지만,
 * A가 모르는 분류는 일반 문구로 표시될 뿐이다.
 */
public final class ConsentCategory {

    private ConsentCategory() {}

    /** 기기 밖으로 데이터를 발신 — 문자, 이메일, 네트워크 업로드 등 */
    public static final String SEND_EXTERNAL = "send_external";

    /** 사용자 데이터 변경 */
    public static final String MODIFY_DATA = "modify_data";

    /** 사용자 데이터 삭제 */
    public static final String DELETE_DATA = "delete_data";

    /** 금전 지출 가능성 — 유료 발신, 결제 등 */
    public static final String COSTS_MONEY = "costs_money";
}
