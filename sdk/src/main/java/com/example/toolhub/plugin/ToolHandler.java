package com.example.toolhub.plugin;

import android.content.Context;
import android.os.Bundle;

/**
 * 벤더가 구현하는 실제 액션 핸들러의 계약.
 *
 * onPerformAction / onReversePerformAction에 전달되는 Context는 반드시
 * attributionContext여야 한다 (PluginContentProvider가
 * createContext(ContextParams.Builder().setNextAttributionSource(...))
 * 로 만든 것). 여기서 얻은 Context로 checkPermission / noteOp을 수행해야
 * A -> B -> C 체인이 실제 AppOps 기록에 반영된다. 일반 context를 쓰면
 * 체인을 조립한 의미가 없어진다.
 *
 * 반환값은 payload 그 자체다. ResultContract.success()로 감싸는 일은
 * PluginContentProvider가 담당하므로 핸들러는 신경 쓰지 않는다.
 * SecurityException을 던지면 PluginContentProvider가 Layer 3 안전망에서
 * 잡아 STATUS_PERMISSION_DENIED / PHASE_RUNTIME 으로 변환한다.
 */
public interface ToolHandler {

    Bundle onPerformAction(Context attributionContext, Bundle args, ParameterValues params)
            throws SecurityException;

    Bundle onReversePerformAction(Context attributionContext, Bundle args, ParameterValues params)
            throws SecurityException;

}
