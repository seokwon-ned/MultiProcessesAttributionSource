package com.example.toolhub.plugin.permission;

import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Bundle;

import com.example.toolhub.common.ResultContract;

/**
 * C(플러그인) 측 사전 검사 — 인가 파이프라인의 P층 (DESIGN.md 3장).
 *
 * Layer 0 — isTrusted(): 체인 자체가 시스템에 등록된 진짜 체인인지. 여기서
 * false가 나오면 A/B의 소스 생성 방식이 잘못된 것이므로 코드 버그다.
 * 권한 문제와 절대 섞지 않는다 (ResultContract.untrustedChain).
 *
 * Layer 2 — 링크별 checkPermission: grant 상태만 보는 경량 사전 진단이다.
 * checkPermission은 런타임(dangerous) 권한의 grant 상태를 반영하므로 런타임
 * 권한 검사로도 유효하다. 다만 "이번만 허용" 만료나 MODE_IGNORED 같은
 * AppOps 상태는 여기서 못 잡는다 — 그건 GET_APP_OPS_STATS를 가진 B의
 * advisory preflight가 맡고(DESIGN.md 6장), 최종 안전망은
 * PluginContentProvider의 try/catch SecurityException(Layer 3)이다.
 *
 * 거부 시 권한 분류(runtime/install)를 함께 보고한다 — A의 복구 분기
 * (recoveryAction)가 이 값으로 갈린다: runtime이면 해당 링크가 grant를
 * 받으면 되고, install이면 배포 문제다.
 */
public final class ChainPermissionChecker {

    private ChainPermissionChecker() {}

    /**
     * @param context               C 프로세스의 일반 Context (attributionContext 아님 —
     *                               체인 신뢰 여부를 판단하는 단계이므로 아직 attributionContext가 없다)
     * @param chainedSource         C가 createContext(...)로 조립한 자신의 attributionSource
     *                               (모드 1: C -> B -> A / 모드 2: C -> B)
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
                            "grant missing on link " + link.getPackageName(),
                            permissionType(pm, permission));
                }
                link = link.getNext();
            }
        }

        return null;
    }

    /** dangerous(런타임) 권한이면 runtime, 그 외/조회 실패는 install로 분류. */
    public static String permissionType(PackageManager pm, String permission) {
        try {
            PermissionInfo info = pm.getPermissionInfo(permission, 0);
            return info.getProtection() == PermissionInfo.PROTECTION_DANGEROUS
                    ? ResultContract.PERMISSION_TYPE_RUNTIME
                    : ResultContract.PERMISSION_TYPE_INSTALL;
        } catch (PackageManager.NameNotFoundException e) {
            return ResultContract.PERMISSION_TYPE_INSTALL;
        }
    }
}
