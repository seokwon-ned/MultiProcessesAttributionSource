package com.example.toolhub.plugin.permission;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;

/**
 * U층의 승인 획득 UI — C(플러그인) 프로세스에서 뜨는 동의 다이얼로그.
 *
 * A가 startActivityForResult로 띄운다. 승인의 주체(에이전트)는 intent
 * extra가 아니라 getCallingPackage()로 얻는다 — 시스템이 보증하는 값이므로
 * A는 자기 자신에 대한 승인만 얻을 수 있다 (타 앱 대리 승인 불가).
 *
 * 이미 승인된 조합이면 다이얼로그 없이 즉시 RESULT_OK로 끝난다 — A는
 * 승인 여부를 미리 조회할 필요 없이 항상 이 Activity를 거쳐도 된다.
 *
 * SDK의 AndroidManifest에 exported로 선언되어 있어 플러그인 앱에 자동
 * 병합된다. 기록은 이 Activity에서만, 판정은 PluginContentProvider가 한다.
 */
public class ConsentRequestActivity extends Activity {

    public static final String EXTRA_ACTION_NAME = "com.example.toolhub.plugin.EXTRA_ACTION_NAME";
    public static final String EXTRA_CATEGORIES = "com.example.toolhub.plugin.EXTRA_CATEGORIES";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String agentPackage = getCallingPackage();
        final String actionName = getIntent().getStringExtra(EXTRA_ACTION_NAME);

        // startActivityForResult가 아니면 호출자를 특정할 수 없다 — 승인 불가.
        if (agentPackage == null || actionName == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        if (ConsentStore.isGranted(this, agentPackage, actionName)) {
            setResult(RESULT_OK);
            finish();
            return;
        }

        String[] categories = getIntent().getStringArrayExtra(EXTRA_CATEGORIES);
        String categoryLine = categories != null && categories.length > 0
                ? "\n\n분류: " + String.join(", ", categories)
                : "";

        new AlertDialog.Builder(this)
                .setTitle("툴 사용 승인")
                .setMessage(agentPackage + " 이(가) '" + actionName + "' 실행 권한을 요청합니다."
                        + categoryLine)
                .setPositiveButton("승인", (d, w) -> {
                    ConsentStore.grant(this, agentPackage, actionName);
                    setResult(RESULT_OK);
                    finish();
                })
                .setNegativeButton("거부", (d, w) -> {
                    setResult(RESULT_CANCELED);
                    finish();
                })
                .setOnCancelListener(d -> {
                    setResult(RESULT_CANCELED);
                    finish();
                })
                .show();
    }
}
