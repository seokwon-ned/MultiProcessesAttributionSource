package com.example.toolhub.plugin;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 명시적으로 등록된 델리게이트만 리플렉션 스캔해서 액션명 -> 권한[] 맵을 만든다.
 *
 * 클래스패스 전체 스캔(덱스 스캔)은 금지 — 너무 느리다. PluginContentProvider가
 * onCreate()에서 자신의 델리게이트 등록 맵(actionName -> PluginDelegate 인스턴스,
 * 명시적으로 나열된 것)을 넘기면 그 맵만 순회한다.
 *
 * 여기서 쓰는 "액션명"이 곧 IToolHub의 actionId 중 actionName 부분이다
 * (ToolHubService가 call()의 arg 파라미터로 실어 보낸다). 이 맵은 C
 * 내부에서만 쓰인다 — PluginContentProvider.dispatch()가
 * ChainPermissionChecker.check()(Layer 2)에 넘길 권한 목록을 얻는 용도이고,
 * B에게 노출하는 IPC 엔드포인트는 없다.
 *
 * onPerformAction과 onReversePerformAction 둘 다 스캔해서 권한을 합집합으로
 * 묶는다 — dispatch() 시점엔 어느 쪽이 실행될지(perform/reverse) 이미
 * 정해져 있지만, 이 맵 자체는 액션명 단위로 한 번만 만들어두고 재사용하므로
 * 두 메서드 중 더 넓은 쪽 기준으로 미리 합쳐둔다.
 */
public final class PermissionScanner {

    private PermissionScanner() {}

    /**
     * @param registeredDelegates onCreate()에서 명시적으로 등록한 actionName -> delegate 맵
     * @return actionName -> 필요 권한 배열. HandlerPermission이 없으면 빈 배열(권한 불필요).
     */
    public static Map<String, String[]> scan(Map<String, ? extends PluginDelegate> registeredDelegates) {
        Map<String, String[]> result = new HashMap<>();

        for (Map.Entry<String, ? extends PluginDelegate> entry : registeredDelegates.entrySet()) {
            String actionName = entry.getKey();
            Class<?> clazz = entry.getValue().getClass();

            Set<String> merged = new LinkedHashSet<>();
            collectPermissions(clazz, "onPerformAction", merged);
            collectPermissions(clazz, "onReversePerformAction", merged);

            result.put(actionName, merged.toArray(new String[0]));
        }

        return Collections.unmodifiableMap(result);
    }

    private static void collectPermissions(Class<?> clazz, String methodName, Set<String> out) {
        Method method = findMethod(clazz, methodName);
        HandlerPermission ann = method != null ? method.getAnnotation(HandlerPermission.class) : null;
        if (ann != null) {
            Collections.addAll(out, ann.value());
        }
    }

    /**
     * 애노테이션은 구현 클래스의 override 메서드에 붙으므로(인터페이스
     * 선언부가 아니라), 실제 구현 클래스에서 메서드를 찾아야 한다.
     * 익명/람다 델리게이트는 이 방식으로 스캔할 수 없다 — 등록 시 반드시
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
