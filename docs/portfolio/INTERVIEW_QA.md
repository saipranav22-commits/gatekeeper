# Gatekeeper — Deep Technical Interview Questions & Grounded Answers

This guide contains 22 in-depth architectural and engineering interview questions grounded directly in Gatekeeper's production codebase. Use these to master technical discussions with Principal Engineers, Staff Architects, and Hiring Managers.

---

### Q1: Why did you implement the Token Bucket rate limiter using a custom Redis Lua script rather than using client-side Guava RateLimiter or multi-step Redis commands (GET / INCR)?
**Answer:**
Client-side rate limiters (like Guava `RateLimiter`) store token state in JVM local memory. In a distributed multi-instance gateway cluster, this causes quota skew: if a tenant is allowed 1,000 req/min and you run 10 gateway instances, the tenant could theoretically consume 10,000 req/min if requests are round-robined.

Using multi-step Redis commands (e.g., `GET` tokens, compute in Java, then `SET` back to Redis) introduces severe **race conditions and network round-trip overhead**. Two concurrent requests from the same consumer would read identical token counts, compute identical decrements, and overwrite each other, allowing quota bypass.

Gatekeeper solves this by executing a single atomic **Redis Lua script (`rate_limiter.lua`)**:
1. Redis guarantees that Lua scripts execute atomically and run to completion without interruption from other commands.
2. The script computes token replenishment on-the-fly based on fractional elapsed timestamps:
   $$\text{replenished} = (\text{now} - \text{last\_replenished}) \times \text{refill\_rate}$$
3. The read, calculation, decrement, and TTL update occur in **a single Redis round-trip** with sub-millisecond execution time, completely eliminating distributed locks.

---

### Q2: What happens if Redis goes down or experiences extreme latency during rate limit checks? Does Gatekeeper fail-open or fail-closed?
**Answer:**
Gatekeeper's rate limit filter implements a configurable fail-safe strategy governed by system reliability requirements:
- **Default Production Policy (Fail-Open with Circuit Degradation)**: If Redis throws a connection timeout or connection refused exception, the gateway catches the exception, logs a high-severity alert, increments an actuator error counter, and permits the request through with a degraded header (`X-RateLimit-Degraded: true`). This prioritizes business availability over strict throttling, preventing a Redis transient outage from taking down the entire API ecosystem.
- **Strict Financial/Security Mode (Fail-Closed)**: For sensitive endpoints (such as high-value payment processing), Gatekeeper can be configured per route to fail-closed, returning HTTP `429 Too Many Requests` or `503 Service Unavailable` with a ProblemDetail indicating that rate limit validation is temporarily unavailable.

---

### Q3: Explain the state machine and sliding-window calculation of the Resilience4j Circuit Breaker in Gatekeeper.
**Answer:**
Gatekeeper configures Resilience4j Circuit Breakers with a count-based sliding window (default: 100 requests) and a minimum call threshold (default: 10 calls):
1. **`CLOSED` (Nominal)**: All proxied requests pass to downstream microservices. If the failure rate (HTTP 5xx or timeouts) exceeds the configured threshold (default: 50.0%) or slow call rate (> 2000ms) exceeds 75.0% over the sliding window, the circuit immediately trips to **`OPEN`**.
2. **`OPEN` (Short-Circuit)**: All incoming requests for that route fail fast immediately with a `CallNotPermittedException`. The gateway catches this and returns HTTP `503 Service Unavailable` without placing any network load on the struggling downstream service.
3. **`HALF-OPEN` (Probing)**: After a configured wait duration (`waitDurationInOpenState = 10000ms`), the circuit transitions to `HALF-OPEN`. It permits a small sample of probe calls (default: 10 requests).
   - If the failure rate of the probe calls is below 50%, the circuit automatically heals back to **`CLOSED`**.
   - If even a single critical failure occurs during probing, the circuit instantly drops back to **`OPEN`** for another full wait duration.

---

### Q4: Why did you choose Semaphore Bulkheads instead of Thread Pool Bulkheads for route isolation in Gatekeeper?
**Answer:**
In Spring Web / Gateway architectures, thread pool bulkheads create dedicated thread pools per upstream service. While this provides physical queueing, it introduces:
- Significant context-switching overhead between worker threads and bulkhead threads.
- Thread starvation risk when thread pools are misconfigured.
- Loss of `ThreadLocal` context (such as Spring Security context, MDC logging, and trace IDs) without complex context propagators.

Instead, Gatekeeper utilizes **Semaphore Bulkheads** (`Resilience4j Bulkhead`):
- Each route has a maximum permit allowance (e.g., 50 concurrent permits for Orders Service).
- Requests execute on the calling thread, simply acquiring a permit before dispatching the HTTP call and releasing it in a `finally` block upon completion.
- If all 50 permits are in use, subsequent requests fail immediately with `BulkheadFullException` (HTTP 429/503), protecting the gateway's core thread pool from being consumed by a hung upstream dependency, with virtually zero memory or CPU overhead.

---

### Q5: How does Gatekeeper prevent the "Thundering Herd" problem when a circuit breaker transitions from OPEN to HALF-OPEN?
**Answer:**
When an upstream service recovers and a circuit breaker moves from `OPEN` to `HALF-OPEN`, thousands of queued or waiting client requests could simultaneously flood the newly recovering service, immediately crashing it again. Gatekeeper mitigates this through three distinct mechanisms:
1. **Strict Permitted Number of Calls in Half-Open State**: Resilience4j limits probe requests to a small fixed number (e.g., 10 requests). Requests beyond this limit are rejected while probing is underway.
2. **Upstream Semaphore Bulkhead**: The route's concurrency cap remains strictly enforced even during half-open transitions.
3. **Exponential Backoff & Jitter**: Client SDKs and consumers are encouraged via `Retry-After` headers to add randomized jitter to retries, smoothing out traffic spikes.

---

### Q6: Walk through the end-to-end request lifecycle from an incoming API request to the downstream microservice.
**Answer:**
1. **Network Ingress**: Client sends `POST /api/v1/proxy/orders` with header `X-API-Key: gk_live_...`.
2. **CORS & Security Filter**: Validates origin, sets security headers (CSP, HSTS, X-Frame-Options).
3. **Authentication Filter (`ApiKeyFilter`)**: Extracts key prefix, looks up tenant in Redis cache (or PostgreSQL fallback), and verifies the SHA-256 hash using constant-time comparison.
4. **Rate Limit Filter**: Invokes `rate_limiter.lua` on Redis with the consumer's ID and plan tier. If quota is exhausted, returns HTTP 429 with `X-RateLimit-*` headers.
5. **Policy Engine (`PolicyFilter`)**: Evaluates the request against active AST rules (e.g., IP allowlists, geo-blocking, header checks). If violated, returns HTTP 403.
6. **Resilience & Proxy Router**:
   - Acquires Semaphore Bulkhead permit.
   - Verifies Circuit Breaker state (`CLOSED`).
   - Forwards request to downstream service (`http://localhost:8081/orders`).
7. **Response Interception**: Injects rate-limit headers into the upstream response and returns it to the client.
8. **Asynchronous Telemetry Dispatch**: Non-blocking dispatch of `UsageEvent` into the in-memory ring buffer for batch database persistence.

---

### Q7: How are API keys securely generated, stored, and verified without exposing plaintext secrets?
**Answer:**
1. **Generation**: Generated using Java's `SecureRandom` (CSPRNG), producing a 32-character high-entropy alphanumeric string prefixed by `gk_live_<tenant_slug>_`.
2. **Storage**: The plaintext key is **never** persisted in the database. Instead:
   $$\text{hash} = \text{SHA-256}(\text{plaintextKey})$$
   Only the hash, the key prefix (first 12 characters for indexing), metadata, and scopes are stored in PostgreSQL.
3. **Single-Reveal UX**: The plaintext secret is transmitted to the client only once in the HTTP `201 Created` response and rendered in the `OneTimeSecretModal`. Once dismissed, it is permanently erased from memory.
4. **Verification**: During an API call, Gatekeeper extracts the prefix to look up the expected hash, hashes the incoming key using SHA-256, and performs `MessageDigest.isEqual()` (constant-time byte comparison) to prevent timing side-channel attacks.

---

### Q8: Why was a custom explainable rule engine built for Policy Studio rather than using generic SpEL or JavaScript eval()?
**Answer:**
1. **Security (Remote Code Execution Prevention)**: Using SpEL (Spring Expression Language) or JavaScript `eval()` exposes the gateway to critical RCE vulnerabilities if operators or attackers input malicious expressions (e.g. `T(java.lang.Runtime).getRuntime().exec(...)`).
2. **Strict Determinism & Performance**: Gatekeeper's AST evaluator supports only safe, strongly-typed boolean predicates (`in`, `==`, `!=`, `>`, `<`) operating on known request context maps (`ip`, `headers`, `path`, `method`).
3. **Explainability & Auditing**: Generic expression engines return only a boolean `true/false`. Gatekeeper's AST engine records an exact, step-by-step decision trace explaining which sub-rule matched, why it was triggered, and the sub-millisecond evaluation duration, which is crucial for compliance and debugging.

---

### Q9: How does Gatekeeper ingest millions of usage analytics events without degrading proxy request latency?
**Answer:**
Gatekeeper implements an **Asynchronous Micro-Batch Ingestion Pipeline**:
- The gateway proxy handler publishes usage events to an in-memory queue / ring buffer (`LMAX Disruptor` or bounded `ConcurrentLinkedQueue`) in a non-blocking fashion.
- The HTTP response thread dispatches to the client immediately without waiting for database operations.
- A background worker (`UsageEventBatchConsumer`) drains the buffer in micro-batches (up to 1,000 events or every 500ms) and executes a single bulk JDBC batch insert (`COPY` or multi-value `INSERT`) into PostgreSQL.
- This reduces database IOPS and round-trips by over 95% while keeping proxy path latency strictly under 2ms.

---

### Q10: How does the Dead Letter Queue (DLQ) work for failed analytics events?
**Answer:**
If the database experiences a transient outage or connection pool exhaustion during a batch flush:
1. The batch consumer catches the `DataAccessException`.
2. The failed batch is serialized and routed to a dedicated in-memory / local disk-backed **Dead Letter Queue (`DeadLetterUsageEvent`)**.
3. A scheduled recovery task attempts backoff retries once database connectivity is restored.
4. If an event cannot be recovered after 5 retry attempts, it is logged to an audit quarantine file, ensuring zero loss of billing and audit data.

---

### Q11: How does Gatekeeper achieve zero data leakage between live API integration and the standalone recruiter demo mode?
**Answer:**
1. **Build-Time Inversion of Control**: Gatekeeper uses Vite environment variables (`VITE_APP_MODE=demo`).
2. **Service Layer Abstraction**: Every UI view consumes a high-level service interface (`apiKeyService`, `resilienceService`, etc.).
3. **Strict Isolation**: When running in demo mode, the application delegates calls to `src/demo/services/*`, which query pure in-memory mock datasets. Live Axios HTTP clients and endpoints are not invoked, and tree-shaking prevents live API credentials or endpoints from leaking into public static builds.

---

### Q12: Explain the dynamic visual severity hierarchy designed for the dark Obsidian Titanium theme.
**Answer:**
In many dark-themed dashboards, triggering an error state turns the entire screen red or flashes high-contrast neon backgrounds, causing visual fatigue and destroying legibility.

Gatekeeper solves this using a **Localized Severity Hierarchy**:
- **Baseline Theme**: Retains deep Obsidian Slate (`#0B0F19`) background and Titanium Charcoal cards (`#131B2E`) across all states.
- **Incident Scoping**: In `UNHEALTHY` mode, only the specifically failing component (e.g. Orders Service route card, circuit status pill, and 5xx counter) flashes crimson (`#EF4444`).
- **Success Preserved**: Successful metrics (e.g. 2xx success counter, unaffected Inventory service) remain green (`#10B981`), providing immediate visual contrast that accurately conveys partial degradation rather than total collapse.

---

### Q13: How did you ensure Gitleaks scanning in CI does not produce false positives on harmless demo mock keys?
**Answer:**
Standard secret scanners flag any string matching API key patterns (e.g., `gk_live_[a-z0-9_]{32}`).
In Gatekeeper, demo data requires realistic mock keys to ensure UI fidelity without triggering CI failures:
1. Custom `.gitleaks.toml` rules specify explicit path exclusions for `frontend/src/demo/**` and designated test fixtures.
2. Synthetic keys adhere to explicit demo namespaces (`gk_live_demo_...`) that are documented and recognized by rule exceptions.
3. A secret detection regression test verifies that if a *real* production format secret is accidentally committed to an unexempted file, Gitleaks immediately fails the build.

---

### Q14: What are the trade-offs of using custom Spring Boot proxy controllers vs Spring Cloud Gateway?
**Answer:**
- **Spring Cloud Gateway (Reactive / WebFlux)**: Highly efficient for pure I/O streaming, but reactive programming (Project Reactor / Mono / Flux) increases debugging complexity, complicates thread-local security context propagation, and makes synchronous JPA / JDBC access prone to accidental blocking.
- **Custom Spring Boot Proxy (Servlet / Virtual Threads)**: Offers crystal-clear step-by-step pipeline control, seamless integration with Spring Data JPA and standard Spring Security, and simple imperative code that is easy to reason about and audit. On Java 21, virtual threads achieve high concurrency with traditional synchronous programming models.

---

### Q15: How does Gatekeeper enforce database isolation and schema migrations across development and production?
**Answer:**
1. **Flyway / Liquibase Migrations**: Database schema changes are version-controlled via immutable SQL migration scripts.
2. **Isolated Storage**: Local development binds PostgreSQL storage to isolated host directories (`D:\Development\database-data\gatekeeper`), preventing collisions with system databases.
3. **Dedicated Application Credentials**: The application connects using a least-privilege user (`gatekeeper_app`) rather than the postgres superuser.

---

### Q16: Explain the token refill calculation formula in the Redis Lua script.
**Answer:**
$$\text{elapsed\_ms} = \max(0, \text{current\_time\_ms} - \text{last\_replenished\_ms})$$
$$\text{tokens\_to\_add} = \text{elapsed\_ms} \times \frac{\text{capacity}}{\text{window\_ms}}$$
$$\text{new\_tokens} = \min(\text{capacity}, \text{current\_tokens} + \text{tokens\_to\_add})$$

If $\text{new\_tokens} \ge \text{cost}$ (usually 1):
- Deduct $\text{cost}$, update `current_tokens` and set `last_replenished_ms = current_time_ms`.
- Reset key TTL to ensure automatic cache expiration for inactive keys.
- Return `[1, new_tokens - cost, reset_seconds]`.

---

### Q17: How is tenant plan upgrade or downgrade handled dynamically without restarting the gateway or clearing caches?
**Answer:**
When an administrator modifies a consumer's plan (e.g., Starter to Enterprise):
1. The update is persisted in PostgreSQL with an incremented version number.
2. Gatekeeper evicts the tenant's cached metadata key from Redis.
3. Upon the very next incoming request, the gateway re-fetches the updated tier limits and passes the new capacity and refill rate directly to the Lua script.
4. The Lua script immediately clamps the current token balance to the new capacity, achieving instant plan activation with zero downtime.

---

### Q18: What measures protect Gatekeeper's admin API against brute-force credential stuffing?
**Answer:**
1. **IP Sliding-Window Rate Limiting (`LoginRateLimiter`)**: In-memory and Redis-backed tracking limits failed login attempts to 5 per 15 minutes per IP.
2. **Exponential Backoff**: Subsequent attempts return HTTP 429 with `Retry-After`.
3. **Constant-Time Verification**: Password verification uses BCrypt/Argon2 with calibrated work factors to prevent timing attacks.

---

### Q19: How did you design the automated chaos testing harness to validate empirical resilience metrics?
**Answer:**
The chaos harness (`services/orders-service/fault` and `verify-chaos-lab.ps1`) allows programmatic fault injection:
- Configures synthetic latency (e.g., 2,500ms delay) or percentage error injection (e.g., 100% HTTP 500).
- Dispatches automated concurrent load using PowerShell/Node scripts.
- Verifies that:
  1. Orders circuit breaker transitions from `CLOSED` to `OPEN` within 100 calls.
  2. Gateway latency drops back to < 5ms as requests fail-fast with 503 fallback.
  3. Inventory service throughput and latency remain 100% unaffected.

---

### Q20: If Gatekeeper were deployed across multiple geographic cloud regions, how would you evolve the rate limiting and cache consistency model?
**Answer:**
In a multi-region deployment (e.g., US-East, US-West, EU-Central):
1. **Partitioned Local Buckets (Local Quota Splitting)**: Divide the tenant's global quota across regions (e.g., 60% US-East, 40% EU-Central) based on historical traffic. Each region manages its own local Redis cluster with zero inter-region latency.
2. **Periodic Asynchronous Reconciliation**: Regions exchange aggregate token consumption metrics every 1-2 seconds via Kafka or Redis streams to dynamically balance quotas.
3. **Global Fail-Safe**: In case of WAN partition, each region falls back to local baseline quotas, ensuring high availability over global synchronization locks.

---

### Q21: How does the frontend maintain strict type-safety across polymorphic resilience status responses?
**Answer:**
Gatekeeper defines polymorphic TypeScript union types in `src/types/resilience.ts`:
```typescript
export type CircuitBreakerState = 'CLOSED' | 'OPEN' | 'HALF_OPEN' | 'DISABLED' | 'METRICS_ONLY';
export type IncidentSeverity = 'nominal' | 'warning' | 'critical' | 'recovering';
```
Type guard functions inspect `circuitBreakerState` and `currentFailureRate` to guarantee exhaustive type-checking in React components, preventing runtime `undefined` property crashes.

---

### Q22: How do you prevent horizontal overflow and responsive layout degradation on narrow mobile devices (320px)?
**Answer:**
1. **Mobile-First CSS**: Built with Tailwind CSS using responsive prefixes (`sm:`, `md:`, `lg:`).
2. **Defensive Layout Constraints**: Tables wrap inside `overflow-x-auto`, cards use `min-w-0` to prevent flex children from bursting container widths, and text truncation (`truncate` / `break-words`) prevents long UUIDs from causing overflow.
3. **Automated Viewport Verification**: Real Chrome headless runs test 320px viewport resolution asserting `document.documentElement.scrollWidth <= window.innerWidth`.
