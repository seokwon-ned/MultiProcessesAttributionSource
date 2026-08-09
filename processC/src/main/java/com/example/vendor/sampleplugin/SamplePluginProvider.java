package com.example.vendor.sampleplugin;

import android.os.Bundle;

import com.example.toolhub.plugin.PluginContentProvider;
import com.example.toolhub.plugin.PluginPolicy;
import com.example.toolhub.plugin.ToolHandler;

import java.util.HashMap;
import java.util.Map;

public class SamplePluginProvider extends PluginContentProvider {

    @Override
    protected Map<String, ToolHandler> registerHandlers() {
        Map<String, ToolHandler> handlers = new HashMap<>();
        handlers.put("sample_action_1", new SampleActionHandler());
        handlers.put("sample_action_2", new SampleActionHandler2());
        return handlers;
    }

    /**
     * V층 벤더 정책 예시. originator는 검증된 체인의 마지막 링크이므로
     * 위조 불가 — 모드 1이면 A의 패키지명, 모드 2이면 허브 자신이다.
     * null 반환 = 허용.
     */
    @Override
    protected PluginPolicy policy() {
        return (originatorPackage, actionName, args) -> {
            // 예: 특정 에이전트 차단, 인자 검증, rate limit 등을 여기서.
            // 기본은 전부 허용.
            return null;
        };
    }
}
