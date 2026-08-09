package com.example.toolhub.plugin;

import com.example.toolhub.plugin.annotation.HandlerPermission;
import com.example.toolhub.plugin.annotation.RequiresUserConsent;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 명시적으로 등록된 핸들러만 리플렉션 스캔해서 액션명 -> ActionMetadata
 * (권한 상한 + 동의 필요 여부)를 만든다.
 *
 * 클래스패스 전체 스캔(덱스 스캔)은 금지 — 너무 느리다. PluginContentProvider가
 * onCreate()에서 자신의 핸들러 등록 맵(actionName -> ToolHandler 인스턴스,
 * 명시적으로 나열된 것)을 넘기면 그 맵만 순회한다.
 *
 * 여기서 쓰는 "액션명"이 곧 IToolHub의 actionId 중 actionName 부분이다
 * (ToolHubService가 call()의 arg 파라미터로 실어 보낸다). 이 맵은 C 내부
 * 검사(Layer 2, U층)와 describe 응답 생성에 쓰인다.
 *
 * onPerformAction과 onReversePerformAction 둘 다 스캔해서 권한/동의를
 * 합집합으로 묶는다 — dispatch() 시점엔 어느 쪽이 실행될지(perform/reverse)
 * 이미 정해져 있지만, 이 맵 자체는 액션명 단위로 한 번만 만들어두고
 * 재사용하므로 두 메서드 중 더 넓은 쪽 기준으로 미리 합쳐둔다.
 */
public final class PermissionScanner {

    private PermissionScanner() {}

    /**
     * @param registeredHandlers onCreate()에서 명시적으로 등록한 actionName -> handler 맵
     * @return actionName -> ActionMetadata. 애노테이션이 없으면 빈 권한/동의 불필요.
     */
    public static Map<String, ActionMetadata> scan(Map<String, ? extends ToolHandler> registeredHandlers) {
        Map<String, ActionMetadata> result = new HashMap<>();

        for (Map.Entry<String, ? extends ToolHandler> entry : registeredHandlers.entrySet()) {
            String actionName = entry.getKey();
            Class<?> clazz = entry.getValue().getClass();

            Set<String> permissions = new LinkedHashSet<>();
            Set<String> categories = new LinkedHashSet<>();
            boolean consentRequired = false;

            for (String methodName : new String[]{"onPerformAction", "onReversePerformAction"}) {
                Method method = findMethod(clazz, methodName);
                if (method == null) continue;

                HandlerPermission perm = method.getAnnotation(HandlerPermission.class);
                if (perm != null) {
                    Collections.addAll(permissions, perm.value());
                }
                RequiresUserConsent consent = method.getAnnotation(RequiresUserConsent.class);
                if (consent != null) {
                    consentRequired = true;
                    Collections.addAll(categories, consent.categories());
                }
            }

            result.put(actionName, new ActionMetadata(
                    permissions.toArray(new String[0]),
                    consentRequired,
                    categories.toArray(new String[0])));
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * 애노테이션은 구현 클래스의 override 메서드에 붙으므로(인터페이스
     * 선언부가 아니라), 실제 구현 클래스에서 메서드를 찾아야 한다.
     * 익명/람다 핸들러는 이 방식으로 스캔할 수 없다 — 등록 시 반드시
     * 이름 있는 클래스를 사용해야 한다.
     */
    private static Method findMethod(Class<?> clazz, String methodName) {
        try {
            return clazz.getMethod(methodName,
                    android.content.Context.class, android.os.Bundle.class, ParameterValues.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
