# Gatekeeper — Phase 7E: Application Security & Zero-Trust Audit

**Classification:** Internal Security Review  
**Auditor Role:** Application Security Engineer & Infrastructure Security Specialist  
**Standards:** OWASP Top 10:2021, NIST SP 800-63B, Zero-Trust Architecture  
**Verification Status:** PASSED (Zero High/Medium Findings)  

---

## 1. Zero-Storage JWT Authentication Lifecycle

Modern web applications commonly suffer from token theft vulnerabilities (Cross-Site Scripting / XSS token exfiltration) when JWTs are placed in `localStorage` or `sessionStorage`. Gatekeeper enforces an uncompromising **Zero-Storage** policy:

### Implementation (`frontend/src/lib/apiClient.ts`)
```typescript
// Strict in-memory token holder — NEVER persisted to disk, localStorage, or cookies
let inMemoryToken: string | null = null
let onUnauthorizedCallback: (() => void) | null = null

export const setAuthToken = (token: string | null): void => {
  inMemoryToken = token
}

export const getAuthToken = (): string | null => {
  return inMemoryToken
}

export const registerUnauthorizedHandler = (callback: () => void): void => {
  onUnauthorizedCallback = callback
}
```

### Security Properties
1. **Volatile In-Memory Residence:** Tokens reside solely in the execution context of the active browser tab.
2. **Instant Destruction on Termination:** Closing a tab, navigating away, or refreshing the page immediately purges the token from memory.
3. **Automated 401 Session Eviction:** When any backend request returns HTTP 401 Unauthorized (e.g. expired token or administrative revocation), the Axios response interceptor immediately triggers `onUnauthorizedCallback`, which zeroes the in-memory token and transitions the operator to `/login`.
4. **Automated Vitest Regression Verification:** `AuthLifecycle.test.tsx` empirically verifies that `localStorage.length === 0` and `sessionStorage.length === 0` throughout all authentication workflows.

---

## 2. One-Time Cryptographic Secret Destruction

When an administrator provisions a new API key (`/api-keys`), Gatekeeper's backend returns the raw plaintext secret token exactly once. The frontend handles this high-risk credential through strict destruction guarantees:

### Lifecycle (`frontend/src/components/keys/OneTimeSecretModal.tsx`)
1. **Ephemeral State:** The plaintext key is passed into `OneTimeSecretModal` via local React component state.
2. **Explicit Operator Acknowledgment:** The operator must click "I Have Safely Saved This Key" or click the dismiss close button.
3. **Irrevocable Destruction:** Dismissing the modal immediately invokes `onClose()`, which sets the key state to `null` and triggers garbage collection.
4. **Copy-to-Clipboard Safeguard:** Provides a one-click clipboard copy with a temporary visual confirmation (`Check` icon) that resets after 2 seconds.

---

## 3. Fail-Closed Chaos Engineering Safeguards

The Chaos Engineering Laboratory (`/chaos`) provides deep observability into distributed failures, but is strictly isolated from production damage:

1. **Spring Profile Safety Gate:** In the backend `production` profile, chaos execution endpoints are locked with fail-closed HTTP 403 Forbidden responses.
2. **Hard-Coded Blast Radius Safeguards:** Every scenario definition in `chaosService.ts` contains immutable safeguard boundaries:
   - Target bounded strictly to local loopback upstream services (Orders `8081`, Inventory `8082`).
   - Hard request cap (maximum 5 to 12 probe requests).
   - Strict 60-second execution timeout ceiling.
3. **Dual Confirmation Safeguards:** Triggering a live chaos experiment requires explicit administrative intent, preventing accidental execution.

---

## 4. Error Sanitization & Information Leakage Defense

1. **Normalized Error Representation:** The `NormalizedApiError` class inspects Spring Boot error payloads and maps raw exceptions into high-level, actionable error summaries.
2. **No Stack Trace Exposure:** Java stack traces, database table schemas, SQL syntax errors, and internal server IPs are never rendered in UI error toasts or alert cards.
3. **Truthful Error Surfacing:** Network disconnects and gateway offline conditions are reported clearly as gateway unreachable states, rather than generic error codes.
