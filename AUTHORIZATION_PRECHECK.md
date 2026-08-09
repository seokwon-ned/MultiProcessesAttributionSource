# Authorization PreCheck 메커니즘: 권한 사전검사

**목적**: A가 execute() 호출 전에 "지금 실행 가능한가?" 미리 빠르게 확인.

---

## 🤔 왜 authorizationPreCheck이 필요한가?

### 문제: execute() 호출의 비용

```
시나리오: A가 tool을 실행하려고 함

1️⃣ A: execute() 호출
   ↓
2️⃣ B: dispatch() 실행
   ↓
3️⃣ B→C: ContentProvider 호출 (플러그인 wake-up)
   ↓
4️⃣ C: 권한 검사 → ❌ 거부 (AppOps 만료)
   ↓
5️⃣ A: 응답 대기 (100~500ms)
   ↓
6️⃣ A: 오류 번들 받음
   ↓
7️⃣ A: 권한 요청
   ↓
8️⃣ A: 다시 execute() 호출 (왕복 2회)
```

**문제점:**
- execute() 거부가 예상 가능한 사건(AppOps 만료)이었는데도 C를 깨움
- 불필요한 IPC 오버헤드
- A가 대기해야 함 (비동기지만 콜백 처리 필요)

### 해결: authorizationPreCheck으로 사전 검사

```
1️⃣ A: authorizationPreCheck() 호출 (선택)
   ↓
2️⃣ B: 캐시 또는 빠른 체크 (AppOps 확인)
   ↓
3️⃣ B: 즉시 응답 (~1ms, C 안 깸)
   ↓
4️⃣ A: "AppOps 만료" 알아챔
   ↓
5️⃣ A: 권한 요청
   ↓
6️⃣ A: execute() 호출 (성공 가능성 높음)
```

**효과:**
- ✅ C 프로세스를 불필요하게 깨우지 않음
- ✅ B에서 빠른 진단 (캐시 ~1ms)
- ✅ A가 불필요한 execute() 재시도 줄임
- ✅ 사용자 경험 개선 (빠른 피드백)

---

## 📊 authorizationPreCheck() 호출 Flow

### 첫 번째 authorizationPreCheck() 호출 (캐시 미스)

```
┌─────────────────────────────────────────────────────────────┐
│ A: authorizationPreCheck("com.plugin/send_sms")            │
│    (IToolHub.Stub.Proxy → B의 binder로 transact)            │
└─────────────────────────────────────────────────────────────┘
         ↓ (Parcel 마샬링)

┌─────────────────────────────────────────────────────────────┐
│ B: ToolHubService.onTransact(code=4, ...)                   │
│    → authorizationPreCheck("com.plugin/send_sms") 호출       │
└─────────────────────────────────────────────────────────────┘
         ↓

┌─────────────────────────────────────────────────────────────┐
│ B: getValidCachedAuthorizationPreCheck("com.plugin/send_sms")│
│    → null (TTL 만료 또는 처음)                              │
└─────────────────────────────────────────────────────────────┘
         ↓

┌─────────────────────────────────────────────────────────────┐
│ B: splitActionId → ("com.plugin", "send_sms")              │
│    resolveContentProvider("com.plugin") → OK               │
└─────────────────────────────────────────────────────────────┘
         ↓

┌─────────────────────────────────────────────────────────────┐
│ B: private fun checkAuthorizationPreConditions(             │
│     "com.plugin", "send_sms", B의 attributionSource)        │
│                                                              │
│ 체인 구성: [B] (A는 없음 — A의 소스가 없기 때문)             │
└─────────────────────────────────────────────────────────────┘
         ↓

┌─────────────────────────────────────────────────────────────┐
│ B: actionsMetadataFor("com.plugin")                         │
│    ├─ metadataCache 확인                                    │
│    ├─ 미스면 C 호출 (⚠️ C 깨움)                              │
│    └─ 캐시 저장                                              │
│                                                              │
│ 응답 예시:                                                   │
│ {                                                            │
│   "send_sms": {                                             │
│     "permission_entries": [                                 │
│       { "name": "SEND_SMS", "type": "runtime" },           │
│       { "name": "READ_CONTACTS", "type": "runtime" }       │
│     ],                                                       │
│     "consent_required": true                                │
│   }                                                          │
│ }                                                            │
└─────────────────────────────────────────────────────────────┘
         ↓

┌─────────────────────────────────────────────────────────────┐
│ B: 메타데이터의 권한 목록으로 B의 grant 확인                  │
│    for perm in [SEND_SMS, READ_CONTACTS]:                  │
│      pm.checkPermission(perm, B.packageName)               │
│      → SEND_SMS: ✓ (있음)                                   │
│      → READ_CONTACTS: ✓ (있음)                             │
└─────────────────────────────────────────────────────────────┘
         ↓

┌─────────────────────────────────────────────────────────────┐
│ B: B의 AppOps 상태 확인                                      │
│    appOpsBlocked(SEND_SMS, B.uid, B.pkg)                    │
│    → MODE_ALLOWED (통과)                                    │
│    appOpsBlocked(READ_CONTACTS, B.uid, B.pkg)               │
│    → MODE_IGNORED? ❌ (거부)                                 │
│                                                              │
│ 반환: denied("READ_CONTACTS", B.pkg,                        │
│       PHASE_HUB_AUTHORIZATION_PRECHECK,                     │
│       "app-op not allowed (one-time grant expired...)",    │
│       PERMISSION_TYPE_RUNTIME)                             │
└─────────────────────────────────────────────────────────────┘
         ↓

┌─────────────────────────────────────────────────────────────┐
│ B: 결과를 authorizationPreCheckCache에 저장 (10초 TTL)       │
│    authorizationPreCheckCache["com.plugin/send_sms"] =      │
│      (timestamp, denied_bundle)                             │
└─────────────────────────────────────────────────────────────┘
         ↓ (Parcel 언마샬링)

┌─────────────────────────────────────────────────────────────┐
│ A: 응답 받음                                                  │
│ {                                                            │
│   "status": "denied",                                       │
│   "phase": "hub_authorization_precheck",                   │
│   "permission": "READ_CONTACTS",                            │
│   "denied_at": "com.example.toolhub",                       │
│   "perm_type": "runtime"                                    │
│ }                                                            │
└─────────────────────────────────────────────────────────────┘
```

---

### 두 번째 authorizationPreCheck() 호출 (캐시 히트)

```
┌─────────────────────────────────────────────────────────────┐
│ A: authorizationPreCheck("com.plugin/send_sms")            │
│    (A가 다시 호출, 또는 다른 처리 후)                         │
└─────────────────────────────────────────────────────────────┘
         ↓

┌─────────────────────────────────────────────────────────────┐
│ B: getValidCachedAuthorizationPreCheck("com.plugin/send_sms")│
│    → (timestamp, denied_bundle)                             │
│    TTL 확인: 현재시간 - timestamp < 10초 ✓                   │
│    → 즉시 반환 (C 안 깸! ⚡)                                 │
└─────────────────────────────────────────────────────────────┘
         ↓

┌─────────────────────────────────────────────────────────────┐
│ A: 응답 (캐시에서, ~1ms)                                     │
│ {                                                            │
│   "status": "denied",                                       │
│   ...  (동일)                                                │
│ }                                                            │
└─────────────────────────────────────────────────────────────┘
```

**요점: 캐시 히트 시 C를 깨우지 않음 ✅**

---

## 🔄 execute() 호출 시 캐시 재사용

### A가 authorizationPreCheck() 한 뒤 execute() 호출

```
1️⃣ A: authorizationPreCheck() → denied(READ_CONTACTS, PHASE_HUB_AUTHORIZATION_PRECHECK)
   ↓
2️⃣ A: PluginPermissionActivity 실행 → 사용자 승인
   ↓
3️⃣ A: execute("com.plugin/send_sms", args, ...)
   ↓
4️⃣ B: dispatch()
   │
   ├─ cachedAuthCheck = getValidCachedAuthorizationPreCheck("com.plugin/send_sms")
   │  → null (TTL 만료 됨? 또는 상태 변경?)
   │
   └─ checkAuthorizationPreConditions(authority, actionName, entry.chainedSource)
      ├─ 체인 = [B, A] (A의 소스 포함)
      ├─ 검사: A의 READ_CONTACTS 확인 → ✓ (방금 승인)
      └─ 통과 → C 호출
      
5️⃣ C: Layer 0/2/3 최종 검증 → ✓
   ↓
6️⃣ execute() 성공
```

**구조:**
- 3️⃣ execute()의 cachedAuthCheck는 **A 없는 상태 (B만)**를 저장했으므로, A의 권한 변경은 반영되지 않음
- 따라서 execute()에서는 **다시 검사 실행** (하지만 캐시 미사용, 이유: A의 상태 변경)
- C에서 **최종 권한/동의 검증** 후 실행

---

## 📋 authorizationPreCheck()의 역할과 제한

### authorizationPreCheck()가 검사하는 것

| 항목 | 검사 대상 | 방법 |
|------|---------|------|
| **B의 grant** | B의 manifest 권한 | PackageManager.checkPermission() |
| **B의 AppOps** | "이번만 허용" 만료, MODE_IGNORED | AppOpsManager (GET_APP_OPS_STATS) |
| **메타데이터** | 플러그인의 필요 권한 | B의 metadataCache (캐시됨) |

### authorizationPreCheck()가 검사 못하는 것

| 항목 | 이유 |
|------|------|
| **A의 grant** | A의 AttributionSource가 없음 (authorizationPreCheck는 actionId만 받음) |
| **A의 AppOps** | A의 uid를 모름 |
| **C의 권한** | C를 호출하지 않음 (그게 목표) |
| **사용자 동의** | C의 ConsentStore 접근 불가 |

---

## 🎯 A의 사용 패턴

### 패턴 1: authorizationPreCheck 미사용 (간단)

```kotlin
// AgentToolClient.kt
fun executeAction(actionId: String, args: Bundle) {
    val requestId = toolHub.execute(actionId, args, false, ...)
    // → execute() 자체가 모든 검증 수행
}
```

**장점:**
- API 단순 (execute만 호출)
- 코드 간결

**단점:**
- 거부 시 지연 발생 (C 호출 후 실패)
- "이번만 허용" 만료 같은 예측 가능한 실패도 C를 깸

---

### 패턴 2: authorizationPreCheck 사용 (최적화)

```kotlin
// AgentToolClient.kt
fun executeAction(actionId: String, args: Bundle) {
    // 1️⃣ 사전 검사 (선택)
    val checkResult = toolHub.authorizationPreCheck(actionId)
    if (!toolClient.authorizationPreCheckOk(checkResult)) {
        val permType = checkResult.getPermissionType()
        if (permType == PERMISSION_TYPE_RUNTIME) {
            // B에서 감지한 AppOps 또는 A의 권한 부족
            // → A가 권한 요청하거나, 기타 처리
            return handleDenial(checkResult)
        }
    }
    
    // 2️⃣ describe로 권한 정보 확인 (B의 검사와 별개)
    val describe = toolHub.describe(actionId)
    val requiredPerms = describe.getPermissions()
    
    // 3️⃣ A 자신의 권한 확인 (B의 검사는 B만 검사했으므로)
    val missing = requiredPerms.filter {
        checkSelfPermission(it) != PERMISSION_GRANTED
    }
    if (missing.isNotEmpty()) {
        // A의 권한 요청
        requestPermissions(missing)
        return
    }
    
    // 4️⃣ execute 호출 (이제 성공 가능성 높음)
    val requestId = toolHub.execute(actionId, args, false, ...)
}
```

**장점:**
- ✅ B의 검사에서 AppOps 문제 조기 감지
- ✅ 불필요한 C 호출 줄임
- ✅ 캐싱으로 빠른 응답

**단점:**
- ❌ API 호출 증가 (authorizationPreCheck + describe + execute)
- ❌ 코드 복잡도 증가

---

## 🔐 보안: authorizationPreCheck은 "advisory"일 뿐

```
authorizationPreCheck()의 거부 판정:
  → "이 경로로 execute() 거부될 가능성이 높다"
  → 보안 판정이 아님

execute()의 검증 (C의 Layer 0/2/3):
  → 최종 보안 판정
  → authorizationPreCheck 결과를 무시하고 재검증
```

**따라서:**
- authorizationPreCheck이 통과해도 execute()가 거부될 수 있음 (권한 변경 등)
- authorizationPreCheck이 거부해도 execute()가 성공할 수 있음 (매우 드묾, 캐시 오래됨)
- C의 Layer 0/2/3가 **최종 안전망**

---

## 📈 캐싱 메커니즘

### authorizationPreCheck 결과 캐싱 (TTL: 10초)

```kotlin
private val authorizationPreCheckCache = ConcurrentHashMap<String, Pair<Long, Bundle>>()
//                                                               ↑            ↑
//                                                   timestamp    결과 Bundle

private fun getValidCachedAuthorizationPreCheck(actionId: String): Bundle? {
    val (timestamp, result) = authorizationPreCheckCache[actionId] ?: return null
    if (System.currentTimeMillis() - timestamp > AUTHORIZATION_PRECHECK_CACHE_TTL_MS) {
        authorizationPreCheckCache.remove(actionId)
        return null
    }
    return result
}
```

### 캐시 생명주기

```
T0: A: authorizationPreCheck() → B가 검사 → 캐시 저장
    authorizationPreCheckCache["action1"] = (T0, denied_bundle)

T1~T9: A: authorizationPreCheck() 다시 호출 → 캐시 히트 (재사용) ⚡

T10: A: authorizationPreCheck() 호출 → TTL 만료 (제거)
    → 다시 검사 실행

T11: B: execute() 호출 (다른 요청)
    → dispatch()에서 cachedAuthCheck 확인
    → TTL 만료 상태면 캐시 미사용 → 새로 검사 실행
```

### 캐시의 목적

1. **authorizationPreCheck 호출 시**: A의 repeated 호출 최적화
2. **execute 호출 시**: B가 중복 검사 회피 (성능)

---

## 🎯 요약

| 항목 | 설명 |
|------|------|
| **authorizationPreCheck()** | A가 execute() 전에 B의 상태만 빠르게 진단 |
| **대상** | B의 grant + AppOps 확인 (A는 별도) |
| **결과** | null 또는 success = OK / denied Bundle = 거부 |
| **캐싱** | 10초 TTL (repeated 호출 최적화) |
| **SecurityException** | C의 Layer 3이 최종 보안망 |
| **사용** | 선택사항 (없어도 execute()로 작동) |

---

## 📚 참고

- **DESIGN.md**: 전체 T∧P∧U∧V 인가 파이프라인
- **LAYERS.md**: Layer 0/2/3 상세 설명
- **ToolHubService.kt**: 구현 코드 (dispatch, checkAuthorizationPreConditions)
