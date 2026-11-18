# ADR 004: Controlled Chaos Engineering Harness vs. Unrestricted Production Tooling

## Status
**Accepted** — November 18, 2026

## Context
As Gatekeeper matured through Phase 5B (Resilience Engine) and Phase 5C (Explainable Policy Engine), verifying its behavior under failure became paramount. Distributed systems exhibit complex cascading failure modes:
1. Upstream service outages causing thread exhaustion.
2. Latency spikes cascading into downstream timeouts and retry storms.
3. Concurrency bursts overwhelming backend services.
4. Core dependency outages (such as Redis or PostgreSQL).
5. Misconfigured or malicious policies denying legitimate traffic.

Traditional production chaos tools (e.g., Chaos Mesh, Gremlin, or unrestricted Chaos Monkey daemons) introduce significant operational blast-radius risks, including unintended node eviction, container termination, persistent data loss, and security boundary violations if exposed via public or unauthenticated APIs.

## Decision
We implemented a **Controlled Chaos Engineering Laboratory** (`com.gatekeeper.chaos`) governed by the following architectural choices:

1. **Local & Non-Production Profile Isolation:**
   Fault injection capabilities are strictly locked to non-production environments (`local`, `test`, `dev`). The `ChaosSafetyGuard` immediately aborts with a `SecurityException` if loaded under a production profile (`prod`).

2. **In-Memory Fault Emulation Over Host Mutation:**
   Upstream microservices (`orders-service`, `inventory-service`) expose profile-restricted `/fault/configure` and `/fault/reset` endpoints. Faults (delays, HTTP status codes, error counts) are simulated in memory within the process rather than terminating OS processes or dropping Docker containers (`docker stop`).

3. **Strict Target Allowlisting & Bounded Blast Radius:**
   Only Gatekeeper-owned local services on loopback (`localhost:8081`, `localhost:8082`, `localhost:6380`) may be targeted. External hosts, arbitrary URLs, and production databases are strictly rejected. Hard boundaries are enforced on request volume (max 500), concurrency (max 50), and experiment duration (max 60 seconds).

4. **Guaranteed 8-Stage Lifecycle & Try-Finally Cleanup:**
   Every scenario adheres to a deterministic 8-stage lifecycle:
   `PRECHECK -> BASELINE -> FAULT INJECTION -> OBSERVATION -> FAULT REMOVAL -> RECOVERY VERIFICATION -> CLEANUP -> REPORT GENERATION`.
   Cleanup is guaranteed to execute in a `finally` block even when experiments encounter unexpected runtime exceptions.

5. **Separation of Gateway Decision Latency vs. Client Round-Trip:**
   Internal circuit-breaker and bulkhead decisions operate in memory ($< 0.05\text{ ms}$), whereas complete client network HTTP round-trips encompass TCP handshakes and HTTP socket serialization ($\sim 2\text{--}15\text{ ms}$). Telemetry records both boundaries explicitly to prevent misleading metrics.

## Consequences

### Positive
- **Deterministic Reliability Evidence:** Real, measurable metrics (P50/P95/P99 latency, circuit breaker state transitions, bulkhead rejection counts, retry amplification ratios) suitable for technical verification and portfolio demonstration.
- **Zero Risk of Data Loss:** Databases (`gatekeeper-postgres`) and caches (`gatekeeper-redis`) remain untouched and safe throughout all experiments.
- **Fail-Closed Verification:** Formally proves that Gatekeeper denies traffic safely when Redis or upstream services are unavailable, preventing unauthorized quota bypass.
- **Audit Completeness:** Policy safety experiments prove that transactional rollbacks restore previous active versions without mutating immutable history.

### Negative / Trade-Offs
- Kernel-level packet drops (`iptables` / Linux `tc-netem`) are not simulated natively on Windows host loopback; socket delays and HTTP error codes serve as bounded software equivalents.
- Tests requiring actual Redis socket disconnects use test-isolated adapters rather than stopping the running host container.
