# Android Attribution System: Layer 0, 2, 3 설명

Android 권한 시스템의 세 가지 검사 계층을 정리한 문서입니다.

---

## 🎯 개요

**"왜 세 가지 계층이 필요한가?"**

```
한 번의 검사로는 충분하지 않다.
체인의 진정성 → 링크의 권한 → 실행 시점의 변화
이 세 가지를 모두 확인해야 안전하다.
```

---

## 📊 시각적 흐름

```
A → B → C로 호출할 때
┌──────────────────────────────────────┐
│ Layer 0: isTrusted()                 │
│ "체인이 시스템에 등록된 진짜 체인?"    │
│ ❌ false → 거부 (코드 버그/공격)      │
└──────────────────────────────────────┘
         ↓ (통과)
┌──────────────────────────────────────┐
│ Layer 2: checkPermission()           │
│ "A, B, C 모두 필요 권한 보유?"        │
│ ❌ 하나라도 없음 → 거부               │
└──────────────────────────────────────┘
         ↓ (통과)
┌──────────────────────────────────────┐
│ 실제 시스템 API 호출                  │
│ attributionContext 사용               │
└──────────────────────────────────────┘
         ↓
┌──────────────────────────────────────┐
│ Layer 3: SecurityException           │
│ "OS가 실행 시점에 재검사"             │
│ ❌ AppOps/runtime 상태 변경 → 거부   │
└──────────────────────────────────────┘
         ↓ (통과)
┌──────────────────────────────────────┐
│ ✅ 실행 완료                          │
└──────────────────────────────────────┘
```

---

## 1️⃣ Layer 0: isTrusted() — 체인 진정성 검증

### 목적
**체인 자체가 시스템에 등록되었는가?**

```java
if (!chainedSource.isTrusted(context)) {
    return ResultContract.untrustedChain("Chain not registered");
}
```

### 확인 항목
- ✓ AttributionSource가 시스템에 등록되었는가?
- ✓ 토큰이 유효한가?
- ✓ A-B-C 체인이 시스템이 알고 있는 체인과 일치하는가?

### 실패 시나리오

**시나리오 1: 코드 버그**
```java
// B가 체인을 잘못 만듦
AttributionSource source = new AttributionSource.Builder()
    .setPackageName("com.example.agent")
    .build();  // ← 토큰이 없음, 시스템에 미등록

// C의 isTrusted() 검사에서 거부됨 ❌
```

**시나리오 2: 공격 시도**
```java
// 공격자가 위조된 체인 구성
AttributionSource fakeChain = new AttributionSource.Builder()
    .setPackageName("com.example.agent")
    .setNext(...)
    .build();  // ← 가짜 토큰

// isTrusted() 검사에서 거부됨 ❌
```

### 복구 불가능
- Layer 0 실패는 **코드 버그 또는 공격**을 의미
- 사용자가 할 수 있는 것이 없음
- 애플리케이션 개발자가 수정해야 함

---

## 2️⃣ Layer 2: checkPermission() — 링크별 권한 검증

### 목적
**체인의 모든 링크가 필요한 권한을 보유했는가?**

```java
PackageManager pm = context.getPackageManager();
for (String permission : requiredPermissions) {
    for (AttributionSource link : chain) {
        int result = pm.checkPermission(permission, link.packageName);
        if (result != PERMISSION_GRANTED) {
            return ResultContract.denied(permission, link.packageName, ...);
        }
    }
}
```

### 체인 구조 예시

```
요청: "send_sms" (SEND_SMS 권한 필요)

체인: A → B → C
      ↓    ↓    ↓
     ✓    ✓    ❌ SEND_SMS 없음!

→ Layer 2 거부
```

### 확인 항목
- ✓ A(에이전트)가 SEND_SMS 보유?
- ✓ B(허브)가 SEND_SMS 보유?
- ✓ C(플러그인)가 SEND_SMS 보유?

### 권한 분류

```
Runtime Permissions (동적 - 사용자 승인 필요):
  - android.permission.READ_CONTACTS
  - android.permission.SEND_SMS
  - etc.

Install-time Permissions (정적 - manifest):
  - android.permission.INTERNET
  - android.permission.CHANGE_NETWORK_STATE
  - etc.

checkPermission()은 grant 상태만 확인:
  PERMISSION_GRANTED (1) → 승인됨
  PERMISSION_DENIED (0)  → 거부됨
```

### 실패 시나리오

**시나리오 1: A에 권한 없음**
```
A(에이전트): SEND_SMS ❌
B(허브): SEND_SMS ✓
C(플러그인): SEND_SMS ✓

→ Layer 2 거부
→ A의 권한 설정에서 [허용] 필요
```

**시나리오 2: B에 권한 없음**
```
A: SEND_SMS ✓
B: SEND_SMS ❌ (프로세스 B가 권한을 가져야 함)
C: SEND_SMS ✓

→ Layer 2 거부
→ 배포 구성 오류 (B의 manifest에 권한 선언 필요)
```

**시나리오 3: C에 권한 없음**
```
A: SEND_SMS ✓
B: SEND_SMS ✓
C: SEND_SMS ❌

→ Layer 2 거부
→ 복구: C의 PluginPermissionActivity 실행해서 grant 획득
```

---

## 3️⃣ Layer 3: SecurityException — 실행 중 OS 강제

### 목적
**실행 시점에 권한 상태가 변경되었는가?**

```java
try {
    // attributionContext로 시스템 API 호출
    Cursor cursor = attributionContext.getContentResolver()
        .query(ContactsContract.Contacts.CONTENT_URI, ...);
    // ← OS가 여기서 재검사
} catch (SecurityException e) {
    // Layer 0, 2를 통과했어도 여기서 거부 가능
    return ResultContract.denied(null, packageName, PHASE_RUNTIME, e.getMessage());
}
```

### Layer 0/2와의 차이

| | Layer 0/2 | Layer 3 |
|---|----------|---------|
| **검사 시점** | dispatch() 호출 전 | 시스템 API 호출 시 |
| **대상** | 캐시된 권한 상태 | 현재 실시간 상태 |
| **감지 대상** | 없는 권한 | **변경된** 권한 |

### 실패 시나리오

**시나리오 1: "이번만 허용" 만료**
```
타임라인:
T1: dispatch() 호출 → checkPermission() ✓
    (SEND_SMS 허용 상태)

T2: [이번만 허용] 타임아웃
    (사용자가 다른 앱을 실행하거나 시간 경과)
    → 권한이 자동으로 취소됨

T3: 실제 SMS API 호출
    → Layer 3에서 SecurityException ❌
```

**시나리오 2: AppOps MODE_IGNORED**
```
사용자가 설정에서:
"앱이 연락처 접근을 시도하면 항상 거부"
→ AppOps MODE_IGNORED 설정

그러면:
- checkPermission() → PERMISSION_GRANTED (실제 grant 상태)
- 실제 API 호출 → SecurityException (AppOps 규칙)
```

**시나리오 3: 런타임 권한 철회**
```
T1: dispatch() 호출 → checkPermission() ✓

T2: 사용자가 설정에서 권한 [거부]

T3: 실제 API 호출
    → Layer 3에서 SecurityException ❌
```

### 복구 방식

```
Layer 3 실패 → RecoveryAction 결정
  ├─ RECOVERY_REQUEST_SELF_PERMISSION
  │  (A가 자신의 권한 UI로 다시 허용 받음)
  │
  └─ RECOVERY_REQUEST_PLUGIN_PERMISSION
     (C의 PluginPermissionActivity 실행)
```

---

## 🔄 전체 흐름: 우리의 T∧P∧U∧V와의 관계

```
PluginContentProvider.dispatch()
  │
  ├─ T층 (CallerVerifier)
  │  └─ 호출자가 정말 B인가?
  │
  ├─ P층 (ChainPermissionChecker)
  │  ├─ Layer 0: chainedSource.isTrusted(context)
  │  └─ Layer 2: pm.checkPermission(perm, packageName)
  │
  ├─ V층 (PluginPolicy)
  │  └─ 벤더 정책 (allowlist 등)
  │
  ├─ U층 (ConsentStore)
  │  └─ 사용자 동의 기록 확인
  │
  └─ 실행
     │
     └─ try { handler.onPerformAction(...) }
        catch (SecurityException) { → Layer 3 거부 }
```

---

## 📋 Layer 0, 2, 3 비교표

| 항목 | Layer 0 | Layer 2 | Layer 3 |
|------|---------|---------|---------|
| **검사 대상** | 체인 진정성 | 링크별 권한 grant | 실시간 권한 상태 |
| **검사 방법** | `isTrusted()` | `checkPermission()` | 시스템 API 호출 |
| **실패 원인** | 코드 버그/공격 | 배포 설정/미grant | AppOps/timeout 만료 |
| **복구 가능** | ❌ 아니오 | ✓ 예 | ✓ 예 |
| **복구 담당** | 개발자 | 사용자/배포 | 사용자/B |
| **검사 시점** | dispatch() 전 | dispatch() 전 | API 호출 시 |

---

## 🎯 핵심 요점

1. **Layer 0**: "체인이 진짜인가?" → 코드 검증 계층
2. **Layer 2**: "링크들이 권한을 가졌는가?" → 배포 검증 계층
3. **Layer 3**: "실행 시점에 뭐 바뀐 건 없나?" → 런타임 보호 계층

**모두 통과해야만 실행 가능** (Layer 0 AND Layer 2 AND ... AND Layer 3)

---

## 📚 참고

- **Android 공식 문서**: Attribution Source 및 Data Delivery
- **우리 설계**: P층 = Layer 0 + Layer 2 구현
- **B의 역할**: preflight로 Layer 2 사전진단 (advisory, C가 재검증)
- **C의 역할**: Layer 0, 2, 3 전부 재검증 후 실행
