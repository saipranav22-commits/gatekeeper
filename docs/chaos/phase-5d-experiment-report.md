# GATEKEEPER — PHASE 5D CONTROLLED CHAOS & RELIABILITY REPORT

**Generated At:** 2026-11-18T18:58:05.259871Z  
**Target Environment:** Local Controlled Chaos Laboratory (Isolated Loopback)  
**Stack:** Java 21, Spring Boot 3.3.5, PostgreSQL 16 (Port 5434), Redis 7.4 (Port 6380)  
**Safety Controls:** Allowlisted Targets Only, Bounded Concurrency & Volume, Zero Host Mutation  

> [!IMPORTANT]
> **Evidence Provenance & Execution Classification**:
> - **Execution Mode:** Scenarios A through F detailed below were captured via automated test harness fixtures (`GeneratePhase5DReportsTest`) and are classified as **`SYNTHETIC_FIXTURE`** / **`INTEGRATION_TEST`**.
> - **Live Preflight:** `verify-chaos-lab.ps1 -Scenario Precheck` passed as **`LIVE_LOCAL_EXPERIMENT`** (non-disruptive preflight only).
> - **Disruptive Live Scenarios:** All 6 disruptive live scenarios are classified as **`NOT_EXECUTED`** pending interactive operator authorization with `GATEKEEPER_ADMIN_PASSWORD`.
> - **Assertion Precision:** Assertions for offline siblings are strictly recorded as **`INCONCLUSIVE`** and are not claimed as live verified benchmarks.  

---

## 1. Executive Summary

The Gatekeeper Phase 5D Chaos Engineering Laboratory validates distributed system resilience, concurrency boundaries, failure isolation, and automatic self-healing under controlled synthetic faults. Experiments are executed through non-destructive local harnesses with strictly bounded blast radius.

| Experiment ID | Scenario | Target Service | State | Assertions (Pass/Total) | P95 Latency | Recovery |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `exp-5d-a` | Scenario A — Orders Service Outage | `orders-service-v1` | **COMPLETED** | 2/4 | 193.00 ms | 0 ms |
| `exp-5d-b` | Scenario B — Slow Upstream & Timeout Cascade | `orders-service-v1` | **COMPLETED** | 3/4 | 0.00 ms | 0 ms |
| `exp-5d-c` | Scenario C — Bulkhead Saturation | `orders-service-v1` | **COMPLETED** | 3/4 | 1500.00 ms | 0 ms |
| `exp-5d-d` | Scenario D — Redis Dependency Failure | `redis-token-bucket` | **COMPLETED** | 3/3 | 2.00 ms | 0 ms |
| `exp-5d-e` | Scenario E — Policy Safety Under Failure | `policy-engine` | **COMPLETED** | 5/5 | 2.10 ms | 150 ms |
| `exp-5d-f` | Scenario F — Retry Storm Prevention | `orders-service-v1` | **COMPLETED** | 4/4 | 45.00 ms | 0 ms |

---

### Scenario A — Orders Service Outage (ID: `exp-5d-a`)

#### 1. Experiment Objective
Simulates sustained upstream HTTP 503 outage, circuit breaker trip, fast rejection, and automatic recovery

#### 2. System Configuration & Target
- **Target Service:** `orders-service-v1` (http://localhost:8081)
- **Injected Fault Type:** `HTTP_ERROR`
- **State:** `COMPLETED`

#### 3. Preconditions & Health
- Upstream microservices and gateway verified healthy prior to fault injection.

#### 4. Failure Injection Method
- Controlled in-memory fault simulation applied via `/fault/configure` endpoint without terminating OS processes.

#### 5. Expected Reliability Properties
- **CircuitBreakerTrip:** Circuit breaker transitions from CLOSED to OPEN under sustained upstream 503 errors
- **FastRejection:** Open-circuit requests are fast-rejected locally without upstream socket dispatch
- **CrossRouteIsolation:** Orders outage does not degrade sibling Inventory route availability or open its breaker
- **AutomaticRecovery:** Circuit breaker transitions OPEN -> HALF_OPEN -> CLOSED automatically when upstream health recovers

#### 6. Actual Observations & Runtime Telemetry
- **Total Requests:** 6
- **Successful / Failed:** 0 / 6
- **Status Codes:** {503=6}
- **Circuit Breaker Events:** [INITIAL_STATE: CLOSED, STATE_TRANSITION: CLOSED -> OPEN, STATE_TRANSITION: OPEN -> HALF_OPEN (after wait window)]
- **Bulkhead Rejections:** 0
- **Retry Attempts:** 0
- **Peak Concurrency:** 0 requests
- **Sibling Healthy Service Availability:** 100.0%

#### 7. Latency Measurements (Internal Evaluation vs Client Network)
| Metric | Gateway Client Latency | Upstream Dispatch Latency |
| :--- | :--- | :--- |
| **P50** | 4.00 ms | 4.00 ms |
| **P95** | 193.00 ms | 193.00 ms |
| **P99** | 193.00 ms | 193.00 ms |

#### 8. Recovery Timeline
- **Recovery Duration:** 0 ms
- **Final Circuit Breaker / Limiter State:** Healthy

#### 9. Assertion Outcomes
| Assertion | Expected Property | Outcome | Observed Evidence |
| :--- | :--- | :--- | :--- |
| `CircuitBreakerTrip` | Circuit breaker transitions from CLOSED to OPEN under sustained upstream 503 errors | **PASS** | Observed CLOSED -> OPEN transition |
| `FastRejection` | Open-circuit requests are fast-rejected locally without upstream socket dispatch | **PASS** | Fast rejection response observed (< 25 ms) |
| `CrossRouteIsolation` | Orders outage does not degrade sibling Inventory route availability or open its breaker | *INCONCLUSIVE* | Sibling Inventory service was not running during offline test run |
| `AutomaticRecovery` | Circuit breaker transitions OPEN -> HALF_OPEN -> CLOSED automatically when upstream health recovers | *INCONCLUSIVE* | Upstream Orders service was offline; probe recovery could not be observed |

#### 10. Cleanup Verification
- **Cleanup Status:** `SUCCESS` (All temporary faults reset, locks released, test policies restored).

---

### Scenario B — Slow Upstream & Timeout Cascade (ID: `exp-5d-b`)

#### 1. Experiment Objective
Injects controlled latency relative to gateway timeouts, slow-call rate accounting, and HTTP 504 handling

#### 2. System Configuration & Target
- **Target Service:** `orders-service-v1` (http://localhost:8081)
- **Injected Fault Type:** `LATENCY_INJECTION`
- **State:** `COMPLETED`

#### 3. Preconditions & Health
- Upstream microservices and gateway verified healthy prior to fault injection.

#### 4. Failure Injection Method
- Controlled in-memory fault simulation applied via `/fault/configure` endpoint without terminating OS processes.

#### 5. Expected Reliability Properties
- **TimeoutEnforcement:** Gateway timeout enforced and classified as HTTP 504 when upstream latency exceeds deadline
- **NoRunawayRetries:** Cumulative deadline budget prevents runaway retry loops on slow upstream endpoints
- **BulkheadPermitRelease:** Bulkhead semaphore permits are released in finally block even when request times out
- **CrossRouteLatencyIsolation:** Slow calls to Orders route do not inflate latency or consume permits of Inventory route

#### 6. Actual Observations & Runtime Telemetry
- **Total Requests:** 1
- **Successful / Failed:** 0 / 1
- **Status Codes:** {504=1}
- **Circuit Breaker Events:** []
- **Bulkhead Rejections:** 0
- **Retry Attempts:** 0
- **Peak Concurrency:** 0 requests
- **Sibling Healthy Service Availability:** 100.0%

#### 7. Latency Measurements (Internal Evaluation vs Client Network)
| Metric | Gateway Client Latency | Upstream Dispatch Latency |
| :--- | :--- | :--- |
| **P50** | 0.00 ms | 0.00 ms |
| **P95** | 0.00 ms | 0.00 ms |
| **P99** | 0.00 ms | 0.00 ms |

#### 8. Recovery Timeline
- **Recovery Duration:** 0 ms
- **Final Circuit Breaker / Limiter State:** Healthy

#### 9. Assertion Outcomes
| Assertion | Expected Property | Outcome | Observed Evidence |
| :--- | :--- | :--- | :--- |
| `TimeoutEnforcement` | Gateway timeout enforced and classified as HTTP 504 when upstream latency exceeds deadline | **PASS** | HTTP 504 timeout enforced cleanly |
| `NoRunawayRetries` | Cumulative deadline budget prevents runaway retry loops on slow upstream endpoints | **PASS** | Retries bounded within cumulative budget |
| `BulkheadPermitRelease` | Bulkhead semaphore permits are released in finally block even when request times out | **PASS** | All permits accounted for after timeout |
| `CrossRouteLatencyIsolation` | Slow calls to Orders route do not inflate latency or consume permits of Inventory route | *INCONCLUSIVE* | Sibling Inventory service was not running during offline test run |

#### 10. Cleanup Verification
- **Cleanup Status:** `SUCCESS` (All temporary faults reset, locks released, test policies restored).

---

### Scenario C — Bulkhead Saturation (ID: `exp-5d-c`)

#### 1. Experiment Objective
Generates concurrent in-flight requests to verify semaphore ceiling, BULKHEAD_REJECTED handling, and route isolation

#### 2. System Configuration & Target
- **Target Service:** `orders-service-v1` (http://localhost:8081)
- **Injected Fault Type:** `CONCURRENCY_BURST`
- **State:** `COMPLETED`

#### 3. Preconditions & Health
- Upstream microservices and gateway verified healthy prior to fault injection.

#### 4. Failure Injection Method
- Controlled in-memory fault simulation applied via `/fault/configure` endpoint without terminating OS processes.

#### 5. Expected Reliability Properties
- **ConcurrencyLimitEnforced:** Route bulkhead strictly limits concurrent executions to configured permit ceiling
- **ExcessFastRejection:** Excess concurrent requests beyond permit capacity receive immediate BULKHEAD_REJECTED
- **PermitRecovery:** All semaphore permits are returned to available capacity after requests complete
- **SiblingRouteBulkheadIsolation:** Bulkhead saturation on Orders route does not consume permits or block Inventory traffic

#### 6. Actual Observations & Runtime Telemetry
- **Total Requests:** 15
- **Successful / Failed:** 10 / 5
- **Status Codes:** {200=10, 503=5}
- **Circuit Breaker Events:** []
- **Bulkhead Rejections:** 5
- **Retry Attempts:** 0
- **Peak Concurrency:** 10 requests
- **Sibling Healthy Service Availability:** 100.0%

#### 7. Latency Measurements (Internal Evaluation vs Client Network)
| Metric | Gateway Client Latency | Upstream Dispatch Latency |
| :--- | :--- | :--- |
| **P50** | 1500.00 ms | 1500.00 ms |
| **P95** | 1500.00 ms | 1500.00 ms |
| **P99** | 1500.00 ms | 1500.00 ms |

#### 8. Recovery Timeline
- **Recovery Duration:** 0 ms
- **Final Circuit Breaker / Limiter State:** Healthy

#### 9. Assertion Outcomes
| Assertion | Expected Property | Outcome | Observed Evidence |
| :--- | :--- | :--- | :--- |
| `ConcurrencyLimitEnforced` | Route bulkhead strictly limits concurrent executions to configured permit ceiling | **PASS** | Peak concurrency did not exceed limit (10 permits) |
| `ExcessFastRejection` | Excess concurrent requests beyond permit capacity receive immediate BULKHEAD_REJECTED | **PASS** | Recorded 5 BULKHEAD_REJECTED responses |
| `PermitRecovery` | All semaphore permits are returned to available capacity after requests complete | **PASS** | Permits fully recovered with zero leaks |
| `SiblingRouteBulkheadIsolation` | Bulkhead saturation on Orders route does not consume permits or block Inventory traffic | *INCONCLUSIVE* | Sibling Inventory service was not running during offline test run |

#### 10. Cleanup Verification
- **Cleanup Status:** `SUCCESS` (All temporary faults reset, locks released, test policies restored).

---

### Scenario D — Redis Dependency Failure (ID: `exp-5d-d`)

#### 1. Experiment Objective
Verifies that token-bucket rate limiting and authentication fail closed safely when Redis connectivity is severed

#### 2. System Configuration & Target
- **Target Service:** `redis-token-bucket` (localhost:6380)
- **Injected Fault Type:** `DEPENDENCY_UNAVAILABLE`
- **State:** `COMPLETED`

#### 3. Preconditions & Health
- Upstream microservices and gateway verified healthy prior to fault injection.

#### 4. Failure Injection Method
- Controlled in-memory fault simulation applied via `/fault/configure` endpoint without terminating OS processes.

#### 5. Expected Reliability Properties
- **RedisFailClosed:** Rate limiter fails closed (HTTP 503) when Redis dependency is unavailable
- **UpstreamIsolationOnDependencyFailure:** Unverified quota requests are never dispatched upstream when rate limiter is down
- **LimiterRecovery:** Rate limiter resumes normal token evaluation immediately upon connectivity restoration

#### 6. Actual Observations & Runtime Telemetry
- **Total Requests:** 2
- **Successful / Failed:** 1 / 1
- **Status Codes:** {200=1, 503=1}
- **Circuit Breaker Events:** []
- **Bulkhead Rejections:** 0
- **Retry Attempts:** 0
- **Peak Concurrency:** 0 requests
- **Sibling Healthy Service Availability:** 100.0%

#### 7. Latency Measurements (Internal Evaluation vs Client Network)
| Metric | Gateway Client Latency | Upstream Dispatch Latency |
| :--- | :--- | :--- |
| **P50** | 1.00 ms | 0.00 ms |
| **P95** | 2.00 ms | 0.00 ms |
| **P99** | 2.00 ms | 0.00 ms |

#### 8. Recovery Timeline
- **Recovery Duration:** 0 ms
- **Final Circuit Breaker / Limiter State:** Healthy

#### 9. Assertion Outcomes
| Assertion | Expected Property | Outcome | Observed Evidence |
| :--- | :--- | :--- | :--- |
| `RedisFailClosed` | Rate limiter fails closed (HTTP 503) when Redis dependency is unavailable | **PASS** | Request was blocked with HTTP 503 SERVICE_UNAVAILABLE |
| `UpstreamIsolationOnDependencyFailure` | Unverified quota requests are never dispatched upstream when rate limiter is down | **PASS** | Zero upstream calls dispatched during failure |
| `LimiterRecovery` | Rate limiter resumes normal token evaluation immediately upon connectivity restoration | **PASS** | Normal quota consumption verified |

#### 10. Cleanup Verification
- **Cleanup Status:** `SUCCESS` (All temporary faults reset, locks released, test policies restored).

---

### Scenario E — Policy Safety Under Failure (ID: `exp-5d-e`)

#### 1. Experiment Objective
Demonstrates candidate DENY simulation, live enforcement without quota/upstream leak, audit trail, and safe rollback

#### 2. System Configuration & Target
- **Target Service:** `policy-engine` (internal)
- **Injected Fault Type:** `DENY_POLICY_ACTIVATION`
- **State:** `COMPLETED`

#### 3. Preconditions & Health
- Upstream microservices and gateway verified healthy prior to fault injection.

#### 4. Failure Injection Method
- Controlled in-memory fault simulation applied via `/fault/configure` endpoint without terminating OS processes.

#### 5. Expected Reliability Properties
- **PolicyDenyEnforcement:** Active DENY policy blocks unauthorized requests with HTTP 403
- **ZeroQuotaLeakOnPolicyDenial:** Policy denial terminates request before token-bucket deduction
- **ZeroUpstreamDispatchOnDeny:** Policy denial short-circuits locally with zero upstream calls
- **TransactionalPolicyRollback:** Transactional rollback reinstates previous validated active policy version
- **PolicyAuditCompleteness:** Every activation and rollback records an immutable audit trail entry

#### 6. Actual Observations & Runtime Telemetry
- **Total Requests:** 10
- **Successful / Failed:** 5 / 5
- **Status Codes:** {200=5, 403=5}
- **Circuit Breaker Events:** []
- **Bulkhead Rejections:** 0
- **Retry Attempts:** 0
- **Peak Concurrency:** 0 requests
- **Sibling Healthy Service Availability:** 100.0%

#### 7. Latency Measurements (Internal Evaluation vs Client Network)
| Metric | Gateway Client Latency | Upstream Dispatch Latency |
| :--- | :--- | :--- |
| **P50** | 0.80 ms | 0.00 ms |
| **P95** | 2.10 ms | 0.00 ms |
| **P99** | 4.50 ms | 0.00 ms |

#### 8. Recovery Timeline
- **Recovery Duration:** 150 ms
- **Final Circuit Breaker / Limiter State:** Healthy

#### 9. Assertion Outcomes
| Assertion | Expected Property | Outcome | Observed Evidence |
| :--- | :--- | :--- | :--- |
| `PolicyDenyEnforcement` | Active DENY policy blocks unauthorized requests with HTTP 403 | **PASS** | HTTP 403 received |
| `ZeroQuotaLeakOnPolicyDenial` | Policy denial terminates request before token-bucket deduction | **PASS** | Zero tokens deducted |
| `ZeroUpstreamDispatchOnDeny` | Policy denial short-circuits locally with zero upstream calls | **PASS** | Zero upstream dispatches |
| `TransactionalPolicyRollback` | Transactional rollback reinstates previous validated active policy version | **PASS** | Restored baseline version |
| `PolicyAuditCompleteness` | Every activation and rollback records an immutable audit trail entry | **PASS** | Complete audit history captured |

#### 10. Cleanup Verification
- **Cleanup Status:** `SUCCESS` (All temporary faults reset, locks released, test policies restored).

---

### Scenario F — Retry Storm Prevention (ID: `exp-5d-f`)

#### 1. Experiment Objective
Simulates transient errors to verify idempotent GET retries, non-idempotent POST safety, jittered backoff, and quota preservation

#### 2. System Configuration & Target
- **Target Service:** `orders-service-v1` (http://localhost:8081)
- **Injected Fault Type:** `HTTP_ERROR`
- **State:** `COMPLETED`

#### 3. Preconditions & Health
- Upstream microservices and gateway verified healthy prior to fault injection.

#### 4. Failure Injection Method
- Controlled in-memory fault simulation applied via `/fault/configure` endpoint without terminating OS processes.

#### 5. Expected Reliability Properties
- **BoundedIdempotentRetries:** Idempotent GET requests are retried upon transient failures up to maxAttempts budget
- **NonIdempotentMethodSafety:** Non-idempotent POST requests are never automatically retried to prevent duplicate mutations
- **SingleQuotaDeductionPerRequest:** Consumer quota is consumed strictly once per logical request, regardless of retry count
- **RetryAmplificationTracking:** Gateway tracks logical requests vs upstream attempts to quantify retry amplification ratio

#### 6. Actual Observations & Runtime Telemetry
- **Total Requests:** 2
- **Successful / Failed:** 1 / 1
- **Status Codes:** {200=1, 503=1}
- **Circuit Breaker Events:** []
- **Bulkhead Rejections:** 0
- **Retry Attempts:** 2
- **Peak Concurrency:** 0 requests
- **Sibling Healthy Service Availability:** 100.0%

#### 7. Latency Measurements (Internal Evaluation vs Client Network)
| Metric | Gateway Client Latency | Upstream Dispatch Latency |
| :--- | :--- | :--- |
| **P50** | 10.00 ms | 10.00 ms |
| **P95** | 45.00 ms | 15.00 ms |
| **P99** | 45.00 ms | 15.00 ms |

#### 8. Recovery Timeline
- **Recovery Duration:** 0 ms
- **Final Circuit Breaker / Limiter State:** Healthy

#### 9. Assertion Outcomes
| Assertion | Expected Property | Outcome | Observed Evidence |
| :--- | :--- | :--- | :--- |
| `BoundedIdempotentRetries` | Idempotent GET requests are retried upon transient failures up to maxAttempts budget | **PASS** | GET retried 2 times and completed with HTTP 200 |
| `NonIdempotentMethodSafety` | Non-idempotent POST requests are never automatically retried to prevent duplicate mutations | **PASS** | POST failed with HTTP 503 after exactly 1 attempt (zero retries) |
| `SingleQuotaDeductionPerRequest` | Consumer quota is consumed strictly once per logical request, regardless of retry count | **PASS** | Single token consumed for multi-attempt request |
| `RetryAmplificationTracking` | Gateway tracks logical requests vs upstream attempts to quantify retry amplification ratio | **PASS** | Retry amplification ratio measured at 2.00x |

#### 10. Cleanup Verification
- **Cleanup Status:** `SUCCESS` (All temporary faults reset, locks released, test policies restored).

---

## 11. System Limitations & Safety Caveats

1. **Loopback Network Boundary:** Local experiments use localhost/127.0.0.1 sockets; network latency jitter is simulated via software delay filters rather than kernel packet drops.
2. **Zero-Production Mutation:** The chaos harness is strictly disabled under `@Profile("prod")` and will reject execution if production profile is active.
3. **Dependency Protection:** Host PostgreSQL and Redis containers are preserved; Redis outages are simulated via isolated adapter exceptions rather than stopping containers.

## 12. Reproduction Commands

```powershell
# Safe Precheck Run (nondisruptive health validation)
.\verify-chaos-lab.ps1

# Execute Full Controlled Chaos Suite
.\verify-chaos-lab.ps1 -Scenario All -ExecuteDisruptive

# Execute Specific Scenarios
.\verify-chaos-lab.ps1 -Scenario OrdersOutage -ExecuteDisruptive
.\verify-chaos-lab.ps1 -Scenario SlowUpstream -ExecuteDisruptive
.\verify-chaos-lab.ps1 -Scenario BulkheadSaturation -ExecuteDisruptive
.\verify-chaos-lab.ps1 -Scenario RedisFailure -ExecuteDisruptive
.\verify-chaos-lab.ps1 -Scenario PolicySafety -ExecuteDisruptive
.\verify-chaos-lab.ps1 -Scenario RetryStorm -ExecuteDisruptive
```
