# ToolHub — A → B → C AttributionSource 체인 설계 명세

Claude Code로 이어서 작업하기 위한 인계 문서. 여기 적힌 결정은 모두 검토를
거친 것이므로 임의로 바꾸지 말 것.

---

## 폴더 구조

프로세스별로 폴더를 분리했다. `common`이 따로 없는 이유는 `ResultContract.kt`
하나뿐이라 processB에 같이 뒀기 때문 — 실제 A/B/C가 모두 참조하는 공유
계약이라는 점은 파일 상단 주석에 명시되어 있다.

```
aidl/                         — A<->B 경계 인터페이스 (누구 소유도 아님)
  IToolHub.aidl
  IToolHubCallback.aidl

processA/                     — 에이전트 (일반 앱)
  AgentToolClient.kt

processB/                     — 툴 허브 (플랫폼 서명, priv-app)
  ToolHubService.kt           — AIDL 구현, 체인 조립, 디스패치, START_STICKY
                                 (권한 검사는 하지 않는다 — 순수 라우터)
  BootCompletedReceiver.kt    — 부팅 시 ToolHubService startService()로 깨움
  PluginRegistry.kt           — 플러그인 discovery (authority -> 패키지명만.
                                 권한 정보는 캐싱하지 않는다)
  RequestRegistry.kt          — requestId 추적/타임아웃/중복 통지 방지
  ResultContract.kt           — A/B/C 공유 결과 계약 (소유는 B, 참조는 전부)

processC/                     — 플러그인 (벤더별 서명)
  HandlerPermission.java      — 델리게이트 메서드용 권한 애노테이션 (구 RequiredPermission)
  ParameterValues.java        — onPerformAction/onReversePerformAction 마지막 인자, args Bundle에서 파생
  PluginDelegate.java         — 델리게이트 계약 (onPerformAction/onReversePerformAction) + 샘플 구현
  PermissionScanner.java      — 등록된 델리게이트 스캔 -> 액션명→권한[] 맵 (두 메서드 권한 합집합).
                                 C 로컬에서만 쓰인다 — B에 노출하는 IPC 엔드포인트는 없다.
  ChainPermissionChecker.java — Layer 0 (isTrusted) + Layer 2 사전 검사 (C의 자체 권한 판단)
  PluginContentProvider.java  — call() 디스패치, Layer 3 안전망

manifests/                    — 시스템 설정 발췌/샘플
  AndroidManifest-B-excerpt.xml
  AndroidManifest-C-excerpt.xml
```

---

## 구조

```
프로세스 A (에이전트)          — 일반 앱, 사용자 대면
   ↓ AIDL: execute(actionId, args, reverse, callerSource, callback) → requestId
프로세스 B (툴 허브)           — 플랫폼 서명, /system/priv-app
   ↓ ContentProvider.call(method, arg, extras)
프로세스 C (플러그인)          — 서명키 제각각, 여러 벤더
```

데이터 흐름은 C → B → A, 어트리뷰션 체인은 C → B → A 순서.

**B는 권한 검사를 하지 않는다.** 체인을 진짜로(시스템에 등록된 형태로)
조립해서 그대로 C에 전달하는 라우터 역할만 한다. "이 액션에 실제로 어떤
권한이 필요한지, 체인의 각 링크가 그걸 갖고 있는지" 판단은 전부 C가 자기
자신의 attributionContext로 스스로 한다. (이전 버전에서는 B가
`__required_permissions`로 이 정보를 미리 캐싱해서 C를 부르기 전에
권위 있는 사전 검사를 했지만, B의 역할을 최소화하기 위해 그 경로를 전부
제거했다 — 아래 3항 참고.)

---

## 확정된 설계 결정

### 1. 체인 진위 (가장 중요)

3홉 체인이므로 "2홉 무검증 특례"가 적용되지 않는다. 각 링크의
AttributionSource는 **그 앱이 직접 만든 것**이어야 한다.

- A: `context.attributionSource` 를 그대로 전달. `Builder(myUid())` 금지.
- B: `createContext(ContextParams.Builder().setNextAttributionSource(a).build())`
  후 그 컨텍스트의 `attributionSource` 를 사용.
  **`AttributionSource.Builder(...).setNext(...)` 로 직접 조립하지 말 것** —
  토큰이 시스템에 등록되지 않아 `isTrusted()` 가 false가 된다.
- C: 받은 체인으로 동일하게 `createContext(...)` → attributionContext.

서명 수준은 체인 진위와 **무관하다**. 오해하기 쉬운 지점.

### 2. 바인더 스레드에서 끝내야 하는 일

핸들러 스레드로 넘어가면 `Binder.getCallingUid()` 가 사라진다. 따라서
AIDL 콜백(바인더 스레드) 안에서:

```kotlin
callerSource.enforceCallingUid()                  // 머리가 진짜 호출자인가
require(callerSource.next == null)                // 체인 깊이 고정
val chained = createContext(
    ContextParams.Builder().setNextAttributionSource(callerSource).build()
).attributionSource
```

여기까지 마친 뒤 `chained` 를 핸들러 메시지에 실어 보낸다.

### 3. 권한 검사는 전부 C가 한다 (B는 검사하지 않음)

| 층 | 주체 | 내용 | 목적 |
|---|---|---|---|
| 0 | C | `isTrusted()` | 체인 무효 = 코드 버그. 권한 문제와 구분 |
| 2 | C | 링크별 `checkPermission` | grant 여부만 보는 경량 사전 진단 |
| 3 | C | `try/catch SecurityException` | AppOps 계열 최종 안전망 (delegate 실행 중) |

B는 권한 검사를 전혀 하지 않는다 — "체인을 조립해서 C에 넘겨주는 라우터"로
역할이 최소화되어 있다. (Layer 1이라는 이름의 B측 권위 검사가 예전에
있었으나 제거됨 — 아래 참고.)

**왜 제거했는가**: B의 역할을 최소화하는 게 목적이었다. 대가는 분명히
있다 — `AppOpsManager.unsafeCheckOpNoThrow()` 로 다른 uid의 op 상태
("이번만 허용" 만료, `MODE_IGNORED` 등)를 보려면 `GET_APP_OPS_STATS`
(signature|privileged)가 필요한데, 그건 B만 가질 수 있고 C는 가질 수
없다. 즉 지금 구조에서 **A의 AppOps 상태**를 정확히 검사할 수 있는
주체가 없다 — C는 `checkPermission`으로 A의 grant 여부만 볼 수 있을 뿐이다.
다만 C가 실제로 `attributionContext`를 통해 보호된 시스템 API를 호출하면,
그 시점엔 OS가 체인 전체(A까지 포함)를 대상으로 AppOps를 자동으로
강제해준다 — Layer 3가 이 경우엔 사실상 A의 AppOps까지 커버한다. C가
시스템 API를 안 부르고 자체 로직만 수행하는 액션이라면 이 안전망이
없다는 뜻이니, 그런 델리게이트를 작성할 땐 유의할 것.

체인 검사(Layer 0, 2)는 **C가 받은 체인의 모든 링크**를 대상으로 한다.

### 4. 결과 계약

모든 실패 경로가 같은 Bundle 형태로 반환된다. `ResultContract.kt` 참조.

핵심은 `KEY_PHASE` 와 `KEY_DENIED_AT`:
- `PHASE_CHAIN` → 코드 버그. 권한 요청 UI를 띄우면 안 된다.
- `deniedAt == A` → 런타임 권한 요청 대상.
- `deniedAt == C` → 배포 구성 문제. 사용자가 할 수 있는 게 없다.
- (`PHASE_HUB_PREFLIGHT`는 B의 사전 검사가 제거되면서 같이 제거됐다 — 이제
  `PHASE_PLUGIN_PREFLIGHT`(Layer 2)와 `PHASE_RUNTIME`(Layer 3)만 존재.)

`STATUS_PROTOCOL_INCOMPATIBLE`: B가 보낸 `call()`의 모양이 C가 기대하는
계약과 안 맞을 때(method가 알려진 리터럴이 아님 / actionName이 없음 /
attributionSource가 없음). `STATUS_ERROR`와 의도적으로 분리했다 — 이건
"이번 요청 하나의 문제"가 아니라 **B와 C의 프로토콜 버전이 안 맞는 배포
문제**일 가능성이 높기 때문이다. B는 `/system/priv-app`(OTA로만 갱신),
C는 벤더별 독립 배포라서, C가 새 프로토콜로 먼저 업데이트되고 B가 아직
구버전인 롤아웃 구간에서 실제로 발생할 수 있다. 이 상태는 **코드로 우회할
수 없다** — attributionSource 없이 실행을 계속하면 체인 검증이라는 이
시스템의 존재 이유 자체가 무너지므로, 항상 막아야 하는 게 맞는 동작이다.
할 수 있는 건 원인을 빨리 진단하도록 `STATUS_ERROR`와 구분해두는 것뿐이고,
실제 해결은 B/C 버전을 맞추는 배포 정책(롤아웃 순서, 최소 버전 게이팅)의
몫이다.

### 5. 플러그인 권한 메타데이터

C가 `@HandlerPermission(RUNTIME)` 으로 메서드(`onPerformAction`,
`onReversePerformAction`)에 선언 → `PluginContentProvider`가 첫 `call()`
시점에 등록된 델리게이트 클래스만 한 번 리플렉션 스캔 → `액션명 → String[]`
맵. 이 맵은 **C 내부에서만** 쓰인다(`ChainPermissionChecker`의 Layer 2
검사에 넘겨줄 권한 목록을 얻는 용도) — B에게 노출하는 IPC 엔드포인트는
없다. (예전엔 `call("__required_permissions")`로 B가 이 맵을 받아
캐싱했지만 제거됨.)

클래스패스 전체 스캔은 금지(덱스 스캔이라 너무 느림). 위임 클래스를 명시적
목록으로 등록하고 그것만 순회한다.

### 6. 배포

- C의 provider permission: 현재 `signatureOrSystem` (= `signature|privileged`).
  서명키가 다르므로 B는 **priv-app 배치로만** 통과한다.
  → `signature|knownSigner` + `android:knownCerts` 로 전환 검토 중.
- B는 더 이상 플러그인이 요구하는 권한을 미러링 선언할 필요가 없다 —
  권한 검사 자체를 안 하므로. `GET_APP_OPS_STATS`, dangerous 권한
  `uses-permission` 미러링, `/etc/default-permissions/`,
  `/etc/permissions/privapp-permissions-*.xml` 전부 B 쪽에서는 불필요해져
  제거했다 (이전엔 있었음 — 아래 "구현 중 확정한 세부 설계" 참고).
- risk-tier 화이트리스트(플러그인 등록 시점 거부) 제안도 근거 데이터
  (`__required_permissions`)가 사라지면서 함께 제거했다.

---

## 작성 완료 (2026-08-02 기준 전부 완료)

### 프로세스 A (Kotlin)
- [x] `processA/AgentToolClient.kt`

### 프로세스 B (Kotlin)
- [x] `processB/ToolHubService.kt` — AIDL 구현. 바인더 스레드 검증 + 체인 조립 +
      핸들러 디스패치 + `linkToDeath` 로 A 사망 시 pending 정리. 권한 검사 없음.
- [x] `processB/PluginRegistry.kt` — 설치된 플러그인 discovery (authority ->
      패키지명). 부팅/패키지 변경 브로드캐스트 시 handler 스레드에서 갱신.
- [x] `processB/BootCompletedReceiver.kt` — 부팅 시 `ToolHubService`를
      `startService()`로 깨워 A가 bind하기 전에 discovery 캐시를 미리 채움.
- [x] `processB/RequestRegistry.kt` — requestId 추적, 타임아웃, 중복 통지 방지.
- [x] `processB/ResultContract.kt` — A/B/C 공유 결과 계약.

### 프로세스 C (Java)
- [x] `processC/HandlerPermission.java` — `@Retention(RUNTIME)` (구 RequiredPermission)
- [x] `processC/PermissionScanner.java` — 위임 클래스 스캔, 액션명→권한[] 맵
      (`onPerformAction`/`onReversePerformAction` 두 메서드의 `HandlerPermission`을
      합집합으로 스캔. C 내부에서만 쓰임)
- [x] `processC/ChainPermissionChecker.java` — `isTrusted()` + 링크 순회 사전 검사
- [x] `processC/PluginContentProvider.java` — `call()` 디스패치(method로
      perform/reverse, arg로 actionName), Layer 0/2/3, 결과 계약 준수
- [x] `processC/PluginDelegate.java` — `onPerformAction` / `onReversePerformAction`
      (Context attributionContext, Bundle args, ParameterValues params) 인터페이스 +
      샘플 구현(중첩 클래스 `Sample`)
- [x] `processC/ParameterValues.java` — 두 메서드의 마지막 인자. args Bundle에서 파생.

### 매니페스트 / 시스템 설정
- [x] B `manifests/AndroidManifest-B-excerpt.xml` — BIND_TOOLHUB permission,
      RECEIVE_BOOT_COMPLETED, 서비스/리시버 export
- [x] C `manifests/AndroidManifest-C-excerpt.xml` — BIND_PLUGIN permission 선언,
      provider 보호

---

## 구현 중 확정한 세부 설계 (문서에 없던 것 / 이후 뒤집힌 것 포함)

- actionId 형식은 `"authority/actionName"` — `ToolHubService.splitActionId()`.
- C의 델리게이트 등록은 코드로 명시(`registerDelegates(): Map<String, PluginDelegate>`),
  매니페스트 메타데이터가 아니다. 위임 클래스 스캔 금지 원칙(HANDOFF 5항)은
  지키되, 매니페스트 파싱 비용도 피했다.
- 플러그인 discovery는 provider의 `readPermission`/`writePermission` ==
  `PluginRegistry.PLUGIN_PROVIDER_PERMISSION` 을 기준으로 한다.
- `ResultContract`(Kotlin object)의 함수들에 `@JvmStatic`을 추가했다 — C 쪽
  Java 코드가 정적 호출 문법으로 쓰기 때문에 없으면 컴파일이 안 된다.
- `PluginDelegate`의 `onPerform` → `onPerformAction`으로 이름 변경, `onReversePerformAction`
  추가. 둘 다 마지막 인자로 `ParameterValues params`를 받는다 (`args` Bundle에서
  `ParameterValues.fromBundle()`로 파생 — 별도 IPC 채널 아님).
- reverse 트리거는 `IToolHub.execute()`에 `boolean reverse` 파라미터 추가 →
  `RequestRegistry.Entry.reverse` → `ToolHubService.callPlugin()`이 이 값으로
  `ContentProvider.call()`의 **method 자체**를 `ResultContract.METHOD_PERFORM_ACTION`
  / `METHOD_REVERSE_PERFORM_ACTION`("onPerformAction"/"onReversePerformAction"
  리터럴) 중 하나로 선택해서 부른다. `actionName`은 `call()`의 `arg` 파라미터에
  실어 보낸다. C의 `PluginContentProvider.call()`이 `method`로 perform/reverse를,
  `arg`로 어느 델리게이트인지를 읽어 `dispatch(actionName, reverse, extras)`로 넘긴다.
- `RequiredPermission` → `HandlerPermission`으로 이름 변경. `PermissionScanner`는
  `onPerformAction`과 `onReversePerformAction` 둘의 `@HandlerPermission`을
  **합집합**으로 스캔한다.
- **런타임에 따라 필요 권한이 달라지는 액션**은 `HandlerPermission.value()`에
  "가능한 모든 경우의 합집합(정적 상한)"을 선언하는 것으로 처리하기로 확정.
  실제 호출에서 상한의 일부만 쓰여도 안전은 깨지지 않는다 —
  `HandlerPermission.java` 주석 참고.
- `PluginRegistry`는 `PluginRegistry(context, handler)`로 `ToolHubService`의
  디스패치용 `Handler`를 공유받는다. `refreshAll()`을 `ToolHubService.onCreate()`
  (메인 스레드)에서 그대로 부르면 메인 스레드가 막힐 수 있어서 — `start()`의
  초기 스캔은 `handler.post`로, 패키지 변경 브로드캐스트도
  `registerReceiver(..., handler)`로 등록해 `onReceive()` 자체가 그 스레드에서
  돌게 했다.
- 부팅 직후 초기 로드는 `BootCompletedReceiver` → `context.startService()`로
  `ToolHubService`를 깨우는 방식. `onStartCommand()`는 `START_STICKY`를 반환.
  부팅 이후에 새로 설치된 플러그인처럼 이 브로드캐스트를 못 받는 경우를 대비해
  `ToolHubService.onCreate() -> PluginRegistry.start()`의 자체 초기 스캔도 유지.

### 뒤집힌 결정: B의 Layer 1 권위 검사 제거 (2026-08-02)

처음엔 B가 `__required_permissions`로 각 플러그인의 필요 권한을 미리
캐싱해두고(`PluginRegistry`), `GET_APP_OPS_STATS`를 가진 B만 할 수 있는
권위 있는 사전 검사(`HubPermissionChecker`, Layer 1)를 C를 부르기 **전에**
수행하는 구조였다. 등록 시점에는 risk-tier 화이트리스트(`PermissionPolicy`)로
플러그인이 과한 권한을 요구하면 등록 자체를 거부하는 방어도 있었다.

**변경 이유**: B의 부담(모든 플러그인의 전체 권한 정보를 스캔해서 내부에
들고 있는 것)을 줄이고, B의 역할을 권한 확인 없는 순수 라우터로 최소화하기
위해. 대신 C가 각 액션 수행 시 attributionSource와 자신의 권한 상태를
스스로 체크하고 결과를 반환하는 설계로 전환했다.

**삭제된 것**: `processB/HubPermissionChecker.kt`, `processB/PermissionPolicy.kt`,
`manifests/default-permissions-toolhub.xml`, `manifests/privapp-permissions-toolhub.xml`,
`ResultContract.PHASE_HUB_PREFLIGHT`, `ResultContract.KEY_REVERSE`(→ method
리터럴 방식으로 대체), C의 `"__required_permissions"` call() 엔드포인트,
B 매니페스트의 `GET_APP_OPS_STATS`/dangerous 권한 미러링, C 매니페스트의
`risk_tier` meta-data.

**받아들인 트레이드오프**: 3항 참고 — A의 AppOps 상태(권한은 있지만 "이번만
허용" 만료 등)를 검사할 주체가 이제 없다. C가 실제 시스템 API를 attributionContext
로 호출하는 액션이면 OS가 자동으로 커버해주지만, 그렇지 않은 액션은 이
구멍이 그대로 남는다.

---

## 검증 순서 (실기기)

1. C에서 `chained.isTrusted(context)` 를 찍어본다. false면 A 또는 B의 소스
   생성 방식이 잘못된 것이니 여기부터 고친다.
2. A에게서 특정 권한을 의도적으로 회수하고, 호출이 거부되는지 확인한다.
   지금 구조에서는 C의 Layer 2(`checkPermission`, grant만 확인)나 Layer 3
   (실제 시스템 API 호출 시 OS의 AppOps 강제)에서 걸려야 한다. 아무 데서도
   안 걸리면 해당 델리게이트가 attributionContext를 안 쓰고 있다는 뜻이니
   델리게이트 구현을 점검한다.
3. C의 매니페스트에서 `uses-permission`을 하나 빼고, `deniedAt`이 C로
   나오는지 확인한다.
