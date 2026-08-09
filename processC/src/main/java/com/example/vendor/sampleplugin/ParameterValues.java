package com.example.toolhub.plugin;

import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * onPerformAction / onReversePerformAction에 마지막 인자로 전달되는
 * 타입 있는 파라미터 컨테이너.
 *
 * args Bundle에서 파생된다 — B가 별도 IPC 채널로 보내는 값이 아니라,
 * PluginContentProvider가 args Bundle을 받아 이 형태로 변환해서 델리게이트에
 * 넘긴다. Bundle은 다양한 타입을 담을 수 있지만 여기서는 문자열 값만
 * 다룬다 — 문자열이 아닌 값은 String.valueOf로 변환한다.
 */
public class ParameterValues {

    private final Map<String, String> values = new HashMap<>();

    public String get(String key) {
        return values.get(key);
    }

    public void put(String key, String value) {
        values.put(key, value);
    }

    public static ParameterValues fromBundle(Bundle args) {
        ParameterValues params = new ParameterValues();
        if (args == null) {
            return params;
        }
        for (String key : args.keySet()) {
            Object value = args.get(key);
            if (value != null) {
                params.put(key, String.valueOf(value));
            }
        }
        return params;
    }
}
