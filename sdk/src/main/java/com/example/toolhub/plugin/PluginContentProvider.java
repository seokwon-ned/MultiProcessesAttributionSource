package com.example.toolhub.plugin;

import android.content.AttributionSource;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextParams;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

import com.example.toolhub.common.ResultContract;
import com.example.toolhub.plugin.permission.CallerVerifier;
import com.example.toolhub.plugin.permission.ChainPermissionChecker;
import com.example.toolhub.plugin.permission.ConsentStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 프로세스 C의 진입점. B의 ContentProvider.call()을 받아 인가 파이프라인
 * T ∧ P ∧ U ∧ V 를 통과한 요청만 핸들러에 넘긴다 (DESIGN.md 3장).
 *
 *  T — CallerVerifier: 허브 uid 핀 고정 + 체인 모양 (BIND_PLUGIN 보유만으로는
 *      실행을 허락하지 않는다)
 *  P — Layer 0 isTrusted -> Layer 2 전 링크 grant -> Layer 3 SecurityException
 *  U — RequiresUserConsent 액션은 ConsentStore의 사용자 승인 레코드 필수
 *  V — 서브클래스가 제공하는 PluginPolicy 훅
 *
 * B의 사전 검사 결과는 신뢰하지 않는다 — 전 층을 여기서 재검증한다.
 * 인가의 주어(originator)는 검증된 체인의 마지막 링크다: 모드 1(A 대리
 * 호출)이면 A, 모드 2(B 자체 호출)이면 B.
 *
 * method 계약: METHOD_PERFORM_ACTION / METHOD_REVERSE_PERFORM_ACTION은 실행,
 * METHOD_DESCRIBE_ACTION은 메타데이터 조회(arg = actionName, null이면 전체).
 * 핸들러 등록은 서브클래스가 registerHandlers()에서 명시적으로 한다.
 * 클래스패스 스캔은 하지 않는다.
 */
public abstract class PluginContentProvider extends ContentProvider {

    private static final String TAG = "PluginContentProvider";
    private static final String DEFAULT_HUB_PACKAGE = "com.example.toolhub";

    private Map<String, ToolHandler> handlers = Collections.emptyMap();

    /**
     * PermissionScanner.scan() 결과를 지연 계산한다 — Layer 2/U층 검사와
     * describe 응답 생성에 쓰인다. onCreate()는 앱 시작 시퀀스의 일부로 메인
     * 스레드에서 호출되므로 리플렉션 스캔까지 끝내면 메인 스레드가 묶인다.
     * call()은 바인더 스레드 풀에서 실행되니 첫 호출 시점에 계산해도 안전하다.
     */
    private volatile Map<String, ActionMetadata> metadata;

    /**
     * 서브클래스가 자신의 액션 -> 핸들러 매핑을 명시적으로 나열한다.
     * 여기 없는 클래스는 절대 스캔되지 않는다 (덱스 전체 스캔 금지).
     */
    protected abstract Map<String, ToolHandler> registerHandlers();

    /** T층 핀 고정 대상 허브 패키지. 배포 환경이 다르면 서브클래스가 override. */
    protected String hubPackageName() {
        return DEFAULT_HUB_PACKAGE;
    }

    /** V층 훅. null이면 벤더 정책 없음(통과). */
    protected PluginPolicy policy() {
        return null;
    }

    @Override
    public boolean onCreate() {
        Map<String, ToolHandler> registered = registerHandlers();
        handlers = registered != null ? new HashMap<>(registered) : new HashMap<>();
        return true;
    }

    private Map<String, ActionMetadata> metadata() {
        Map<String, ActionMetadata> result = metadata;
        if (result == null) {
            synchronized (this) {
                result = metadata;
                if (result == null) {
                    result = PermissionScanner.scan(handlers);
                    metadata = result;
                }
            }
        }
        return result;
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        // T층 ① — 어떤 method든 허브가 아닌 호출자는 거부. BIND_PLUGIN을 가진
        // 다른 priv-app의 직접 호출(허브 사칭)을 여기서 끊는다. callingUid는
        // 바인더 스레드에서만 유효하므로 반드시 여기서 얻는다.
        int callingUid = Binder.getCallingUid();

        if (ResultContract.METHOD_DESCRIBE_ACTION.equals(method)) {
            Bundle pinDenial = verifyHubUidOnly(callingUid);
            if (pinDenial != null) return pinDenial;
            return describe(arg);
        }

        boolean reverse;
        if (ResultContract.METHOD_PERFORM_ACTION.equals(method)) {
            reverse = false;
        } else if (ResultContract.METHOD_REVERSE_PERFORM_ACTION.equals(method)) {
            reverse = true;
        } else {
            // 알려진 method 리터럴이 아니다 — B가 이 계약 자체를 모르는
            // 구버전일 가능성이 높다. STATUS_ERROR와 구분해서 진단하기 쉽게.
            return ResultContract.protocolIncompatible("unknown method: " + method);
        }

        String actionName = arg;
        if (actionName == null) {
            return ResultContract.protocolIncompatible("missing actionName (arg)");
        }

        return dispatch(callingUid, actionName, reverse, extras);
    }

    /** describe용 축약 T층 — 체인이 없으므로 uid 핀 고정만 한다. */
    private Bundle verifyHubUidOnly(int callingUid) {
        try {
            int hubUid = getContext().getPackageManager().getPackageUid(hubPackageName(), 0);
            if (callingUid != hubUid) {
                return ResultContract.unauthorized("caller is not the hub");
            }
        } catch (PackageManager.NameNotFoundException e) {
            return ResultContract.unauthorized("hub package not installed");
        }
        return null;
    }

    /**
     * 메타데이터 조회 (DESIGN.md 4장). 액션별 필요 권한을 분류(runtime/install)와
     * 링크별 grant 스냅샷(C 자신, B)과 함께 내려준다. A 자신의 grant 상태는
     * A가 로컬에서 확인하면 세 링크의 사전 그림이 완성된다.
     */
    private Bundle describe(String actionNameOrNull) {
        PackageManager pm = getContext().getPackageManager();
        String selfPackage = getContext().getPackageName();
        String hubPackage = hubPackageName();

        Bundle actions = new Bundle();
        for (Map.Entry<String, ActionMetadata> entry : metadata().entrySet()) {
            if (actionNameOrNull != null && !actionNameOrNull.equals(entry.getKey())) continue;

            ActionMetadata meta = entry.getValue();
            ArrayList<Bundle> permissionEntries = new ArrayList<>();
            for (String permission : meta.permissions) {
                Bundle p = new Bundle();
                p.putString(ResultContract.KEY_PERM_NAME, permission);
                p.putBoolean(ResultContract.KEY_PERM_IS_RUNTIME,
                        ResultContract.PERMISSION_TYPE_RUNTIME.equals(
                                ChainPermissionChecker.permissionType(pm, permission)));
                p.putBoolean(ResultContract.KEY_PERM_GRANTED_ON_PLUGIN,
                        pm.checkPermission(permission, selfPackage) == PackageManager.PERMISSION_GRANTED);
                p.putBoolean(ResultContract.KEY_PERM_GRANTED_ON_HUB,
                        pm.checkPermission(permission, hubPackage) == PackageManager.PERMISSION_GRANTED);
                permissionEntries.add(p);
            }

            Bundle action = new Bundle();
            action.putParcelableArrayList(ResultContract.KEY_PERMISSION_ENTRIES, permissionEntries);
            action.putBoolean(ResultContract.KEY_CONSENT_REQUIRED, meta.consentRequired);
            action.putStringArray(ResultContract.KEY_CONSENT_CATEGORIES, meta.consentCategories);
            actions.putBundle(entry.getKey(), action);
        }

        if (actionNameOrNull != null && actions.isEmpty()) {
            return ResultContract.error("unknown action: " + actionNameOrNull);
        }

        Bundle payload = new Bundle();
        payload.putBundle(ResultContract.KEY_ACTIONS, actions);
        return ResultContract.success(payload);
    }

    private Bundle dispatch(int callingUid, String actionName, boolean reverse, Bundle extras) {
        ToolHandler handler = handlers.get(actionName);
        if (handler == null) {
            return ResultContract.error("unknown action: " + actionName);
        }

        AttributionSource fromB = extras != null
                ? extras.getParcelable(ResultContract.KEY_ATTRIBUTION_SOURCE, AttributionSource.class)
                : null;
        if (fromB == null) {
            // B가 attributionSource를 아예 안 보냈다 — 체인 검증이라는 이
            // 시스템의 전제를 모르는 구버전 B일 가능성이 높다. 검증 없이
            // 실행을 계속하는 건 선택지가 아니므로 막되, 원인 진단이 쉽게
            // STATUS_ERROR와 구분한다.
            return ResultContract.protocolIncompatible("missing attribution source from hub");
        }

        // T층 — 허브 핀 고정 + uid 일치 + 체인 모양 (모드 1/2만 허용)
        Bundle transportDenial = CallerVerifier.verify(
                getContext(), hubPackageName(), callingUid, fromB);
        if (transportDenial != null) {
            return transportDenial;
        }

        AttributionSource chainedSource;
        Context attributionContext;
        try {
            attributionContext = getContext().createContext(
                    new ContextParams.Builder()
                            .setNextAttributionSource(fromB)
                            .build());
            chainedSource = attributionContext.getAttributionSource();
        } catch (SecurityException e) {
            // 체인 조립 자체가 거부되는 경우도 이론상 있다(next 토큰이 시스템에
            // 없을 때). isTrusted() 이전 단계지만 성격은 동일하다 — 코드 버그.
            return ResultContract.untrustedChain("createContext failed: " + e.getMessage());
        }

        ActionMetadata meta = metadata().get(actionName);
        String[] required = meta != null ? meta.permissions : new String[0];

        // P층 Layer 0 — 체인 신뢰 여부(시스템 등록 확인)
        if (!chainedSource.isTrusted(getContext())) {
            return ResultContract.untrustedChain(
                    "attribution chain is not registered with the system (isTrusted() == false)");
        }

        // P층 Layer 2-1 — C 자신의 permission 확인
        Bundle pluginDenial = ChainPermissionChecker.checkPluginPermissions(getContext(), required);
        if (pluginDenial != null) {
            return pluginDenial;
        }

        // P층 Layer 2-2 — 체인(originator: B/A)의 permission 확인
        Bundle chainDenial = ChainPermissionChecker.checkChainLinks(getContext(), chainedSource, required);
        if (chainDenial != null) {
            return chainDenial;
        }

        // 인가의 주어 — 검증된 체인의 마지막 링크 (모드 1: A, 모드 2: B).
        String originator = CallerVerifier.originatorOf(fromB);

        // V층 — 벤더 정책. U층보다 먼저 평가한다: 정책상 차단된 조합에
        // 동의 UI부터 띄우게 하는 낭비를 막는다.
        PluginPolicy policy = policy();
        if (policy != null) {
            Bundle policyDenial = policy.authorize(originator, actionName, extras);
            if (policyDenial != null) {
                return policyDenial;
            }
        }

        // U층 — 사용자 인가. A의 주장이 아니라 C 소유 저장소로 판단한다.
        if (meta != null && meta.consentRequired
                && !ConsentStore.isGranted(getContext(), originator, actionName)) {
            return ResultContract.consentRequired(actionName, meta.consentCategories);
        }

        Bundle args = extras.getBundle(ResultContract.KEY_ARGS);
        if (args == null) {
            args = Bundle.EMPTY;
        }
        ParameterValues params = ParameterValues.fromBundle(args);

        try {
            Bundle payload = reverse
                    ? handler.onReversePerformAction(attributionContext, args, params)
                    : handler.onPerformAction(attributionContext, args, params);
            return ResultContract.success(payload);
        } catch (SecurityException e) {
            // P층 Layer 3: AppOps 계열 최종 안전망. noteOp/checkOp이 여기서 던진다.
            Log.w(TAG, "action '" + actionName + "' denied at runtime", e);
            return ResultContract.denied(
                    null,
                    getContext().getPackageName(),
                    ResultContract.PHASE_RUNTIME,
                    e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "action '" + actionName + "' failed", e);
            return ResultContract.error(e.getMessage());
        }
    }

    // 이 프로바이더는 query/insert/update/delete를 지원하지 않는다 — call()만 쓴다.

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                         String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
