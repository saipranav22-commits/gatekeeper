# GATEKEEPER — PHASE 5D CONTROLLED CHAOS & RELIABILITY REPORT

**Generated At:** 2026-11-19T01:15:00.000Z  
**Target Environment:** Local Controlled Chaos Laboratory (Isolated Loopback)  
**Stack:** Java 21, Spring Boot 3.3.5, PostgreSQL 16 (Port 5434), Redis 7.4 (Port 6380)  
**Safety Controls:** Allowlisted Targets Only, Bounded Concurrency & Volume, Zero Host Mutation  

> [!IMPORTANT]
> **Evidence Provenance & Execution Classification**:
> - **Execution Mode:** Scenarios A through F below reflect **`LIVE_LOCAL_EXPERIMENT`** runs executed against live Gatekeeper backend (port 8080), Orders upstream (port 8081), Inventory upstream (port 8082), Redis (port 6380), and PostgreSQL (port 5434).
> - **Prior Fixtures:** Offline test harness fixtures previously recorded in unit tests (`GeneratePhase5DReportsTest`) are strictly separated as **`SYNTHETIC_FIXTURE`**.
> - **Live Experiment IDs:** `f569ed16`, `73b796c7`, `98ee175f`, `070f635e`, `33a63dd0`, `955160fe`.
> - **Recovery Time Precision:** Scenarios A (26 ms), B (18 ms), and F (13 ms) represent empirically measured probe cycles. Scenarios C, D, and E report `< 1 ms` (instantaneous programmatic release of local semaphore permits, rollback locks, or adapter reconnects within sub-millisecond clock resolution; not a network recovery loop).

---

## 1. Executive Summary

The Gatekeeper Phase 5D Chaos Engineering Laboratory validates distributed system resilience, concurrency boundaries, failure isolation, and automatic self-healing under controlled live faults. Experiments were executed through non-destructive local harnesses with strictly bounded blast radius and verified cleanup.

| Experiment ID | Scenario | Target Service | State | Assertions (Pass/Total) | Requests (Succ/Fail) | P95 Latency | Recovery Window |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `f569ed16` | Scenario A — Orders Service Outage | `orders-service-v1` | **COMPLETED** | 4/4 | 12 (6/6) | 320.00 ms | 26 ms (Probe verified) |
| `73b796c7` | Scenario B — Slow Upstream & Timeout Cascade | `orders-service-v1` | **COMPLETED** | 4/4 | 5 (4/1) | 94.00 ms | 18 ms (Probe verified) |
| `98ee175f` | Scenario C — Bulkhead Saturation | `orders-service-v1` | **COMPLETED** | 4/4 | 19 (14/5) | 1500.00 ms | < 1 ms (Local permits released) |
| `070f635e` | Scenario D — Redis Dependency Failure | `redis-token-bucket` | **COMPLETED** | 3/3 | 3 (2/1) | 2.00 ms | < 1 ms (Adapter reset) |
| `33a63dd0` | Scenario E — Policy Safety Under Failure | `policy-engine` | **COMPLETED** | 5/5 | 1 (0/1) | 1.00 ms | < 1 ms (Atomic rollback) |
| `955160fe` | Scenario F — Retry Storm Prevention | `orders-service-v1` | **COMPLETED** | 4/4 | 3 (2/1) | 45.00 ms | 13 ms (Probe verified) |

---

### Scenario A — Orders Service Outage (ID: `f569ed16`)

#### 1. Experiment Objective
Simulates sustained upstream HTTP 503 outage, circuit breaker trip, fast rejection, and automatic recovery.

#### 2. System Configuration & Target
- **Target Service:** `orders-service-v1` (http://localhost:8081)
- **Injected Fault Type:** `HTTP_ERROR`
- **State:** `COMPLETED`
- **Provenance:** `LIVE_LOCAL_EXPERIMENT`

#### 3. Preconditions & Health
- Upstream Orders (8081), Inventory (8082), and Gateway (8080) verified healthy prior to fault injection.

#### 4. Failure Injection Method
- Controlled in-memory fault simulation applied via Orders `/fault/configure` endpoint returning HTTP 503 without terminating OS processes.

#### 5. Expected Reliability Properties
- **CircuitBreakerTrip:** Circuit breaker transitions from CLOSED to OPEN under sustained upstream 503 errors.
- **FastRejection:** Open-circuit requests are fast-rejected locally without upstream socket dispatch (< 25 ms).
- **CrossRouteIsolation:** Orders outage does not degrade sibling Inventory route availability or open its breaker.
- **AutomaticRecovery:** Circuit breaker transitions OPEN -> HALF_OPEN -> CLOSED automatically when upstream health recovers.

#### 6. Actual Observations & Runtime Telemetry
- **Total Requests:** 12
- **Successful / Failed:** 6 / 6 (5 baseline success + 6 fault 503s + 1 sibling Inventory success)
- **Status Codes:** `{200=6, 503=6}`
- **Circuit Breaker Events:** `[INITIAL_STATE: CLOSED, STATE_TRANSITION: CLOSED -> OPEN, STATE_TRANSITION: OPEN -> HALF_OPEN (after wait window), STATE_TRANSITION: HALF_OPEN -> CLOSED]`
- **Bulkhead Rejections:** 0
- **Retry Attempts:** 0
- **Peak Concurrency:** 1 request
- **Sibling Healthy Service Availability:** 100.0% (Inventory responded with HTTP 200 during Orders outage)

#### 7. Latency Measurements
| Metric | Gateway Client Latency | Upstream Dispatch Latency |
| :--- | :--- | :--- |
| **P50** | 18.00 ms | 18.00 ms |
| **P95** | 320.00 ms | 320.00 ms |
| **P99** | 320.00 ms | 320.00 ms |

#### 8. Recovery Timeline
- **Recovery Duration:** 26 ms (empirically measured during healthy probe polling)
- **Final Circuit Breaker State:** CLOSED (Healthy)

#### 9. Assertion Outcomes
| Assertion | Expected Property | Outcome | Observed Evidence |
| :--- | :--- | :--- | :--- |
| `CircuitBreakerTrip` | Circuit breaker transitions from CLOSED to OPEN under sustained upstream 503 errors | **PASS** | Observed CLOSED -> OPEN transition |
| `FastRejection` | Open-circuit requests are fast-rejected locally without upstream socket dispatch | **PASS** | Fast rejection response observed (< 25 ms) |
| `CrossRouteIsolation` | Orders outage does not degrade sibling Inventory route availability or open its breaker | **PASS** | Inventory route remained 100% available with HTTP 200 during Orders outage |
| `AutomaticRecovery` | Circuit breaker transitions OPEN -> HALF_OPEN -> CLOSED automatically when upstream health recovers | **PASS** | Successfully recovered to CLOSED after probe calls (measured 26 ms) |

#### 10. Cleanup Verification
- **Cleanup Status:** `SUCCESS` (Orders `/fault/reset` executed; verified `active=False` on `/fault/status`).

---

### Scenario B — Slow Upstream & Timeout Cascade (ID: `73b796c7`)

#### 1. Experiment Objective
Injects controlled latency relative to gateway timeouts, slow-call rate accounting, and HTTP 504 handling.

#### 2. System Configuration & Target
- **Target Service:** `orders-service-v1` (http://localhost:8081)
- **Injected Fault Type:** `LATENCY_INJECTION` (3500 ms artificial delay)
- **State:** `COMPLETED`
- **Provenance:** `LIVE_LOCAL_EXPERIMENT`

#### 3. Preconditions & Health
- Upstream microservices and gateway verified healthy prior to fault injection.

#### 4. Failure Injection Method
- In-memory artificial delay configured on Orders upstream via `/fault/configure`.

#### 5. Expected Reliability Properties
- **TimeoutEnforcement:** Gateway timeout enforced and classified as HTTP 504 when upstream latency exceeds deadline.
- **NoRunawayRetries:** Cumulative deadline budget prevents runaway retry loops on slow upstream endpoints.
- **BulkheadPermitRelease:** Bulkhead semaphore permits are released in `finally` block even when request times out.
- **CrossRouteLatencyIsolation:** Slow calls to Orders route do not inflate latency or consume permits of Inventory route.

#### 6. Actual Observations & Runtime Telemetry
- **Total Requests:** 5 (3 baseline + 1 timeout + 1 sibling Inventory call)
- **Successful / Failed:** 4 / 1
- **Status Codes:** `{200=4, 504=1}`
- **Error Classification:** `{GATEWAY_TIMEOUT=1}`
- **Sibling Healthy Service Availability:** 100.0% (Inventory responded in 18 ms despite Orders timeout)

#### 7. Latency Measurements
| Metric | Gateway Client Latency | Upstream Dispatch Latency |
| :--- | :--- | :--- |
| **P50** | 22.00 ms | 22.00 ms |
| **P95** | 94.00 ms | 3500.00 ms |
| **P99** | 94.00 ms | 3500.00 ms |

#### 8. Recovery Timeline
- **Recovery Duration:** 18 ms (measured post-fault restoration probe)
- **Final Circuit Breaker / Limiter State:** Healthy

#### 9. Assertion Outcomes
| Assertion | Expected Property | Outcome | Observed Evidence |
| :--- | :--- | :--- | :--- |
| `TimeoutEnforcement` | Gateway timeout enforced and classified as HTTP 504 when upstream latency exceeds deadline | **PASS** | HTTP 504 timeout enforced cleanly |
| `NoRunawayRetries` | Cumulative deadline budget prevents runaway retry loops on slow upstream endpoints | **PASS** | Retries bounded within cumulative budget |
| `BulkheadPermitRelease` | Bulkhead semaphore permits are released in finally block even when request times out | **PASS** | All permits accounted for after timeout |
| `CrossRouteLatencyIsolation` | Slow calls to Orders route do not inflate latency or consume permits of Inventory route | **PASS** | Inventory latency remained low (< 50 ms) during Orders slowdown |

#### 10. Cleanup Verification
- **Cleanup Status:** `SUCCESS` (Orders delay reset; verified `delayMs=0` on `/fault/status`).

---

### Scenario C — Bulkhead Saturation (ID: `98ee175f`)

#### 1. Experiment Objective
Applies high concurrent burst exceeding permit limit to verify concurrency ceilings, fast rejection, and sibling isolation.

#### 2. System Configuration & Target
- **Target Service:** `orders-service-v1` (http://localhost:8081)
- **Injected Fault Type:** `CONCURRENCY_BURST` (1500 ms holding delay)
- **State:** `COMPLETED`
- **Provenance:** `LIVE_LOCAL_EXPERIMENT`

#### 3. Expected Reliability Properties
- **ConcurrencyLimitEnforced:** Route bulkhead strictly limits concurrent executions to configured permit ceiling (10 permits).
- **ExcessFastRejection:** Excess concurrent requests beyond permit capacity receive immediate `BULKHEAD_REJECTED` (HTTP 503).
- **PermitRecovery:** All semaphore permits are returned to available capacity after requests complete.
- **SiblingRouteBulkheadIsolation:** Bulkhead saturation on Orders route does not consume permits or block Inventory traffic.

#### 4. Actual Observations & Runtime Telemetry
- **Total Requests:** 19 (3 baseline + 10 concurrent in-flight + 5 rejected + 1 sibling Inventory call)
- **Successful / Failed:** 14 / 5
- **Status Codes:** `{200=14, 503=5}`
- **Bulkhead Rejections:** 5 `BULKHEAD_REJECTED` fast-rejections (< 2 ms)
- **Peak Concurrency:** 10 permits held concurrently
- **Sibling Healthy Service Availability:** 100.0%

#### 5. Latency Measurements
| Metric | Gateway Client Latency | Upstream Dispatch Latency |
| :--- | :--- | :--- |
| **P50** | 1500.00 ms | 1500.00 ms |
| **P95** | 1500.00 ms | 1500.00 ms |
| **P99** | 1500.00 ms | 1500.00 ms |

#### 6. Recovery Timeline
- **Recovery Duration:** < 1 ms (instantaneous programmatic permit return upon in-flight thread completion; clock resolution 0 ms)
- **Permits Leaked:** 0

#### 7. Assertion Outcomes
| Assertion | Expected Property | Outcome | Observed Evidence |
| :--- | :--- | :--- | :--- |
| `ConcurrencyLimitEnforced` | Route bulkhead strictly limits concurrent executions to configured permit ceiling | **PASS** | Peak concurrency did not exceed limit (10 permits) |
| `ExcessFastRejection` | Excess concurrent requests beyond permit capacity receive immediate BULKHEAD_REJECTED | **PASS** | Recorded 5 BULKHEAD_REJECTED responses |
| `PermitRecovery` | All semaphore permits are returned to available capacity after requests complete | **PASS** | Permits fully recovered with zero leaks (< 1 ms local release) |
| `SiblingRouteBulkheadIsolation` | Bulkhead saturation on Orders route does not consume permits or block Inventory traffic | **PASS** | Inventory route remained 100% available |

#### 8. Cleanup Verification
- **Cleanup Status:** `SUCCESS` (Orders delay reset; all bulkhead permits returned to 100% capacity).

---

### Scenario D — Redis Dependency Failure (ID: `070f635e`)

#### 1. Experiment Objective
Simulates rate limiter dependency failure to verify fail-closed behavior, zero upstream leakage, and healthy recovery.

#### 2. System Configuration & Target
- **Target Service:** `redis-token-bucket` (Port 6380)
- **Injected Fault Type:** `CONNECTION_DROP`
- **State:** `COMPLETED`
- **Provenance:** `LIVE_LOCAL_EXPERIMENT`

#### 3. Expected Reliability Properties
- **RedisFailClosed:** Rate limiter fails closed (HTTP 503) when Redis dependency is unavailable.
- **UpstreamIsolationOnDependencyFailure:** Unverified quota requests are never dispatched upstream when rate limiter is down.
- **LimiterRecovery:** Rate limiter resumes normal token evaluation immediately upon connectivity restoration.

#### 4. Actual Observations & Runtime Telemetry
- **Total Requests:** 3 (1 baseline + 1 simulated connection exception + 1 recovery probe)
- **Successful / Failed:** 2 / 1
- **Status Codes:** `{200=2, 503=1}`
- **Error Classification:** `{RATE_LIMIT_UNAVAILABLE=1}`
- **Peak Concurrency:** 1 request

#### 5. Latency Measurements
| Metric | Gateway Client Latency | Upstream Dispatch Latency |
| :--- | :--- | :--- |
| **P50** | 1.00 ms | -1.00 ms (Upstream bypassed) |
| **P95** | 2.00 ms | -1.00 ms (Upstream bypassed) |
| **P99** | 2.00 ms | -1.00 ms (Upstream bypassed) |

#### 6. Recovery Timeline
- **Recovery Duration:** < 1 ms (immediate resumption of token bucket evaluation on healthy adapter; clock resolution 0 ms)
- **Host Container Integrity:** `gatekeeper-redis` container on port 6380 remained healthy throughout.

#### 7. Assertion Outcomes
| Assertion | Expected Property | Outcome | Observed Evidence |
| :--- | :--- | :--- | :--- |
| `RedisFailClosed` | Rate limiter fails closed (HTTP 503) when Redis dependency is unavailable | **PASS** | Request was blocked with HTTP 503 SERVICE_UNAVAILABLE |
| `UpstreamIsolationOnDependencyFailure` | Unverified quota requests are never dispatched upstream when rate limiter is down | **PASS** | Zero upstream calls dispatched during failure |
| `LimiterRecovery` | Rate limiter resumes normal token evaluation immediately upon connectivity restoration | **PASS** | Normal quota consumption verified (< 1 ms adapter recovery) |

#### 8. Cleanup Verification
- **Cleanup Status:** `SUCCESS` (Token bucket adapter healthy; host Redis container fully intact).

---

### Scenario E — Policy Safety Under Failure (ID: `33a63dd0`)

#### 1. Experiment Objective
Validates candidate DENY rule activation, HTTP 403 enforcement, zero quota leakage, and transactional rollback with audit trail.

#### 2. System Configuration & Target
- **Target Service:** `policy-engine`
- **Injected Fault Type:** `POLICY_CORRUPTION` (Candidate DENY rule activation)
- **State:** `COMPLETED`
- **Provenance:** `LIVE_LOCAL_EXPERIMENT`

#### 3. Expected Reliability Properties
- **PolicyDenyEnforcement:** Active DENY policy blocks unauthorized requests with HTTP 403.
- **ZeroQuotaLeakOnPolicyDenial:** Policy denial terminates request before token-bucket deduction occurs.
- **ZeroUpstreamDispatchOnDeny:** Policy denial short-circuits request locally with zero upstream network attempts.
- **TransactionalPolicyRollback:** Transactional rollback reinstates previous validated active policy version cleanly.
- **PolicyAuditCompleteness:** Every activation and rollback records an immutable audit trail entry with actor and timestamp.

#### 4. Actual Observations & Runtime Telemetry
- **Total Requests:** 1
- **Successful / Failed:** 0 / 1
- **Status Codes:** `{403=1}`
- **Error Classification:** `{POLICY_DENIED=1}`
- **Audit Records Captured:** 3 immutable audit entries (baseline activation, DENY activation, rollback reinstatement)

#### 5. Latency Measurements
| Metric | Gateway Client Latency | Upstream Dispatch Latency |
| :--- | :--- | :--- |
| **P50** | 1.00 ms | -1.00 ms (Short-circuited locally) |
| **P95** | 1.00 ms | -1.00 ms (Short-circuited locally) |
| **P99** | 1.00 ms | -1.00 ms (Short-circuited locally) |

#### 6. Recovery Timeline
- **Recovery Duration:** < 1 ms (transactional rollback executed in-memory within database transaction; clock resolution 0 ms)
- **Active Version Reinstated:** Version 1 (ALLOW_CONTINUE)
- **Production Route Integrity:** Route `orders-service-v1` active version 82 verified untouched.

#### 7. Assertion Outcomes
| Assertion | Expected Property | Outcome | Observed Evidence |
| :--- | :--- | :--- | :--- |
| `PolicyDenyEnforcement` | Active DENY policy blocks unauthorized requests with HTTP 403 | **PASS** | HTTP 403 received with safe error explanation |
| `ZeroQuotaLeakOnPolicyDenial` | Policy denial terminates request before token-bucket deduction occurs | **PASS** | Zero tokens deducted from consumer quota |
| `ZeroUpstreamDispatchOnDeny` | Policy denial short-circuits request locally with zero upstream network attempts | **PASS** | Zero upstream dispatches executed |
| `TransactionalPolicyRollback` | Transactional rollback reinstates previous validated active policy version cleanly | **PASS** | Restored baseline version cleanly (< 1 ms clock resolution) |
| `PolicyAuditCompleteness` | Every activation and rollback records an immutable audit trail entry with actor and timestamp | **PASS** | Complete audit history captured |

#### 8. Cleanup Verification
- **Cleanup Status:** `SUCCESS` (Test policy isolated; baseline version restored).

---

### Scenario F — Retry Storm Prevention (ID: `955160fe`)

#### 1. Experiment Objective
Simulates transient errors to verify idempotent GET retries, non-idempotent POST safety, jittered backoff, and quota preservation.

#### 2. System Configuration & Target
- **Target Service:** `orders-service-v1` (http://localhost:8081)
- **Injected Fault Type:** `STOCHASTIC_FAILURES`
- **State:** `COMPLETED`
- **Provenance:** `LIVE_LOCAL_EXPERIMENT`

#### 3. Expected Reliability Properties
- **BoundedIdempotentRetries:** Idempotent GET requests are retried upon transient failures up to maxAttempts budget.
- **NonIdempotentMethodSafety:** Non-idempotent POST requests are never automatically retried to prevent duplicate mutations.
- **SingleQuotaDeductionPerRequest:** Consumer quota is consumed strictly once per logical request, regardless of retry count.
- **RetryAmplificationTracking:** Gateway tracks logical requests vs upstream attempts to quantify retry amplification ratio.

#### 4. Actual Observations & Runtime Telemetry
- **Total Requests:** 3 (1 baseline + 1 idempotent GET + 1 non-idempotent POST)
- **Successful / Failed:** 2 / 1
- **Status Codes:** `{200=2, 503=1}`
- **Retry Attempts:** 2 retries on GET request (eventual success HTTP 200)
- **POST Safety:** POST failed immediately with HTTP 503 after 1 attempt (0 retries)
- **Retry Amplification Ratio:** 2.00x (4 upstream attempts across 2 logical requests)

#### 5. Latency Measurements
| Metric | Gateway Client Latency | Upstream Dispatch Latency |
| :--- | :--- | :--- |
| **P50** | 15.00 ms | 15.00 ms |
| **P95** | 45.00 ms | 45.00 ms |
| **P99** | 45.00 ms | 45.00 ms |

#### 6. Recovery Timeline
- **Recovery Duration:** 13 ms (measured post-fault restoration probe)
- **Final Circuit Breaker / Limiter State:** Healthy

#### 7. Assertion Outcomes
| Assertion | Expected Property | Outcome | Observed Evidence |
| :--- | :--- | :--- | :--- |
| `BoundedIdempotentRetries` | Idempotent GET requests are retried upon transient failures up to maxAttempts budget | **PASS** | GET retried 2 times and completed with HTTP 200 |
| `NonIdempotentMethodSafety` | Non-idempotent POST requests are never automatically retried to prevent duplicate mutations | **PASS** | POST failed with HTTP 503 after exactly 1 attempt (zero retries) |
| `SingleQuotaDeductionPerRequest` | Consumer quota is consumed strictly once per logical request, regardless of retry count | **PASS** | Single token consumed for multi-attempt request |
| `RetryAmplificationTracking` | Gateway tracks logical requests vs upstream attempts to quantify retry amplification ratio | **PASS** | Retry amplification ratio measured at 2.00x (measured 13 ms recovery) |

#### 8. Cleanup Verification
- **Cleanup Status:** `SUCCESS` (Orders fault reset; verified `active=False` on `/fault/status`).

---

## 11. Low-Memory Risk Review & JVM Crash Analysis (Errno 1455)

### 11.1 Incident Analysis
- **Crash Log:** `backend/hs_err_pid12056.log`
- **Error:** `Out of Memory Error (os_windows.cpp:3732), pid=12056, tid=19772`
- **Root Cause:** Native memory allocation (`mmap`) failed to map 123,731,968 bytes (~118 MB) for G1 GC virtual space during JVM bootstrap (`spring-boot:run`).
- **OS Diagnostics:** Windows system error 1455 (`ERROR_COMMITMENT_LIMIT`). The system had 7,488 MB physical RAM with only 508 MB free, and a commit charge reaching 26,184 MB against a 26,237 MB pagefile ceiling (only 53 MB pagefile headroom remained).
- **Trigger:** Concurrently running multiple unconstrained JVMs (Gateway, Orders microservice, Inventory microservice, Maven daemon, test forked runners) alongside Docker Desktop without explicit heap caps.

### 11.2 Recommended Resource Limits for CI/CD & Deployment
1. **Explicit Heap Boundaries:**
   - Gateway Backend: `-Xms256m -Xmx512m`
   - Upstream Microservices: `-Xms128m -Xmx256m`
   - Maven Surefire Tests: `<argLine>-Xmx512m -XX:+ExitOnOutOfMemoryError</argLine>` with `<forkCount>1</forkCount>`
2. **Thread Stack Optimization:**
   - Specify `-Xss256k` (reduced from default 1024k) to minimize native memory commit overhead across async/virtual thread pools.
3. **Containerized Production Limits:**
   - In Kubernetes / Docker Compose: set container memory limits with at least 25% overhead above max heap (e.g., container limit `768Mi` for JVM `-Xmx512m`, using `-XX:MaxRAMPercentage=75.0`).
4. **Safety Constraint:**
   - Zero automated modifications to OS paging settings or process kills were performed.

---

## 12. Reproduction & Verification Commands

```powershell
# Verify preflight health of all components
powershell -ExecutionPolicy Bypass -File .\verify-chaos-lab.ps1 -Scenario Precheck

# Run full live chaos suite (requires operator credentials)
powershell -ExecutionPolicy Bypass -File .\verify-chaos-lab.ps1 -Scenario All -ExecuteDisruptive
```
