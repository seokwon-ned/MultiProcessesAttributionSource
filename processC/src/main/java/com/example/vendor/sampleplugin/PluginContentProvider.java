package com.example.toolhub.plugin;

import android.content.AttributionSource;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextParams;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.example.toolhub.common.ResultContract;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 프로세스 C의 진입점. B의 ContentProvider.call()을 받는다.
 *
 * B는 권한 검사를 하지 않는다 — call(authority, method, arg, extras)에서
 * method는 항상 ResultContract.METHOD_PERFORM_ACTION /
 * METHOD_REVERSE_PERFORM_ACTION 둘 중 하나고(어느 델리게이트 메서드를
 * 부를지), arg는 어느 액션(델리게이트)이냐(actionName)를 지정한다. 체인
 * 조립 -> Layer 0/2 자체 검사(ChainPermissionChecker, HandlerPermission
 * 기반) -> 델리게이트 실행 -> Layer 3 안전망 순서로, 권한 판단은 전부
 * 여기(C) 안에서 끝난다. 예전엔 B가 "__required_permissions"로 이 정보를
 * 미리 물어봐서 자체적으로도 사전 검사를 했지만, B의 역할을 "체인을 조립해서
 * 넘겨주는 라우터"로 최소화하기 위해 그 엔드포인트를 제거했다.
 *
 * 델리게이트 등록은 서브클래스가 registerDelegates()에서 명시적으로 한다.
 * 클래스패스 스캔은 하지 않는다.
 */
public abstract class PluginContentProvider extends ContentProvider {

    private static final String TAG = "PluginContentProvider";

    private Map<String, PluginDelegate> delegates = Collections.emptyMap();

    /**
     * PermissionScanner.scan() 결과를 지연 계산한다 — dispatch()에서 Layer 2
     * 검사(ChainPermissionChecker.check)에 넘길 권한 목록을 얻는 데 쓰인다.
     * onCreate()는 앱 시작 시퀀스의 일부로 메인 스레드에서 호출되므로,
     * 여기서 리플렉션 스캔까지 끝내버리면 메인 스레드가 그만큼 묶인다.
     * call()은 바인더 스레드 풀에서 실행되니 첫 호출 시점에 계산해도 안전하다.
     */
    private volatile Map<String, String[]> requiredPermissions;

    /**
     * 서브클래스가 자신의 액션 -> 델리게이트 매핑을 명시적으로 나열한다.
     * 여기 없는 클래스는 절대 스캔되지 않는다 (덱스 전체 스캔 금지).
     */
    protected abstract Map<String, PluginDelegate> registerDelegates();

    @Override
    public boolean onCreate() {
        Map<String, PluginDelegate> registered = registerDelegates();
        delegates = registered != null ? new HashMap<>(registered) : new HashMap<>();
        return true;
    }

    private Map<String, String[]> requiredPermissions() {
        Map<String, String[]> result = requiredPermissions;
        if (result == null) {
            synchronized (this) {
                result = requiredPermissions;
                if (result == null) {
                    result = PermissionScanner.scan(delegates);
                    requiredPermissions = result;
                }
            }
        }
        return result;
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
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
            // method는 알아봤는데 actionName(arg)이 없다 — B가 method/arg
            // 역할 분담을 모르는 중간 버전일 가능성.
            return ResultContract.protocolIncompatible("missing actionName (arg)");
        }

        return dispatch(actionName, reverse, extras);
    }

    private Bundle dispatch(String actionName, boolean reverse, Bundle extras) {
        PluginDelegate delegate = delegates.get(actionName);
        if (delegate == null) {
            return ResultContract.error("unknown action: " + actionName);
        }

        AttributionSource fromB = extras != null
                ? extras.getParcelable(ResultContract.KEY_ATTRIBUTION_SOURCE, AttributionSource.class)
                : null;
        if (fromB == null) {
            // B가 attributionSource를 아예 안 보냈다 — 이 시스템의 핵심
            // 전제(체인 기반 검증)를 모르는 구버전 B일 가능성이 높다.
            // 검증 없이 실행을 계속하는 건 선택지가 아니다(이 시스템의
            // 존재 이유 자체가 체인 검증이므로) — 막되, 원인을 진단할 수
            // 있게 STATUS_ERROR와 구분한다.
            return ResultContract.protocolIncompatible("missing attribution source from hub");
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

        String[] required = requiredPermissions().getOrDefault(actionName, new String[0]);
        Bundle chainDenial = ChainPermissionChecker.check(getContext(), chainedSource, required);
        if (chainDenial != null) {
            return chainDenial;
        }

        Bundle args = extras.getBundle(ResultContract.KEY_ARGS);
        if (args == null) {
            args = Bundle.EMPTY;
        }
        ParameterValues params = ParameterValues.fromBundle(args);

        try {
            Bundle payload = reverse
                    ? delegate.onReversePerformAction(attributionContext, args, params)
                    : delegate.onPerformAction(attributionContext, args, params);
            return ResultContract.success(payload);
        } catch (SecurityException e) {
            // Layer 3: AppOps 계열 최종 안전망. noteOp/checkOp이 여기서 던진다.
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
