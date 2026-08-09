# SDK 라이브러리 구성 및 file-based 의존성 사용

## SDK AAR 빌드

SDK 모듈이 이미 `/libs/sdk.aar`로 빌드되어 있습니다.

새로 빌드하려면:
```bash
export ANDROID_HOME=~/Library/Android/sdk
gradle :sdk:clean :sdk:assembleRelease
```

생성된 AAR: `sdk/build/outputs/aar/sdk-release.aar`

## SDK 내용

SDK 라이브러리(`com.example.toolhub.plugin` 패키지)는 다음을 포함합니다:

| 클래스 | 용도 |
|-------|------|
| `ToolHandler` | 플러그인 핸들러 인터페이스 (onPerformAction, onReversePerformAction) |
| `HandlerPermission` | 런타임 권한 선언 애노테이션 |
| `ParameterValues` | 핸들러 메서드 인자 타입 래퍼 |
| `PermissionScanner` | 애노테이션 기반 권한 스캔 |
| `ChainPermissionChecker` | AttributionSource 체인 검증 |

## ProcessC에서 사용

**build.gradle.kts:**
```gradle
dependencies {
    implementation(project(":aidl"))
    implementation(files("../libs/sdk.aar"))  // File-based 의존성
    // ... 기타 의존성
}
```

### 구현 예시
```java
// 1. ToolHandler 구현
public class MyActionHandler implements ToolHandler {
    @HandlerPermission({"android.permission.READ_CONTACTS"})
    @Override
    public Bundle onPerformAction(Context ctx, Bundle args, ParameterValues params) {
        // 액션 수행
        return result;
    }

    @HandlerPermission({"android.permission.READ_CONTACTS"})
    @Override
    public Bundle onReversePerformAction(Context ctx, Bundle args, ParameterValues params) {
        // 되돌리기
        return new Bundle();
    }
}

// 2. PluginContentProvider 서브클래스에서 등록
public class MyPluginProvider extends PluginContentProvider {
    @Override
    protected Map<String, ToolHandler> registerHandlers() {
        Map<String, ToolHandler> handlers = new HashMap<>();
        handlers.put("action_name", new MyActionHandler());
        return handlers;
    }
}

// 3. AndroidManifest.xml
<provider
    android:name=".MyPluginProvider"
    android:authorities="com.example.plugin"
    android:exported="true"
    android:readPermission="com.example.toolhub.permission.BIND_PLUGIN"
    android:writePermission="com.example.toolhub.permission.BIND_PLUGIN" />
```

## ProcessB에서 사용 (Optional)

ProcessB가 SDK를 사용하려면, `processB/build.gradle.kts`에 추가:
```gradle
dependencies {
    implementation(project(":aidl"))
    implementation(files("../libs/sdk.aar"))
    // ...
}
```

**주의:** ProcessB는 현재 ResultContract를 로컬에 가지고 있으며, aidl 모듈도 복사본을 가지고 있습니다. 
나중에 정리가 필요합니다:
- 중복 제거: ProcessB의 ResultContract.kt 삭제
- aidl 모듈의 ResultContract import로 통일

## 파일 구조

```
project/
├── libs/
│   └── sdk.aar                  ← File-based library (프로젝트 level)
├── sdk/
│   ├── build.gradle.kts         ← library 모듈 (project dependency 사용)
│   └── src/main/java/com/example/toolhub/plugin/
│       ├── ToolHandler.java
│       ├── HandlerPermission.java
│       ├── ParameterValues.java
│       ├── PermissionScanner.java
│       └── ChainPermissionChecker.java
├── processC/
│   ├── build.gradle.kts         ← files("../libs/sdk.aar") 사용
│   └── src/main/java/com/example/vendor/sampleplugin/
│       ├── PluginContentProvider.java    ← abstract
│       ├── SamplePluginProvider.java     ← concrete subclass
│       ├── SampleActionHandler.java      ← ToolHandler 구현
│       └── SampleActionHandler2.java     ← ToolHandler 구현
├── aidl/                        ← SDK 의존 (project dependency)
│   ├── build.gradle.kts
│   └── src/main/
│       ├── aidl/
│       │   └── com/example/toolhub/
│       │       ├── IToolHub.aidl
│       │       └── IToolHubCallback.aidl
│       └── java/com/example/toolhub/common/
│           └── ResultContract.kt         ← 이동됨 (processB와 공유)
```

## 빌드 참고사항

- SDK는 `:aidl` 프로젝트 의존성이 필요 (ResultContract 참조)
- ProcessC는 SDK AAR을 file-based로 참조
- settings.gradle.kts에서 `:sdk` 제거됨 (프로젝트 모듈에서 라이브러리로 전환)
