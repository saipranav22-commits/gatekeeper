# Gatekeeper Phase 7D — Controlled Chaos Engineering Laboratory & Safety Guardrails

## 1. Overview & Blast Radius Philosophy

Chaos Engineering at Gatekeeper is strictly governed by production safety policies. While chaos experiments are essential to validate circuit breaker trip points, bulkhead isolation, fail-closed Redis security, and timeout boundaries, they must **never** be executable in a live production environment.

The **Chaos Laboratory** (`/chaos`) provides a dual-mode interface:
1. **Historical Benchmark Evidence Viewer**: Always available in all environments. Displays empirical proof from the Phase 5D test suite (`phase-5d-results.json`).
2. **Interactive Local Execution Engine**: Active only when running in non-production profiles (`local`, `test`, `dev`) and accessed by an authorized administrator (`ROLE_SUPER_ADMIN` or `ROLE_ADMIN`).

---

## 2. Compile-Time and Runtime Production Guardrails

Gatekeeper enforces multiple defense-in-depth layers to prevent accidental or malicious execution in production:

```
                      ┌────────────────────────────────────────┐
                      │ Inbound Request: POST /api/v1/chaos/*  │
                      └───────────────────┬────────────────────┘
                                          │
                                          ▼
                      ┌────────────────────────────────────────┐
                      │ Layer 1: Spring @Profile Check         │
                      │ Does active profile match:             │
                      │ local | test | dev ?                   │
                      └───────────────────┬────────────────────┘
                                          │
                        No                │ Yes
           ┌──────────────────────────────┴──────────────────────────┐
           │                                                         │
           ▼                                                         ▼
┌──────────────────────────────┐              ┌────────────────────────────────────────┐
│ HTTP 404 NOT FOUND           │              │ Layer 2: ChaosSafetyGuard Filter       │
│ Controller bean is not even  │              │ - Target IP must be local/loopback     │
│ loaded in ApplicationContext │              │ - Port restricted to 8080/8081/8082    │
└──────────────────────────────┘              │ - Max concurrency ≤ 50                 │
                                              │ - Max requests ≤ 500                   │
                                              │ - Timeout ≤ 60 seconds                 │
                                              └───────────────────┬────────────────────┘
                                                                  │
                                                                  ▼
                                              ┌────────────────────────────────────────┐
                                              │ Layer 3: Automatic Teardown in finally │
                                              │ Resets mock stubs & restores state     │
                                              └────────────────────────────────────────┘
```

1. **Spring Profile Lockout (`@Profile({"local", "test", "dev"})`)**:
   - `ChaosAdminController` is annotated with `@Profile({"local", "test", "dev"})`.
   - In production (`application-prod.yml`), the controller bean is omitted entirely from the Spring `ApplicationContext`. Any attempt to access `/api/v1/chaos/*` returns `HTTP 404 Not Found`.
2. **`ChaosSafetyGuard` Validation**:
   - Rejects non-loopback or unauthorized upstream hosts.
   - Enforces a hard ceiling of 500 requests per experiment run.
   - Restricts concurrency to a maximum of 50 threads.
   - Mandates a strict 60-second execution timeout.
3. **Automatic Teardown in `finally` Blocks**:
   - Injected faults (such as latency or upstream error stubs) are registered with explicit cleanup handlers that execute in `finally` blocks, ensuring no fault persists after an experiment completes or aborts.

---

## 3. The Six Core Chaos Scenarios

The Chaos Laboratory implements six canonical distributed-systems failure scenarios:

### Scenario A: Orders Service Outage (`ORDERS_OUTAGE`)
- **Fault Type**: HTTP 503 Service Unavailable injected into `orders-service-v1` (Port 8081).
- **Resilience Mechanism**: Resilience4j `RouteCircuitBreaker` (Sliding Window: 20 calls, Threshold: 50%).
- **Empirical Assertions**:
  1. `CircuitBreakerTrip`: Breaker transitions from `CLOSED` to `OPEN` after 5 consecutive failures.
  2. `FastRejection`: Subsequent requests are rejected locally in < 25 ms with HTTP 503 (`CIRCUIT_BREAKER_OPEN`).
  3. `CrossRouteIsolation`: Sibling route `inventory-service-v1` remains 100% available with 0% error rate.
  4. `AutomaticRecovery`: After wait duration, breaker transitions through `HALF_OPEN` and resets to `CLOSED`.

### Scenario B: Slow Upstream & Timeout Cascade (`SLOW_UPSTREAM`)
- **Fault Type**: Latency injection (3500 ms delay) into `orders-service-v1`.
- **Resilience Mechanism**: Gateway Timeout & Slow Call Rate Accounting (Threshold: 50%, Duration: 2000 ms).
- **Empirical Assertions**:
  1. `DeadlineEnforcement`: Calls exceeding 5000 ms cumulative deadline terminate with HTTP 504.
  2. `SlowCallRateAccounting`: Slow calls are recorded in sliding window.
  3. `ZeroResourceLeak`: Timed-out requests release semaphore permits in `finally` block.

### Scenario C: Bulkhead Saturation (`BULKHEAD_SATURATION`)
- **Fault Type**: Concurrency burst of 19 concurrent requests against route with 10 semaphore permits.
- **Resilience Mechanism**: RouteBulkhead (Max Concurrent: 10, Semaphore acquisition: 0 ms wait).
- **Empirical Assertions**:
  1. `BulkheadPermitCeiling`: First 10 requests acquire permits; excess 9 are rejected with `BULKHEAD_REJECTED`.
  2. `SiblingRouteAvailability`: Sibling routes continue serving concurrent traffic unhindered.
  3. `PermitRecovery`: In-flight permits return to baseline (0 in-flight, 10 available) immediately upon completion.

### Scenario D: Redis Dependency Failure (`REDIS_FAILURE`)
- **Fault Type**: Interruption of Redis connection on port 6379.
- **Resilience Mechanism**: Fail-Closed Rate Limiter & Token Verification.
- **Empirical Assertions**:
  1. `RedisFailClosed`: Rate limiting and token revocation fail securely, blocking unverified requests with HTTP 503 instead of allowing unmetered access.
  2. `NoCredentialLeak`: Error payloads omit internal Redis connection strings or topology details.
  3. `AutomaticReconnection`: Gateway re-establishes connection once Redis resumes.

### Scenario E: Policy Rule Safety & Shadow Evaluation (`POLICY_SAFETY`)
- **Fault Type**: High-priority DENY rule evaluation under heavy load.
- **Resilience Mechanism**: Policy Rule Engine with Atomic Snapshot Swapping and Shadow Evaluation.
- **Empirical Assertions**:
  1. `PolicyAtomicSwap`: Zero in-flight request corruption during policy activation.
  2. `ShadowExecutionSafety`: Shadow policies record evaluation traces without altering live traffic decisions.
  3. `RollbackSafety`: Rollback to previous version executes in < 5 ms.

### Scenario F: Retry Storm Suppression (`RETRY_STORM`)
- **Fault Type**: Upstream intermittent 502/503 errors on non-idempotent endpoints.
- **Resilience Mechanism**: Retry Budget, Idempotency Guard, and Exponential Backoff with Jitter.
- **Empirical Assertions**:
  1. `NonIdempotentGuard`: Retries on `POST` requests without idempotency keys are strictly suppressed (0 retries).
  2. `RetryCeiling`: Idempotent requests do not exceed 3 total attempts.
  3. `JitterSpread`: Exponential backoff intervals exhibit pseudo-random variance preventing thundering herds.

---

## 4. Provenance & Benchmark Evidence Verification

Every experiment displayed in the Chaos Lab carries a verified **Provenance Tag**:
- **`HISTORICAL TEST ARTIFACT`**: Loaded from the empirical Phase 5D benchmark verification suite (`docs/chaos/results/phase-5d-results.json`). Contains recorded assertion outcomes, observed millisecond latencies, and circuit-breaker state transitions.
- **`LIVE LOCAL EXECUTION`**: Returned dynamically from a local Spring Boot gateway instance running in `local`, `test`, or `dev` profiles.
