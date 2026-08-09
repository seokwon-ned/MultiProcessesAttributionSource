# ToolHub 설계 FAQ

사용자가 질문한 핵심 개념들을 정리한 문서입니다.

---

## 1. SHA-256을 ConsentStore에서 사용하는 이유

### 문제: 패키지명만으로는 충분하지 않다

```
공격 시나리오:
1. 정상 앱 "com.example.agent" 삭제
2. 공격자가 같은 패키지명으로 악성 앱 배포
3. 기존 승인 레코드가 그대로 악성 앱에 적용됨 ❌
```

### 해결책: 서명 해시를 키에 포함

ConsentStore의 키 구성:
```
agentPackage + "|" + SHA256(APK서명) + "|" + actionName
```

**예시:**
```
승인 레코드:
  "com.example.agent|a3b4c5d6e7f8...|read_contacts" → true

공격자의 악성 앱 (다른 서명):
  조회 키: "com.example.agent|sha256(악성앱)|read_contacts"
  → 다른 키 → 레코드 찾지 못함 ✓
```

### 왜 SHA-256인가?

| 해시 알고리즘 | 상태 | 사용 |
|-----------|------|------|
| MD5 | ❌ 충돌 취약 (2004년 이후) | X |
| SHA-1 | ⚠️ 약화됨 (2017년 권장 폐지) | X |
| **SHA-256** | ✅ 현재 안전 (NIST 표준) | **✓** |

---

## 2. ConsentStore의 역할과 필요성

### ConsentStore가 관리하는 것

**사용자가 (앱, 기능) 조합에 대해 승인한 기록**

```
저장 형식 (SharedPreferences):
com.example.agent|SHA256(sig)|read_contacts    → true
com.example.agent|SHA256(sig)|send_sms         → true
com.example.maps|SHA256(sig2)|send_sms         → (없음 = 미승인)
```

### 메서드

```java
// 조회
boolean isGranted(context, "com.example.agent", "send_sms")

// 기록 (ConsentRequestActivity에서만)
void grant(context, "com.example.agent", "send_sms")

// 철회
void revoke(context, "com.example.agent", "send_sms")
```

### ConsentStore가 필요한 이유: A는 신뢰할 수 없다

**신뢰도 계층:**
```
Process B (허브)    ← 플랫폼 앱 (priv-app) → 신뢰 ✅
Process C (플러그인) ← 플랫폼 앱 (벤더)    → 신뢰 ✅
Process A (에이전트) ← 써드파티 앱 (누구나 제작) → 신뢰 ❌
```

**ConsentStore 없을 때의 공격:**

```java
// 악의적 A가:
args.putBoolean("user_consent_ack", true);
client.execute("send_sms", args);

// C가:
if (args.getBoolean("user_consent_ack")) {
    // 실행! 사용자는 승인 안 했는데? 😱
}
```

**ConsentStore 있을 때:**

```java
// C가:
if (ConsentStore.isGranted(context, "com.example.agent", "send_sms")) {
    // C 소유 저장소 확인 (A의 주장 무시)
    // 레코드 없음 → false → 거부 ✅
}
```

### 왜 C 소유 저장소인가?

```
A의 주장:      args.putBoolean("ack", true) → 위조 가능 ❌
ConsentStore:  C의 SharedPreferences
               → A가 다른 프로세스의 저장소 접근 불가 ✅
```

---

## 3. PluginPermissionActivity vs ConsentRequestActivity

### 둘 다 필요한 이유

| | **PluginPermissionActivity** | **ConsentRequestActivity** |
|---|---|---|
| **목적** | C의 **런타임 권한** 획득 | **사용자 동의** (기능별) 승인 |
| **언제 띄움** | C가 권한 없을 때 | 사용자 동의 레코드 없을 때 |
| **요청자** | C (권한 부족) | A (동의 레코드 부족) |
| **시스템 UI** | ✅ `requestPermissions()` → OS 다이얼로그 | ❌ 커스텀 `AlertDialog` |
| **저장소** | OS 권한 DB | C의 ConsentStore |
| **실행 위치** | C 프로세스 | C 프로세스 |
| **복구 분기** | `RECOVERY_REQUEST_PLUGIN_PERMISSION` | `RECOVERY_REQUEST_CONSENT` |

### 전체 흐름

```
A: execute("send_sms", args)
  ↓
B: 체인 조립 → C.call()
  ↓
C: dispatch()
  
  ├─ Layer P (권한)
  │  └─ C에 SEND_SMS? ❌ 
  │     → RECOVERY_REQUEST_PLUGIN_PERMISSION
  │     → A가 PluginPermissionActivity 띄움
  │     → C가 requestPermissions() (시스템 다이얼로그)
  │
  ├─ Layer U (동의)
  │  └─ ConsentStore.isGranted()? ❌
  │     → RECOVERY_REQUEST_CONSENT
  │     → A가 ConsentRequestActivity 띄움
  │     → 사용자가 [승인] 누름
  │     → ConsentStore.grant() 저장
  │
  └─ 재시도 → 실행
```

---

## 4. ActionMetadata의 용도

### 구조

```java
public final class ActionMetadata {
    public final String[] permissions;           // 필요한 권한들
    public final boolean consentRequired;        // 동의 필요?
    public final String[] consentCategories;     // 동의 분류
}
```

### 생성 과정

```
1. 앱 시작 → 첫 ContentProvider.call
   ↓
2. PermissionScanner.scan() 호출 (한 번만)
   ↓
3. 리플렉션: @HandlerPermission, @RequiresUserConsent 읽음
   ↓
4. 메모리 캐시 생성:
   {
     "read_contacts": ActionMetadata([READ_CONTACTS], false, []),
     "send_sms":      ActionMetadata([SEND_SMS], true, [SEND_EXTERNAL, COSTS_MONEY])
   }
```

### 사용처

**1️⃣ P층 (권한 검사)**
```java
ActionMetadata meta = metadata().get(actionName);
String[] required = meta.permissions;

ChainPermissionChecker.check(context, chainedSource, required);
// ← 필요 권한 목록으로 Layer 2 검사
```

**2️⃣ U층 (동의 검사)**
```java
if (meta != null && meta.consentRequired
    && !ConsentStore.isGranted(context, originator, actionName)) {
    return ResultContract.consentRequired(actionName, meta.consentCategories);
}
```

**3️⃣ describe 응답**
```java
// A에게 메타데이터 노출
{
  "send_sms": {
    "permission_entries": [...],
    "consent_required": true,
    "consent_categories": ["send_external", "costs_money"]
  }
}
```

### 이점

```
✅ 선언 기반 (유연함)
✅ 한 번만 스캔 후 캐싱 (빠름)
✅ 액션 추가해도 C 코드 수정 불필요
✅ 애노테이션으로 명확한 의도 표현
```

---

## 5. T∧P∧U∧V 파이프라인

### 의미

**∧ = "AND" (논리곱)**

```
T ∧ P ∧ U ∧ V = 모든 층을 통과해야만 실행 가능
```

**하나라도 거부되면 전체 거부:**

```java
// T층: 전송 경로 검증
if (T_거부) return 거부;

// P층: 권한 검사
if (P_거부) return 거부;

// V층: 벤더 정책
if (V_거부) return 거부;

// U층: 사용자 인가 (동의)
if (U_거부) return 거부;

// 모두 통과! 실행
return 실행();
```

### 계층별 역할

| 층 | 이름 | 검사 항목 | 구현 위치 |
|----|------|---------|---------|
| **T** | 전송 경로 검증 | 호출자가 정말 허브인가? 체인 모양? | CallerVerifier |
| **P** | 권한 | A/B/C 모두 필요 권한 보유? | ChainPermissionChecker |
| **U** | 사용자 인가 | ConsentStore에 기록 있는가? | ConsentStore |
| **V** | 벤더 정책 | allowlist, rate limit 등 | PluginPolicy |

### 실제 거부 예시

```
T 거부: 다른 priv-app이 C의 provider 직접 호출
P 거부: A가 SEND_SMS 권한 없음
U 거부: 사용자가 "send_sms" 동의 한 번도 안 함
V 거부: 벤더 정책상 "이 에이전트는 차단됨"

모두 통과해야만 ✅ 실행 가능
```

### 왜 AND인가? (OR이 아닌 이유)

```
T OR P OR U? (하나만 통과하면 OK)
  → 너무 느슨함 ❌
  → 권한은 OK인데 정책상 차단? 실행되면 안 되는데?

T AND P AND U AND V? (모두 통과)
  → 보안 강함 ✅
  → 모든 조건을 만족해야만 실행
  → "방어 깊이" (Defense in Depth)
```

---

## 6. 패키지 구조 정리

### 최종 SDK 구조

```
sdk/src/main/java/com/example/toolhub/plugin/
├── annotation/           ← 애노테이션
│   ├── HandlerPermission.java
│   ├── RequiresUserConsent.java
│   └── ConsentCategory.java
│
├── permission/           ← 인가 관련
│   ├── CallerVerifier.java (T층)
│   ├── ChainPermissionChecker.java (P층)
│   ├── ConsentStore.java (U층)
│   ├── ConsentRequestActivity.java (U층 UI)
│   └── PluginPermissionActivity.java (C 권한 UI)
│
└── plugin/              ← 기본 계약
    ├── ToolHandler.java
    ├── ParameterValues.java
    ├── ActionMetadata.java
    ├── PluginPolicy.java (V층)
    ├── PermissionScanner.java
    └── PluginContentProvider.java (T∧P∧U∧V 통합)
```

---

## 7. 보안 계층 요약

### 신뢰도

```
Level 3 (최고):  OS/플랫폼 (permission grant)
Level 2:        B(허브) + C(플러그인) (플랫폼 앱)
Level 1:        A(에이전트) (써드파티 앱) ← ConsentStore로 검증!
```

### 방어 메커니즘

| 위협 | 방어 방법 | 층 |
|-----|---------|------|
| 다른 앱이 B 사칭 | 허브 uid 핀 고정 | T |
| 위조 AttributionSource | isTrusted() + uid 검증 | T, P |
| 패키지명 재사용 공격 | SHA256 서명 해시 | U |
| A의 ack 위조 | ConsentStore (C 소유) | U |
| 특정 에이전트 차단 | PluginPolicy allowlist | V |
| "이번만 허용" 만료 | B의 AppOps preflight | P |

---

## 요점 정리

1. **SHA-256**: 서명 기반 신원 확인 (패키지명 재사용 공격 방지)
2. **ConsentStore**: A(써드파티)를 신뢰하지 않기 위한 C 소유 저장소
3. **PluginPermissionActivity**: C의 런타임 권한 획득 (A가 대신 요청)
4. **ConsentRequestActivity**: 사용자 동의 기록 (C가 소유)
5. **ActionMetadata**: 액션별 메타정보 (권한, 동의) 캐싱
6. **T∧P∧U∧V**: 모든 층을 동시에 통과해야 실행 (방어 깊이)
