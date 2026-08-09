package com.example.vendor.sampleplugin;

import android.content.Context;
import android.os.Bundle;

import com.example.toolhub.plugin.ParameterValues;
import com.example.toolhub.plugin.ToolHandler;
import com.example.toolhub.plugin.annotation.ConsentCategory;
import com.example.toolhub.plugin.annotation.HandlerPermission;
import com.example.toolhub.plugin.annotation.RequiresUserConsent;

/**
 * 사용자 동의가 필요한 액션 예시 — 외부 발신(문자). RequiresUserConsent
 * 선언으로 U층이 활성화된다: 사용자가 (에이전트, 이 액션) 조합을 승인한
 * 적 없으면 PluginContentProvider가 STATUS_CONSENT_REQUIRED로 거부한다.
 */
public class SampleActionHandler2 implements ToolHandler {

    @HandlerPermission({"android.permission.SEND_SMS"})
    @RequiresUserConsent(categories = {ConsentCategory.SEND_EXTERNAL, ConsentCategory.COSTS_MONEY})
    @Override
    public Bundle onPerformAction(Context attributionContext, Bundle args, ParameterValues params) {
        // 실제 발신은 attributionContext를 통해 수행해야 체인 전체에 AppOps가
        // 기록된다 (ToolHandler 계약 참고).
        Bundle result = new Bundle();
        result.putString("message", "sms sent (sample)");
        return result;
    }

    @HandlerPermission({"android.permission.SEND_SMS"})
    @RequiresUserConsent(categories = {ConsentCategory.SEND_EXTERNAL})
    @Override
    public Bundle onReversePerformAction(Context attributionContext, Bundle args, ParameterValues params) {
        return new Bundle();
    }
}
