package com.example.toolhub.plugin;

import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.example.toolhub.common.ResultContract;

/**
 * C(플러그인) 측 사전 검사.
 *
 * Layer 0 — isTrusted(): 체인 자체가 시스템에 등록된 진짜 체인인지. 여기서
 * false가 나오면 A/B의 소스 생성 방식이 잘못된 것이므로 코드 버그다.
 * 권한 문제와 절대 섞지 않는다 (ResultContract.untrustedChain).
 *
 * Layer 2 — 링크별 checkPermission: grant 상태만 보는 경량 사전 진단이다.
 * "이번만 허용" 만료나 MODE_IGNORED 같은 AppOps 상태는 여기서 못 잡는다 —
 * checkPermission은 grant 여부만 보고, GET_APP_OPS_STATS 없이는 다른 uid의
 * AppOps 상태를 조회할 방법이 없다(그 권한은 signature|privileged라 C가
 * 가질 수 없다). B는 권한 검사를 하지 않으므로(HANDOFF 3항) 이게 사실상
 * C가 실행 전에 할 수 있는 유일한 사전 검사다. 최종 안전망은
 * PluginContentProvider의 try/catch SecurityException(Layer 3, AppOps
 * 계열)이 맡는다 — 델리게이트가 attributionContext로 실제 시스템 API를
 * 부르면 그 시점에 OS가 체인 전체를 대상으로 AppOps를 강제해준다.
 */
public final class ChainPermissionChecker {

    private ChainPermissionChecker() {}

    /**
     * @param context               C 프로세스의 일반 Context (attributionContext 아님 —
     *                               체인 신뢰 여부를 판단하는 단계이므로 아직 attributionContext가 없다)
     * @param chainedSource         C가 createContext(...)로 조립한 자신의 attributionSource
     *                               (C -> B -> A 전체 체인)
     * @param requiredPermissions   이 액션에 필요한 권한 목록
     * @return null이면 통과. 아니면 ResultContract 형식의 거부 Bundle.
     */
    public static Bundle check(Context context, AttributionSource chainedSource,
                                String[] requiredPermissions) {
        if (!chainedSource.isTrusted(context)) {
            return ResultContract.untrustedChain(
                    "attribution chain is not registered with the system (isTrusted() == false)");
        }

        PackageManager pm = context.getPackageManager();

        for (String permission : requiredPermissions) {
            AttributionSource link = chainedSource;
            while (link != null) {
                if (pm.checkPermission(permission, link.getPackageName())
                        != PackageManager.PERMISSION_GRANTED) {
                    return ResultContract.denied(
                            permission,
                            link.getPackageName(),
                            ResultContract.PHASE_PLUGIN_PREFLIGHT,
                            "grant missing on link " + link.getPackageName());
                }
                link = link.getNext();
            }
        }

        return null;
    }
}
