package com.example.toolhub.plugin.permission;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * C(플러그인)의 런타임 권한 획득 UI (DESIGN.md 5장).
 *
 * C는 ContentProvider 기반 헤드리스 플러그인이라 스스로 런타임 권한을 요청할
 * 계기가 없다. 실행 실패가 deniedAt == C(플러그인) && type == runtime으로
 * 보고되면(recoveryAction == RECOVERY_REQUEST_PLUGIN_PERMISSION), A가 이
 * Activity를 startActivityForResult로 띄워 C의 grant를 획득하게 한다.
 *
 * exported이지만 안전하다 — requestPermissions()는 이 앱(C) 자신의
 * manifest에 선언된 권한만 요청할 수 있고, 승인 여부는 시스템 다이얼로그로
 * 사용자가 결정한다. 요청 권한 목록이 비어 있거나 이미 전부 grant면
 * 다이얼로그 없이 즉시 RESULT_OK.
 */
public class PluginPermissionActivity extends Activity {

    public static final String EXTRA_PERMISSIONS = "com.example.toolhub.plugin.EXTRA_PERMISSIONS";

    private static final int REQUEST_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String[] requested = getIntent().getStringArrayExtra(EXTRA_PERMISSIONS);
        if (requested == null || requested.length == 0) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String permission : requested) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }

        if (missing.isEmpty()) {
            setResult(RESULT_OK);
            finish();
            return;
        }

        requestPermissions(missing.toArray(new String[0]), REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        setResult(allGranted ? RESULT_OK : RESULT_CANCELED);
        finish();
    }
}
