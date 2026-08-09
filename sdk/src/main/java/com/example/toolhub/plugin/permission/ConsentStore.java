package com.example.toolhub.plugin.permission;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 인가 파이프라인 U층의 저장소 — 사용자가 승인한 (에이전트, 액션) 조합을
 * C(플러그인) 프로세스 로컬에 영속화한다 (DESIGN.md 3장).
 *
 * 키에 에이전트 패키지명뿐 아니라 서명 해시를 포함한다 — 에이전트를 삭제한
 * 뒤 같은 패키지명의 악성 앱을 설치해 기존 승인을 물려받는 공격을 막기
 * 위함이다. 조회 시점의 실제 설치 서명으로 키를 만들므로, 서명이 바뀌면
 * 기존 승인 레코드와 키가 달라져 자동으로 무효가 된다.
 *
 * 기록은 ConsentRequestActivity(사용자 대면, C 소유)에서만 하고, 판정은
 * PluginContentProvider.dispatch()가 한다. A가 넘긴 플래그 따위는 판정에
 * 쓰지 않는다.
 */
public final class ConsentStore {

    private static final String PREFS = "toolhub_plugin_consent";

    private ConsentStore() {}

    /** 사용자가 이 (에이전트, 액션) 조합을 승인한 적 있는가. */
    public static boolean isGranted(Context context, String agentPackage, String actionName) {
        String key = keyOf(context, agentPackage, actionName);
        if (key == null) return false;
        return prefs(context).getBoolean(key, false);
    }

    /** 승인 기록. ConsentRequestActivity에서만 호출할 것. */
    public static void grant(Context context, String agentPackage, String actionName) {
        String key = keyOf(context, agentPackage, actionName);
        if (key == null) return;
        prefs(context).edit().putBoolean(key, true).apply();
    }

    /** 철회 — C의 설정 UI 등에서 호출. */
    public static void revoke(Context context, String agentPackage, String actionName) {
        String key = keyOf(context, agentPackage, actionName);
        if (key == null) return;
        prefs(context).edit().remove(key).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 패키지가 설치되어 있지 않거나 서명을 읽을 수 없으면 null (승인 불가/무효). */
    private static String keyOf(Context context, String agentPackage, String actionName) {
        String sig = signatureHashOf(context, agentPackage);
        if (sig == null) return null;
        return agentPackage + "|" + sig + "|" + actionName;
    }

    static String signatureHashOf(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return null;
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length == 0) return null;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signers[0].toByteArray());
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e) {
            return null;
        }
    }
}
