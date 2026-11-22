# Gatekeeper — System Architecture & Engineering Blueprint

This document details the architectural design, distributed algorithms, data flows, and security boundaries of Gatekeeper.

---

## 1. Executive Architecture Overview

Gatekeeper is an enterprise API Gateway and Intelligent Traffic Management Plane engineered in Java 21 (Spring Boot 3) and TypeScript (React 18). It delivers distributed rate limiting, tenant quota enforcement, circuit breaking, policy-based traffic governance, and sub-millisecond telemetry.

```mermaid
flowchart TD
    subgraph Clients["API Consumers & Administrators"]
        ClientApps["Client Applications\n(X-API-Key: gk_live_...)"]
        AdminBrowser["Admin Control Plane\n(Bearer JWT / Standalone Demo)"]
    end

    subgraph Edge["Gatekeeper Edge Gateway (Port 8080)"]
        CorsSecurity["Security & CORS Filter"]
        AuthFilter["Authentication Filter\n(JWT & API-Key Verification)"]
        RateLimiter["Token-Bucket Rate Limiter\n(Atomic Redis Lua Script)"]
        PolicyEngine["Versioned Policy Engine\n(AST Rule Evaluation)"]
        ResilienceEngine["Resilience4j Circuit Breakers\n& Semaphore Bulkheads"]
        ProxyRouter["Dynamic Reverse Proxy\n(Spring Web Routing)"]
        AsyncTelemetry["Asynchronous Event Pipeline\n(Micro-Batch Ingestion)"]
    end

    subgraph DataStore["State & Cache Layer"]
        RedisCluster[("Redis 7 (Port 6380)\nToken Buckets & Fast Auth Cache")]
        PostgresDB[("PostgreSQL 16 (Port 5434)\nTenants, Keys, Policies, Audit")]
    end

    subgraph UpstreamServices["Protected Downstream Services"]
        OrdersSvc["Orders Microservice (Port 8081)\n/orders"]
        InventorySvc["Inventory Microservice (Port 8082)\n/inventory"]
    end

    ClientApps -->|HTTP Requests| Edge
    AdminBrowser -->|Management REST APIs| Edge

    CorsSecurity --> AuthFilter
    AuthFilter --> RateLimiter
    RateLimiter --> PolicyEngine
    PolicyEngine --> ResilienceEngine
    ResilienceEngine --> ProxyRouter

    RateLimiter <-->|Atomic EVALSHA Lua| RedisCluster
    AuthFilter <-->|Cached Tenant Lookup| RedisCluster
    AuthFilter -.->|Fallback Read| PostgresDB
    ProxyRouter -->|Proxied Request| UpstreamServices

    Edge -.->|Async Event Queue| AsyncTelemetry
    AsyncTelemetry -->|Micro-Batch Inserts| PostgresDB
```

---

## 2. End-to-End Request Processing Lifecycle

Every HTTP request entering Gatekeeper traverses a deterministic pipeline designed for maximum throughput and predictable fail-safe behavior.

```mermaid
sequenceDiagram
    autonumber
    actor Client as API Consumer
    participant Gateway as Gatekeeper Gateway
    participant Redis as Redis Cache & Lua
    participant Policy as Policy Engine
    participant Circuit as Resilience4j Circuit Breaker
    participant Upstream as Orders Microservice
    participant Pipeline as Async Analytics Pipeline
    participant DB as PostgreSQL

    Client->>Gateway: POST /api/v1/proxy/orders (X-API-Key)
    Gateway->>Gateway: Authenticate API Key Prefix & Hash
    Gateway->>Redis: Check Token Bucket (rate_limiter.lua)
    
    alt Tokens Available
        Redis-->>Gateway: Allowed: 1, Remaining: 49, Reset: 12s
        Gateway->>Policy: Evaluate Request AST Rules
        
        alt Policy Allowed
            Policy-->>Gateway: ALLOW (Rule ID: rule-standard-traffic)
            Gateway->>Circuit: Acquire Semaphore Bulkhead Permission
            
            alt Circuit CLOSED & Bulkhead Available
                Circuit->>Upstream: Forward Proxied Request
                Upstream-->>Circuit: 201 Created (Order Confirmed)
                Circuit-->>Gateway: Return Upstream Response
                Gateway-->>Client: 201 Created + RateLimit Headers
            else Circuit OPEN or Bulkhead Full
                Circuit-->>Gateway: CallNotPermittedException / BulkheadFullException
                Gateway-->>Client: 503 Service Unavailable (Fallback Response)
            end
            
        else Policy Denied
            Policy-->>Gateway: DENY (Condition: IP not in allowlist)
            Gateway-->>Client: 403 Forbidden (Policy Violation)
        end
        
    else Tokens Exhausted
        Redis-->>Gateway: Allowed: 0, Remaining: 0, Reset: 4s
        Gateway-->>Client: 429 Too Many Requests (Retry-After: 4)
    end

    Gateway-)Pipeline: Publish UsageEvent asynchronously
    Pipeline->>DB: Batch Insert Events (Non-blocking)
```

---

## 3. Distributed Token-Bucket Rate Limiter (Redis Lua)

### 3.1 Algorithm Design
Gatekeeper utilizes an atomic **Token Bucket Algorithm** implemented entirely within a single Redis Lua script (`rate_limiter.lua`). This eliminates race conditions, network round-trips, and lock contention.

```mermaid
flowchart TD
    Start["Incoming Request for Consumer Key"] --> ReadState["Redis Lua Script reads:\n- last_replenished_timestamp\n- current_tokens\n- bucket_capacity\n- refill_rate_per_sec"]
    ReadState --> ComputeRefill["Calculate Time Elapsed (now - last_replenished)\nTokens to add = elapsed_time * refill_rate"]
    ComputeRefill --> UpdateTokens["New Tokens = min(capacity, current_tokens + tokens_to_add)"]
    UpdateTokens --> CheckAvailable{"New Tokens >= requested_cost (1)?"}
    
    CheckAvailable -->|Yes| Decrement["Tokens Remaining = New Tokens - 1\nUpdate Redis Key & Set TTL\nAllowed = 1"]
    CheckAvailable -->|No| Reject["Allowed = 0\nCalculate Time to Next Available Token"]
    
    Decrement --> ReturnAllowed["Return [allowed=1, remaining, reset_seconds]"]
    Reject --> ReturnDenied["Return [allowed=0, remaining=0, retry_after_seconds]"]
```

### 3.2 Key Invariants & Edge Case Handling
1. **Zero Race Conditions**: Redis single-threaded script execution guarantees atomic read-compute-write cycles without distributed locks.
2. **Clock Drift Resilience**: Timestamps are passed from the gateway application or derived via Redis `TIME` command to avoid desynchronization across client instances.
3. **Graceful Fail-Open vs Fail-Closed**:
   - Production configuration supports configurable failure modes. If Redis is unreachable, the gateway enters safe degraded mode, logging alerts while preventing catastrophic cascade outages.
4. **Adaptive Tier Upgrades**: When an administrator updates a tenant's plan tier, the Redis key capacity and refill rate are updated on the next token check without requiring cache flushes.

---

## 4. Distributed Resilience & Fault Isolation

Gatekeeper embeds Resilience4j to protect both the gateway and downstream microservices from cascading failures.

### 4.1 Circuit Breaker Architecture
```mermaid
stateDiagram-v2
    [*] --> CLOSED: Initial Healthy State
    CLOSED --> OPEN: Failure Rate > 50% (Sliding Window: 100 calls)
    OPEN --> HALF_OPEN: Wait Duration Elapsed (10,000 ms)
    HALF_OPEN --> CLOSED: Probe Calls Success Rate >= 50%
    HALF_OPEN --> OPEN: Single Probe Call Fails
```

- **Sliding Window**: Count-based or time-based sliding window measuring failure rate and slow call rate (> 2000 ms).
- **Automatic Fallback**: Returns structured ProblemDetail (503 Service Unavailable) with cacheable fallback payload where applicable.
- **Manual Override**: Administrators can manually reset any circuit breaker via `/api/v1/resilience/routes/{routeId}/reset`.

### 4.2 Semaphore Bulkhead Isolation
Each upstream route (e.g., Orders Service vs. Inventory Service) is assigned an independent **Semaphore Bulkhead** (default: 50 concurrent executions). If an outage or latency spike occurs in Orders Service:
- Orders Service exhausts its 50 permits and fails fast with `429/503`.
- **Inventory Service remains 100% unaffected** with its own 50 permits available.
- Thread resources in the Gatekeeper gateway are never starved.

---

## 5. Explainable Policy Studio & Governance Engine

Gatekeeper includes an extensible policy evaluation engine designed to govern API traffic based on dynamic rules, client IP, geographic location, headers, and request methods.

```mermaid
flowchart LR
    RuleStore[("Versioned Rule Store\nPostgreSQL")] --> ASTBuilder["Abstract Syntax Tree\n(AST Compiler)"]
    ASTBuilder --> ShadowMode["Shadow Evaluation\n(Evaluates without blocking)"]
    ASTBuilder --> LiveEngine["Live Enforcement Engine\n(Priority-Ordered Rules)"]
    LiveEngine --> DecisionTrace["Explainable Decision Trace\n(Rule ID, Expression, Result)"]
    DecisionTrace --> AuditLog[("Immutable Audit Trail\nPostgreSQL")]
```

### 5.1 Key Capabilities
- **Explainable Decisions**: Every policy evaluation generates a full decision trace detailing which rules matched, which failed, and the exact sub-millisecond evaluation duration.
- **Versioned Immutability**: Policy versions are strictly immutable. Updates create new version drafts that can be activated or rolled back instantly.
- **Shadow Mode**: New rule sets can run in shadow mode alongside production traffic, logging simulated verdicts without altering production responses.

---

## 6. High-Throughput Asynchronous Analytics Ingestion

To achieve high throughput without adding latency to the critical proxy path, Gatekeeper separates request serving from usage event logging.

```mermaid
flowchart LR
    ProxyHandler["Gateway Proxy Handler\n(Request Complete)"] -->|Non-blocking enqueue| InMemBuffer["Ring Buffer / Event Publisher\n(LMAX Disruptor / Memory Queue)"]
    InMemBuffer --> BatchConsumer["Micro-Batch Consumer\n(Flush every 500ms or 1000 events)"]
    BatchConsumer --> BulkInsert["PostgreSQL Bulk COPY / JDBC Batch Insert"]
    BulkInsert --> PostgresTable[("usage_events Table\n(Time-Partitioned)")]
    BulkInsert -.->|On DB Error| DLQ[("Dead Letter Queue\nFile / Retried Ingestion")]
```

- **Zero Critical-Path Latency**: Proxy response is dispatched to the client immediately after upstream response completes; logging is fully asynchronous.
- **Micro-Batching**: Database writes are batched in groups of up to 1,000 events or 500ms windows, reducing database IOPS by over 95%.
- **Dead-Letter Safety**: Any unwriteable events are preserved in a local dead-letter buffer for automated recovery.

---

## 7. Standalone Recruiter Demo Isolation Architecture

Gatekeeper features a standalone, interactive recruiter demonstration mode embedded directly within the Vite/React frontend.

```mermaid
flowchart TD
    subgraph UI["React 18 Control Plane"]
        Pages["Overview, Analytics, Consumers, Plans, Keys, Policy, Reliability, Chaos"]
        HealthContext["GatewayHealthContext\n(HEALTHY | DEGRADED | UNHEALTHY | RECOVERING)"]
    end

    subgraph ServiceLayer["Service Abstraction Layer"]
        AppMode{"APP_MODE == 'demo'?"}
        LiveServices["Live Axios Services\n(Calls /api/v1/* via HTTP)"]
        DemoServices["In-Memory Demo Services\n(Local Simulated Datastores)"]
    end

    subgraph DemoEngine["Demo State & Scenarios"]
        Scenarios["Deterministic Health Scenarios:\n- HEALTHY (100% nominal)\n- DEGRADED (Orders 42.5% failure)\n- UNHEALTHY (Orders circuit OPEN)\n- RECOVERING (HALF-OPEN probe)"]
    end

    Pages --> ServiceLayer
    ServiceLayer --> AppMode
    AppMode -->|false| LiveServices
    AppMode -->|true| DemoServices
    DemoServices <--> DemoEngine
    HealthContext --> DemoEngine
```

### 7.1 Zero-Leak Security & Isolation Guarantees
- **Zero Cloud Network Calls**: When `VITE_APP_MODE=demo`, no external HTTP or WebSocket connections are initiated.
- **Bundle Isolation**: Tree-shaking and environment variable branching ensure demo code cannot leak production credentials.
- **Authentic Fidelity**: Demo services utilize the identical TypeScript DTO types, schemas, and timing behaviors as the live backend services.

---

## 8. Security & Threat Modeling

| Threat Vector | Mitigation Strategy | Architectural Implementation |
| :--- | :--- | :--- |
| **API Key Compromise** | Zero-trust hashed storage | Keys generated with CSPRNG; stored as SHA-256 hashes. Plaintext shown once upon creation. |
| **Credential Stuffing** | IP-based sliding window rate limiter | `LoginRateLimiter` restricts admin login attempts to 5 per 15 minutes per IP. |
| **DDoS & Traffic Spikes** | Token bucket rate limiting & bulkheads | Upstream services protected by per-tenant rate limits and isolated semaphore bulkheads. |
| **Cascading Upstream Failures** | Resilience4j Circuit Breakers | Fails fast when upstream failure rate exceeds threshold, returning 503 fallback. |
| **Man-in-the-Middle** | TLS 1.3 & HSTS | Strict HTTPS enforcement with modern cipher suites and CSP headers. |
| **Secret Leakage in Git** | Automated Gitleaks scanning | Pre-commit and CI automated secret scanners enforce zero plaintext credentials. |
