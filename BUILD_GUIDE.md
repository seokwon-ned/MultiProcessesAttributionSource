# ToolHub Multi-Module Build Guide

## 프로젝트 구조

이 프로젝트는 Gradle 멀티-모듈 구조로 구성되어 있으며, 각 process가 독립적인 Android 앱으로 빌드됩니다.

### 모듈 구성

| 모듈 | 타입 | 패키지 | 용도 |
|------|------|---------|------|
| `:aidl` | Library | com.example.toolhub | AIDL 인터페이스 (IToolHub, IToolHubCallback) |
| `:sdk` | Library | com.example.toolhub.plugin | 플러그인 SDK (ToolHandler, HandlerPermission, ParameterValues, PermissionScanner, ChainPermissionChecker) |
| `:processA` | Application | com.example.agent | Agent app (AIDL 클라이언트) |
| `:processB` | Application | com.example.toolhub | ToolHub 서비스 (/system/priv-app 배치) |
| `:processC` | Application | com.example.vendor.sampleplugin | 플러그인 (ContentProvider), `:sdk` 사용 |

## 빌드 명령어

### 전체 프로젝트 빌드
```bash
./gradlew build
```

### 개별 모듈 빌드

#### Process A (Agent)
```bash
./gradlew :processA:build
./gradlew :processA:assembleRelease    # APK 생성
```

#### Process B (ToolHub)
```bash
./gradlew :processB:build
./gradlew :processB:assembleRelease    # APK 생성
```

#### Process C (Plugin)
```bash
./gradlew :processC:build
./gradlew :processC:assembleRelease    # APK 생성
```

### 빌드 산출물

모든 APK는 각 모듈의 `build/outputs/apk/` 디렉토리에 생성됩니다.

```
processA/build/outputs/apk/release/processA-release.apk
processB/build/outputs/apk/release/processB-release.apk
processC/build/outputs/apk/release/processC-release.apk
```

## 빌드 설정

### SDK 버전 (gradle.properties)
- **compileSdk**: 34 (Android U)
- **minSdk**: 30 (Android R)
- **targetSdk**: 34

### 플러그인 버전
- **Android Gradle Plugin**: 8.3.0
- **Kotlin**: 1.9.22

## 의존성

각 process 모듈은 다음 의존성을 가집니다:

```gradle
// Build dependencies
implementation(project(":aidl"))         // AIDL 인터페이스 공유
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
```

## 서명 구성 (TODO)

릴리스 빌드 시 각 모듈별로 별도의 서명 키가 필요합니다:

1. **processB (ToolHub)**: 플랫폼 서명 키 (system image에 빌드)
2. **processC (Plugin)**: 벤더별 서명 키

`build.gradle.kts` 파일에 `signingConfigs` 블록을 추가하여 구성하세요:

```kotlin
signingConfigs {
    release {
        storeFile = file("path/to/keystore.jks")
        storePassword = "..."
        keyAlias = "..."
        keyPassword = "..."
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
    }
}
```

## 주요 특징

### Process A (Agent)
- AIDL을 통해 Process B의 ToolHubService에 접근
- 일반 사용자 앱으로 배포 가능

### Process B (ToolHub)
- Process A의 요청을 처리하고 Process C로 라우팅
- 권한 검사 없음 (순수 라우터 역할)
- `:hub` 서브프로세스에서 실행
- 부팅 완료 시 플러그인 메타데이터 미리 로드

### Process C (Plugin)
- ContentProvider를 통해 Process B로부터 호출 수신
- 실제 델리게이트 구현 및 권한 검사 담당
- 각 벤더별 독립적인 서명 가능

## 프록시드 규칙

각 모듈의 `proguard-rules.pro` 파일에서 AIDL 클래스와 주요 컴포넌트는 보존됩니다.

## 문제 해결

### Gradle 동기화 실패
```bash
./gradlew clean
./gradlew sync
```

### 빌드 캐시 삭제
```bash
./gradlew clean
```

### 특정 모듈만 재빌드
```bash
./gradlew :processA:clean :processA:build
```

## 참고 문서

- [HANDOFF.md](HANDOFF.md) - 아키텍처 및 설계 결정 사항
- Android Gradle Plugin 문서: https://developer.android.com/build
