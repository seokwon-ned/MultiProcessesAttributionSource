package com.example.toolhub.plugin;

import android.content.Context;
import android.os.Bundle;

/**
 * 벤더가 구현하는 실제 액션 델리게이트의 계약.
 *
 * onPerformAction / onReversePerformAction에 전달되는 Context는 반드시
 * attributionContext여야 한다 (PluginContentProvider가
 * createContext(ContextParams.Builder().setNextAttributionSource(...))
 * 로 만든 것). 여기서 얻은 Context로 checkPermission / noteOp을 수행해야
 * A -> B -> C 체인이 실제 AppOps 기록에 반영된다. 일반 context를 쓰면
 * 체인을 조립한 의미가 없어진다.
 *
 * 반환값은 payload 그 자체다. ResultContract.success()로 감싸는 일은
 * PluginContentProvider가 담당하므로 델리게이트는 신경 쓰지 않는다.
 * SecurityException을 던지면 PluginContentProvider가 Layer 3 안전망에서
 * 잡아 STATUS_PERMISSION_DENIED / PHASE_RUNTIME 으로 변환한다.
 */
public interface PluginDelegate {

    Bundle onPerformAction(Context attributionContext, Bundle args, ParameterValues params)
            throws SecurityException;

    Bundle onReversePerformAction(Context attributionContext, Bundle args, ParameterValues params)
            throws SecurityException;

    /**
     * 등록 예시. PermissionScanner가 스캔하는 대상이 이런 형태다.
     * 실제 벤더 델리게이트는 이 클래스를 그대로 등록해도 되고, 같은 패턴으로
     * 자체 클래스를 작성해도 된다.
     */
    final class Sample implements PluginDelegate {

        @HandlerPermission({"android.permission.READ_CONTACTS"})
        @Override
        public Bundle onPerformAction(Context attributionContext, Bundle args, ParameterValues params) {
            // attributionContext.checkPermission / attributionContext.getSystemService(...)
            // 등 실제 리소스 접근은 여기, 반드시 attributionContext를 통해 수행한다.
            Bundle result = new Bundle();
            result.putString("echo", args.getString("query"));
            return result;
        }

        @HandlerPermission({"android.permission.READ_CONTACTS"})
        @Override
        public Bundle onReversePerformAction(Context attributionContext, Bundle args, ParameterValues params) {
            // onPerformAction을 되돌리는 로직. 필요한 상태는 params로 받는다.
            return new Bundle();
        }
    }
}
