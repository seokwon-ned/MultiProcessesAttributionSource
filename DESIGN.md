# ToolHub 인가(Authorization) 설계 — A/B/C 권한·동의·정책 모델

HANDOFF.md 이후 논의로 확정된 설계. HANDOFF의 "B는 권한 검사를 하지 않는다"
결정은 이 문서로 **번복**된다 (근거는 6장).

---

## 1. 목표

1. C(플러그인)의 액션은 manifest 선언 권한뿐 아니라 **런타임 grant 권한**을
   요구할 수 있다 — 사전에 파악·복구 가능해야 한다.
2. A는 실행 전에 액션의 **필수 권한 목록/동의 필요 여부를 조회**할 수 있어야
   한다 (describe).
3. 데이터 변경·삭제, 외부 발신(문자/이메일) 등은 **툴 제공자가 사용자 동의
   필요를 선언**하고, A가 이를 알 수 있어야 하며, C가 강제해야 한다.
4. `BIND_PLUGIN` 보유(인증)만으로 실행을 허락하지 않는다 — **인가는 별도
   파이프라인**으로 판단한다.
5. B도 자체 기능을 위해 C의 툴을 **1차 소비자로 직접 사용**할 수 있다.

## 2. 구성요소와 호출 모드

```
모드 1 — 대리 호출:  A ──AIDL──▶ B ──ContentProvider.call──▶ C   체인: C→B→A (3홉)
모드 2 — B 자체 호출:            B ──ContentProvider.call──▶ C   체인: C→B   (2홉)
```

- A↔B: AIDL bound service (`IToolHub.execute/describe/cancel`).
- B 내부: HandlerThread로 요청 직렬화 (`RequestRegistry`로 requestId/타임아웃).
- B→C: `ContentProvider.call(authority, method, arg, extras)`.
  체인 AttributionSource는 `extras.putParcelable(KEY_ATTRIBUTION_SOURCE, ...)`.
- 모드 2에서 B는 자기 `context.attributionSource`를 **next 없이** 전달한다.

## 3. 인가 파이프라인 — T ∧ P ∧ U ∧ V (전부 C가 최종 강제)

`BIND_PLUGIN`은 provider 접근 인증일 뿐이다. 실행 허용은 아래 4층의 논리곱.

| 층 | 이름 | 검사 내용 | 구현 위치 |
|---|---|---|---|
| **T** | 전송 경로 검증 | ① `Binder.getCallingUid()` == 허브 패키지 uid (핀 고정) ② `fromB.uid == callingUid` ③ 체인 모양: next==null(모드2) 또는 next.next==null(모드1). 3링크 이상 거부 | SDK `CallerVerifier` |
| **P** | 권한 | Layer 0 `isTrusted()` → Layer 2 전 링크 `checkPermission` → Layer 3 실행 중 `SecurityException` 안전망 | SDK `ChainPermissionChecker` + provider |
| **U** | 사용자 인가 | `@RequiresUserConsent` 액션은 (originator 패키지+서명해시, actionName) 승인 레코드가 C의 `ConsentStore`에 있어야 실행. A의 주장(ack 플래그)이 아니라 **C 소유 저장소**로 판단 | SDK `ConsentStore` + `ConsentRequestActivity` |
| **V** | 벤더 정책 | `PluginPolicy.authorize(originator, action, args)` — allowlist, 인자 검증, rate limit 등 벤더 자율 훅 | SDK `PluginPolicy` (C가 구현) |

**인가의 주어(originator) = 검증된 체인의 마지막 링크.**
모드 1이면 A, 모드 2면 B. 특례 없이 동일 규칙 적용 (B 완화는 벤더가
`PluginPolicy`에서 명시적으로 결정).

**신뢰 원칙**: C는 상류(B)의 어떤 검사 결과도 신뢰하지 않고 전 층을 재검증
한다. B의 사전 검사는 advisory(fail-fast)이므로 B 캐시의 staleness는 보안
문제가 아니라 가용성 문제로 격하된다.

## 4. 메타데이터 계약 — describe

### 선언 (C, 단일 소스)

```java
@HandlerPermission({"android.permission.READ_CONTACTS"})   // 필요 권한 (정적 상한)
@RequiresUserConsent(categories = {ConsentCategory.SEND_EXTERNAL})  // 동의 필요 선언
@Override
public Bundle onPerformAction(Context ctx, Bundle args, ParameterValues params) { ... }
```

`PermissionScanner`가 등록된 핸들러만 스캔해 `actionName → ActionMetadata
{permissions[], consentRequired, consentCategories[]}`를 만든다 (기존 지연
계산·명시 등록 원칙 유지).

### 조회 경로

```
A ──IToolHub.describe(actionId)──▶ B ──call(METHOD_DESCRIBE_ACTION)──▶ C
A ◀── per-action 메타데이터 ────── B (캐시, 해석하지 않음) ◀── C
```

C의 응답(액션별):

| 키 | 내용 |
|---|---|
| `KEY_PERMISSION_ENTRIES` | `[{name, isRuntime, grantedOnPlugin, grantedOnHub}]` — C가 자신·B의 grant 상태를 스냅샷으로 계산 |
| `KEY_CONSENT_REQUIRED` / `KEY_CONSENT_CATEGORIES` | 동의 필요 여부/분류 |

A 자신의 grant 상태는 A가 로컬 `checkSelfPermission`으로 확인 — 이로써 세
링크의 사전 그림이 완성된다. describe는 실행이 아니므로 체인 조립·권한 검사
없이 T층 ①(허브 핀 고정)만 적용한다.

B는 authority 단위로 응답을 캐싱하고, `PluginRegistry`의 패키지 변경
브로드캐스트 시 무효화한다. **B는 내용을 해석하지 않는다** — 예외는 자신의
advisory preflight(6장) 입력으로 쓰는 것뿐이며, 권위 판단은 항상 C.

## 5. 런타임 권한 처리

`pm.checkPermission`은 런타임 권한의 grant 상태를 반영하므로 Layer 2는 이미
유효하다. 문제는 **획득 경로**:

| 링크 | 획득 방법 |
|---|---|
| A | 자신의 런타임 권한 UI (`requestPermissions`) |
| B | UI 없는 priv-app → manifest 미러링 + `/etc/default-permissions/` 사전 grant (유일한 경로) |
| C | 헤드리스 플러그인 → SDK 제공 `PluginPermissionActivity`를 **A가 실행**해 획득 |

`ResultContract.denied`에 `KEY_PERMISSION_TYPE`(runtime/install)이 포함되어,
describe를 안 거친 호출도 실패 응답만으로 복구 분기가 가능하다:

| 실패 상황 | A의 복구 (`ResultContract.recoveryAction`) |
|---|---|
| A에 런타임 권한 없음 | `request_self_permission` — A 자신의 권한 UI |
| C에 런타임 권한 없음 | `request_plugin_permission` — C의 `PluginPermissionActivity` 실행 |
| 동의 미승인 (`STATUS_CONSENT_REQUIRED`) | `request_consent` — C의 `ConsentRequestActivity` 실행 후 재시도 |
| install-time 누락 / B grant 누락 / 체인 무효 | `none` — 배포·코드 문제, 안내만 |

## 6. B의 역할 (HANDOFF 결정 번복)

### 6.1 권한 미러링은 선택이 아니라 필수

- 결과 데이터가 물리적으로 C→B→A로 흐르고, OS의 체인 강제
  (`checkPermissionForDataDelivery`)는 **체인의 모든 링크**에 grant를 요구한다.
- B는 **1차 소비자**이기도 하다(모드 2) — 자체 기능이 쓰는 툴의 권한이 필요.

→ B manifest에 플러그인 생태계 권한의 합집합을 `uses-permission` 미러링 +
priv-app 사전 grant. "B 최소화"로 아낄 수 있던 것은 검사 로직뿐이었고,
미러링은 애초에 피할 수 없었다.

### 6.2 advisory preflight 부활 (구 Layer 1)

미러링이 어차피 강제라면, B에 검사를 되살리는 한계 비용은 작고 이득이 크다:

1. **AppOps 사각지대 해소** — A의 "이번만 허용" 만료, `MODE_IGNORED`는
   `GET_APP_OPS_STATS`(signature|privileged)를 가진 B만 사전 감지 가능.
   C는 grant 여부밖에 못 본다.
2. **콜드스타트 회피** — 거부될 호출을 B에서 끊으면 C 프로세스를 깨우는
   최대 비용이 0이 된다.

단, B의 판단은 **advisory** — 통과시켜도 C가 재검증하고, 잘못 거부하면
재시도로 해결된다(`PHASE_HUB_PREFLIGHT`로 구분 보고).

## 7. ResultContract 확장 요약

| 추가 | 값/용도 |
|---|---|
| `METHOD_DESCRIBE_ACTION` | C provider 메타데이터 조회 method |
| `STATUS_CONSENT_REQUIRED` | U층 거부 — A는 동의 UI 유도 후 재시도 |
| `STATUS_UNAUTHORIZED` | T/V층 거부 |
| `PHASE_HUB_PREFLIGHT` | B advisory 검사 거부 (부활) |
| `KEY_PERMISSION_TYPE` | `runtime` / `install` |
| `KEY_PERMISSION_ENTRIES`, `KEY_CONSENT_*`, `KEY_ACTIONS` | describe 응답 |
| `recoveryAction()` | 실패 Bundle → A의 복구 분기 결정 |

## 8. 위협 → 방어 맵

| 위협 | 방어 |
|---|---|
| 다른 priv-app이 B를 사칭해 C 직접 호출 | T① 허브 패키지 uid 핀 고정 (BIND_PLUGIN 보유만으로는 불충분) |
| 위조 AttributionSource | T② uid 일치 + P Layer 0 `isTrusted()` |
| 비정상 체인 깊이(4홉 이상 위장) | T③ 체인 모양 고정 |
| grant는 있으나 사용자가 툴 사용을 허락한 적 없음 | U ConsentStore (C 소유) |
| 에이전트 삭제 후 동명 악성 앱 재설치 | U 키에 서명 해시 포함 |
| A의 ack 위조 | U는 A 주장이 아닌 C 저장소로 판단, 승인은 C 소유 Activity에서만 |
| 특정 에이전트 차단/인자 남용 | V PluginPolicy |
| "이번만 허용" 만료 후 실행 | B AppOps preflight (advisory) + 시스템 API 호출 시 OS 체인 강제 |
| B 캐시 staleness | C가 전 층 재검증 → 가용성 문제로 격하 |

## 9. 모듈/파일 맵

```
aidl/  (com.example.toolhub / .common)
  IToolHub.aidl(문서용), ResultContract.kt          ← 계약 확장

sdk/   (com.example.toolhub.plugin) → libs/sdk.aar
  ToolHandler, HandlerPermission, ParameterValues     (기존)
  RequiresUserConsent, ConsentCategory                동의 선언
  ActionMetadata, PermissionScanner                   메타데이터 스캔
  CallerVerifier                                      T층
  ChainPermissionChecker                              P층 (type 포함)
  ConsentStore, ConsentRequestActivity                U층
  PluginPermissionActivity                            C 런타임 권한 획득 UI
  PluginPolicy                                        V층 훅
  PluginContentProvider                               파이프라인 통합 + describe

processC/  SamplePluginProvider(등록+정책), Sample 핸들러들
processB/  ToolHubService(describe 릴레이/캐시 + preflight + executeAsHub),
           manifest 권한 미러링 + GET_APP_OPS_STATS
processA/  AgentToolClient(describe/preflight/복구 분기), manifest 권한
```

## 10. 실행 시퀀스 (모드 1 전체)

```
[사전] A: describe → 권한 분류/스냅샷 + 동의 필요 확인
       A: 자기 grant 확인 → 부족분 복구 분기 (5장 표)
[동의] 필요 시 C의 ConsentRequestActivity 실행 (이미 승인이면 즉시 OK)
[실행] A: execute(actionId, args, reverse, mySource, callback)
       B: enforceCallingUid → next==null 확인 → 체인 조립
       B: advisory preflight (grant + AppOps) — 실패 시 PHASE_HUB_PREFLIGHT 거부
       B: C.call(method, actionName, {chain, args})
       C: T(핀/uid/모양) → Layer0 → V(정책) → U(동의) → Layer2 → 실행 → Layer3
[결과] C → B → A 콜백. 실패 시 recoveryAction으로 복구 후 재시도
```
