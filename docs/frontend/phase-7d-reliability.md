# Gatekeeper Phase 7D — Resilience Workbench Architecture & Operational Semantics

## 1. Overview & Architectural Mission
Gatekeeper is designed as a mission-critical, enterprise-grade API gateway and infrastructure control plane. In distributed microservice architectures, transient failures, cascading timeouts, network partitions, and resource exhaustion are inevitable realities.

The **Resilience Workbench** (`/reliability?tab=resilience`) provides Site Reliability Engineers (SREs), system architects, and platform operators with an interactive observability console and control mechanism for Gatekeeper's underlying **Resilience4j** fault-tolerance and concurrency primitives.

---

## 2. Circuit Breaker Architecture & State Transition Machine

Gatekeeper embeds Resilience4j circuit breakers on every configured upstream route (e.g., `orders-service-v1`, `inventory-service-v1`). The circuit breaker shields upstream services from overload and prevents upstream latency from starving gateway resources.

```
                  ┌──────────────────────┐
                  │                      │
       ┌─────────►│     CLOSED (Nominal) │◄─────────┐
       │          │                      │          │
       │          └──────────┬───────────┘          │
       │                     │                      │
       │             Failure Rate ≥ 50%             │
       │                     │                      │
       │                     ▼                      │
Success Rate ≥ 50%┌──────────────────────┐          │ Success Rate < 50%
       │          │                      │          │ (Re-trip)
       │          │   OPEN (Fast-Fail)   │          │
       │          │                      │          │
       │          └──────────┬───────────┘          │
       │                     │                      │
       │              Wait Duration                 │
       │                 Elapsed                    │
       │                     │                      │
       │                     ▼                      │
       │          ┌──────────────────────┐          │
       └──────────┤  HALF_OPEN (Probing) ├──────────┘
                  │                      │
                  └──────────────────────┘
```

### 2.1 State Definitions
1. **CLOSED (Nominal)**:
   - All inbound requests for the route are forwarded to the configured upstream destination.
   - Circuit breaker monitors call outcomes (successes, errors, slow calls) within a circular ring buffer sliding window.
2. **OPEN (Fast-Fail / Tripped)**:
   - Tripped when the failure rate or slow call rate exceeds configured percentage thresholds.
   - Subsequent inbound traffic is immediately rejected locally at the gateway layer (< 25 ms) with `HTTP 503 Service Unavailable` (`CIRCUIT_BREAKER_OPEN`).
   - Prevents cascading gateway thread exhaustion and allows damaged upstream clusters time to recover.
3. **HALF_OPEN (Probing)**:
   - After the configured wait duration in the `OPEN` state elapses, the breaker enters `HALF_OPEN`.
   - A configurable probe batch of trial requests (e.g., 5 calls) is permitted through to test upstream health.
   - If the probe failure rate remains above the threshold, the breaker immediately re-trips to `OPEN`.
   - If probe calls succeed, the breaker resets to `CLOSED`.

### 2.2 Mathematical Model & Sliding Window Accounting
Gatekeeper employs a **count-based sliding window** of size $N = 20$ calls:
- **Failure Rate Calculation**:
  $$\text{Failure Rate} (\%) = \left( \frac{\text{Failed Calls}}{\text{Total Calls in Window}} \right) \times 100$$
  Trip condition: $\text{Failure Rate} \ge 50.0\%$ (minimum 5 calls recorded).
- **Slow Call Rate Calculation**:
  A call is classified as "slow" if its response time $T > T_{\text{slow}}$ ($T_{\text{slow}} = 2000\text{ ms}$).
  $$\text{Slow Call Rate} (\%) = \left( \frac{\text{Slow Calls}}{\text{Total Calls in Window}} \right) \times 100$$
  Trip condition: $\text{Slow Call Rate} \ge 50.0\%$.

---

## 3. Bulkhead Concurrency Limiting & Semaphore Isolation

To prevent a single slow or misbehaving upstream route from consuming all gateway resources (such as worker threads or socket connections), Gatekeeper implements route-level bulkheads.

### 3.1 Semaphore Implementation
- Each upstream route is bounded by a dedicated concurrency semaphore.
- Default configuration: `maxConcurrentCalls = 10` permits.
- `maxWaitDuration = 0 ms`: Gatekeeper enforces non-blocking semaphore acquisition. If all 10 permits are in-flight, excess concurrent requests receive an immediate local rejection:
  ```json
  {
    "status": 503,
    "error": "Service Unavailable",
    "message": "BULKHEAD_REJECTED: Route orders-service-v1 concurrency ceiling reached",
    "routeId": "orders-service-v1"
  }
  ```
- **Strict Leak Prevention**: Semaphore permits are acquired immediately prior to dispatch and released in strict `try-finally` blocks upon response completion, upstream cancellation, or gateway timeout.

---

## 4. Retry Budget, Exponential Backoff, Jitter, and Cumulative Deadlines

Blind retries in distributed systems can trigger dangerous "retry storms" that amplify outages. Gatekeeper enforces strict backoff and deadline constraints:

### 4.1 Retry Configuration
- **Max Attempts**: 3 attempts (1 initial attempt + 2 retries).
- **Retry Criteria**: Retries are permitted only on idempotent operations (`GET`, `HEAD`) and specific transient failures (HTTP 502 Bad Gateway, 503 Service Unavailable, socket disconnect). Retries are strictly blocked on non-idempotent methods (`POST`, `PATCH`, `DELETE`) without explicit transaction idempotency keys.
- **Exponential Backoff with Full Jitter**:
  $$t_{\text{wait}} = \text{random}(0, \min(t_{\text{max}}, t_{\text{base}} \times 2^{\text{attempt}}))$$
  - Base interval: 200 ms
  - Jitter factor: 0.5
  - Max backoff: 2000 ms

### 4.2 Cumulative Timeout Deadlines
- To prevent retries from stacking indefinitely, Gatekeeper enforces a **cumulative deadline ceiling**:
  $$\sum_{i=1}^{k} (T_{\text{attempt}_i} + t_{\text{wait}_i}) \le T_{\text{cumulative\_deadline}}$$
  Default cumulative deadline: 5000 ms.
- If the cumulative deadline expires while awaiting a retry backoff or upstream response, the gateway immediately terminates execution and returns `HTTP 504 Gateway Timeout`.

---

## 5. Administrative Control: Circuit Breaker Reset Action

In emergency operational scenarios, an SRE or administrator can manually reset a circuit breaker from `OPEN` or `HALF_OPEN` back to `CLOSED`.

### 5.1 Security & Authorization Guardrails
- **Endpoint**: `POST /api/v1/resilience/routes/{routeId}/reset`
- **Role Requirement**: Requires `ROLE_SUPER_ADMIN` or `ROLE_ADMIN`. Read-only roles (`ROLE_ANALYST`) are blocked with HTTP 403.
- **Frontend Safeguard**: The Resilience Workbench presents a destructive-action modal requiring the user to type the exact route identifier (e.g. `orders-service-v1`) to confirm the manual reset.
- **Telemetry Invalidation**: Upon reset, the Resilience4j circular ring buffer is purged, failure rate is cleared to 0.0%, and an audit event is published.

---

## 6. Operational Timeline Derivation & Severity Classification Rules

The Operational Timeline (`/reliability?tab=timeline`) synthesizes events from multiple telemetry streams into a unified chronological log. Rather than relying on synthetic or arbitrary logs, every event is derived using explicit deterministic rules:

| Severity | Derivation Rule | Representative Events |
| :--- | :--- | :--- |
| **CRITICAL** | Route circuit breaker in `OPEN` state, upstream HTTP 5xx responses, or failed chaos experiment assertions. | Circuit breaker tripped on `orders-service-v1`; Upstream 503 outage. |
| **WARNING** | Circuit breaker in `HALF_OPEN`, HTTP 429 (rate-limited), HTTP 403 (policy denial), or route failure rate > 10%. | Rate limit burst reached for API consumer; Policy deny rule matched. |
| **RECOVERY** | Circuit breaker manually or automatically reset to `CLOSED`, policy rollbacks, or completed post-fault recovery. | Circuit breaker reset on `orders-service-v1` by administrator. |
| **INFO** | Nominal 2xx traffic proxying, policy activation events, or scheduled experiment completions. | Policy version activated; Baseline probe execution. |
