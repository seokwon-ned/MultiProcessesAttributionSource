package com.example.toolhub.plugin.permission;

import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.example.toolhub.common.ResultContract;

/**
 * 인가 파이프라인의 T층 — "정말 허브(B)를 거쳐, 허용된 모양의 체인으로
 * 왔는가" (DESIGN.md 3장).
 *
 * BIND_PLUGIN은 provider 접근 인증일 뿐이다. signatureOrSystem 수준이라
 * 허브가 아닌 다른 priv-app도 통과할 수 있으므로, 여기서 호출자 신원을
 * 허브 패키지 uid로 핀 고정한다. 검사 항목:
 *
 *  ① Binder.getCallingUid() == 허브 패키지의 uid (사칭 차단)
 *  ② fromB.uid == callingUid — B가 자기 자신의 소스를 보냈는가
 *  ③ 체인 모양: next == null(모드 2 — B 자체 호출) 또는
 *     next.next == null(모드 1 — A 대리 호출). 그 이상 깊이는 거부.
 *
 * ①은 반드시 바인더 스레드(call() 안)에서 얻은 callingUid로 검사해야 한다.
 */
public final class CallerVerifier {

    private CallerVerifier() {}

    /**
     * @param callingUid Binder.getCallingUid() — provider call() 안에서 얻은 값
     * @return null이면 통과. 아니면 ResultContract.unauthorized 거부 Bundle.
     */
    public static Bundle verify(Context context, String hubPackageName,
                                 int callingUid, AttributionSource fromB) {
        int hubUid;
        try {
            hubUid = context.getPackageManager().getPackageUid(hubPackageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return ResultContract.unauthorized("hub package not installed: " + hubPackageName);
        }

        if (callingUid != hubUid) {
            return ResultContract.unauthorized(
                    "caller uid " + callingUid + " is not the hub (" + hubPackageName + ")");
        }

        if (fromB.getUid() != callingUid) {
            return ResultContract.unauthorized(
                    "attribution head uid " + fromB.getUid() + " != calling uid " + callingUid);
        }

        AttributionSource next = fromB.getNext();
        if (next != null && next.getNext() != null) {
            return ResultContract.unauthorized("chain deeper than hub->agent is not allowed");
        }

        return null;
    }

    /**
     * 인가의 주어 — 검증된 체인의 마지막 링크 패키지.
     * 모드 1(대리 호출)이면 A, 모드 2(B 자체 호출)이면 B 자신이다.
     * verify() 통과 후에만 호출할 것.
     */
    public static String originatorOf(AttributionSource fromB) {
        AttributionSource next = fromB.getNext();
        String pkg = next != null ? next.getPackageName() : fromB.getPackageName();
        return pkg != null ? pkg : "";
    }
}
