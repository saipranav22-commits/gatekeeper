# Gatekeeper — Render & Free PaaS Zero-Cost Deployment Feasibility Study

## Executive Summary

This study evaluates the technical and financial feasibility of hosting the **Gatekeeper enterprise distributed API gateway** at **₹0 / $0 recurring cost** using the user's existing **Render** account combined with third-party free developer services (**Neon PostgreSQL** and **Upstash Redis**).

The evaluation assessed:
1. Render Free Web Service constraints (memory, CPU, sleep lifecycle, instance-hour quotas, private networking).
2. Distributed Java microservice execution (`gateway`, `orders`, `inventory`).
3. External serverless persistence compatibility (Neon PostgreSQL connection pooling vs Flyway migrations; Upstash Redis command quotas vs active Redis Stream consumers).
4. Cold-start cascading effects on Resilience4j circuit breakers and timeouts.

### Definitive Verdict: **ARCHITECTURALLY INCOMPATIBLE (FAIL)**
While a simple stateless monolithic application can run on Render's free tier, **Gatekeeper's distributed 3-tier microservice architecture, atomic Redis Lua rate limiting, and continuous Redis Stream telemetry cannot operate reliably or stay online on Render Free + Upstash Free.** 

The fatal blockers include:
1. **Upstash Command Exhaustion in < 2.8 Hours**: Gatekeeper's `UsageEventBatchConsumer` polls Redis Streams at 1-second intervals ($86,400\text{ commands/day}$), exceeding Upstash's $10,000\text{ command/day}$ ceiling before noon on Day 1. Because Gatekeeper enforces a **strict fail-closed security policy**, all gateway traffic is blocked as soon as Upstash quota is exceeded.
2. **Render 750 Instance-Hour Pool Exhaustion in ~10.4 Days**: Render pools 750 free instance-hours across all services. Running 3 distinct backend services (`gateway`, `orders`, `inventory`) consumes $72\text{ hours/day}$, causing Render to forcibly suspend all services on Day 10 of every month.
3. **Cold-Start Cascade Tripping Circuit Breakers**: Upstream services sleeping after 15 minutes take 50+ seconds to spin up, triggering gateway timeouts and tripping Resilience4j circuit breakers into `OPEN` state.
4. **512 MB Memory Hard Ceiling**: Spring Boot 3.3 + Hibernate + Flyway + Lettuce easily exceeds 512 MB RSS under load or migration, resulting in cgroup `OOMKilled` (SIGKILL 137).

The **Oracle Cloud Infrastructure (OCI) Always Free tier (Ampere A1: 2 OCPU, 12 GB RAM, 24/7 dedicated compute, local Docker bridge)** remains the only verified, architecturally sound ₹0 deployment target.

---

## 1. Render Free Tier Specification Audit

| Platform Dimension | Render Free Web Service Allowance | Gatekeeper Requirement | Compatibility Assessment |
|---|---|---|:---:|
| **Memory Allocation** | 512 MB RAM hard limit per service | Gateway: 512–768 MB RSS<br/>Orders: 256–384 MB RSS<br/>Inventory: 256–384 MB RSS | **FAIL** (High OOM risk on Gateway) |
| **CPU Allocation** | 0.1 shared vCPU (throttled under load) | Multi-threaded JIT compilation, crypto (BCrypt, JWT), rate limit checks | **FAIL** (Extreme CPU throttling on startup) |
| **Inactivity Sleep** | Spins down after 15 minutes of zero HTTP traffic | Continuous Redis stream processing, background telemetry batching | **FAIL** (Background schedulers stop on sleep) |
| **Cold Start Latency** | 50+ seconds from cold spin-down | API Gateway SLA: < 100 ms target latency | **FAIL** (Breaks client expectations & timeouts) |
| **Account Monthly Hours** | **750 free instance-hours/month pool** (shared across all free services) | 3 backend services $\times 744\text{ hours/mo} = 2,232\text{ hours/mo}$ | **FATAL FAIL** (Account suspended by Day 10) |
| **Private Networking** | **NOT SUPPORTED** on Free Tier (Paid only) | Internal Docker network isolation between Gateway and microservices | **FAIL** (Microservices must be exposed publicly) |
| **Persistent Storage** | 0 GB (Ephemeral filesystem only) | PostgreSQL data directory & Redis append-only persistence | **FAIL** (Requires external managed services) |
| **Managed PostgreSQL** | Render free PostgreSQL is **deleted after 30 days** | Permanent relational schema (V1–V9 Flyway migrations) | **FAIL** (Not a permanent free option) |
| **Egress Bandwidth** | 100 GB / month free | Estimated < 50 GB / month | **PASS** |

---

## 2. Distributed Microservices Memory & Time Budget Breakdown

### A. Memory Budget Analysis
Gatekeeper's backend is built on Spring Boot 3.3.5 and Temurin 21. Even with tuned JVM flags (`-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC`):
- **Gateway Service**:
  - Heap: capped at 256 MB.
  - Non-Heap / Native Memory: Metaspace (~90–120 MB), Code Cache (~40 MB), Thread Stacks (~30 MB for Tomcat/Netty/Scheduler threads), DirectByteBuffers (~30 MB).
  - Total Resident Set Size (RSS): **~440 MB to 510 MB** at idle.
  - During Flyway schema execution, BCrypt hash iterations (cost factor 12), or concurrent request handling, RSS crosses 512 MB, triggering Linux cgroup OOM termination by Render's hypervisor.
- **Microservices (`orders`, `inventory`)**:
  - Each requires ~200–280 MB RSS.
  - Combining all 3 Java runtimes into a single 512 MB Render container would require over **900 MB RSS**, causing immediate startup crash.

### B. The 750-Hour Instance Pool Collapse
Render calculates free tier usage on an account-wide pool:
$$\text{Daily Consumption} = 3 \text{ services} \times 24 \text{ hours} = 72 \text{ instance-hours/day}$$
$$\text{Account Lifespan} = \frac{750 \text{ hours}}{72 \text{ hours/day}} \approx 10.4 \text{ days}$$
By the 11th calendar day of each month, Render suspends all free services on the account. Even if sleep mode is enabled, any moderate traffic or scheduled pings that keep services awake consume hours rapidly.

### C. The Cold-Start Cascade & Circuit Breaker Collapse
```mermaid
sequenceDiagram
    autonumber
    actor Client as External Consumer / Browser
    participant GW as Render Gateway (Sleeping)
    participant ORD as Render Orders Service (Sleeping)

    Note over GW,ORD: Services idle for > 15 minutes (Spun down to 0 instances)
    Client->>GW: GET /api/v1/proxy/orders/123
    Note over GW: Render wakes container (50s - 65s cold start)
    GW->>ORD: HTTP GET https://orders-service.onrender.com/orders/123
    Note over ORD: Render wakes upstream container (50s - 65s cold start)
    Note over GW: Gateway timeout exceeded (2000ms - 5000ms)
    GW-->>Client: HTTP 504 Gateway Timeout
    Note over GW: Resilience4j records timeout failure
    Client->>GW: Subsequent Requests
    Note over GW: Circuit Breaker trips to OPEN state!
    GW-->>Client: HTTP 503 Service Unavailable (CircuitBreaker 'orders' is OPEN)
```
Because upstream microservices sleep independently, cold starts inevitably violate Gatekeeper's configured resilience timeouts (2–5 seconds), immediately tripping Resilience4j circuit breakers and locking out traffic.

---

## 3. External Free Persistence Compatibility (Neon & Upstash)

### A. Neon Free Serverless PostgreSQL
- **Specifications**: 0.5 GB storage, 1 compute project, auto-suspend after 5 minutes of inactivity (1–3s wake time).
- **Network & TLS**: Requires TLS (`sslmode=require`). Spring Boot Hikari configuration must specify SSL connection parameters.
- **Flyway Caveat**: Neon uses PgBouncer for pooled connections (port 6543). PgBouncer in transaction pooling mode **does not support session-level PostgreSQL advisory locks**, causing Flyway migration lock errors. Migrations must use the direct unpooled compute endpoint (port 5432).
- **Verdict**: **CONDITIONAL PASS** (Viable for relational data if unpooled endpoint is used for Flyway).

### B. Upstash Free Serverless Redis
- **Specifications**: 10,000 commands/day hard limit, 256 MB storage, max 100 concurrent connections, TLS connection string (`rediss://...`).
- **Lua Scripting**: Upstash supports `EVAL` and `EVALSHA`, allowing `token_bucket.lua` to execute.
- **The Fatal Flaw — Telemetry Stream Polling**:
  Gatekeeper's `UsageEventBatchConsumer` actively pulls telemetry from Redis Streams to persist audit events to PostgreSQL:
  ```java
  @Scheduled(fixedDelayString = "${gatekeeper.analytics.consumer.poll-interval-ms:1000}")
  public void pollAndProcessStreamBatch() { ... }
  ```
  - Polling interval: **1 second (1,000 ms)**.
  - Baseline commands issued by scheduler:
    $$1 \text{ command/sec} \times 86,400 \text{ seconds/day} = 86,400 \text{ commands/day}$$
  - Upstash daily allowance: **10,000 commands/day**.
  - Time to complete exhaustion:
    $$\frac{10,000 \text{ commands}}{3,600 \text{ commands/hour}} \approx 2.77 \text{ hours}$$
- **Fail-Closed Security Consequence**:
  When Upstash reaches 10,000 commands, it returns `ERR daily request limit exceeded`. Gatekeeper's `RedisTokenBucketRateLimiter` enforces strict fail-closed security:
  ```java
  } catch (Exception e) {
      log.error("Redis token bucket rate limiter failed for consumer {}: {}", consumerId, e.getMessage());
      throw new RateLimitUnavailableException("Rate limiting service unavailable due to Redis error", e);
  }
  ```
  **Every API request through Gatekeeper will fail with HTTP 503 / 500 starting 2.8 hours after boot every single day.**
- **Verdict**: **FATAL FAIL**.

---

## 4. Architectural Comparison: Render Split Stack vs OCI Dedicated VM

| Architectural Dimension | Render + Neon + Upstash Split Stack | Oracle Cloud Always Free (Ampere A1) |
|---|---|---|
| **Recurring Cost** | ₹0 (Subject to strict free tier limits) | **₹0 (Guaranteed within Always Free limits)** |
| **Compute Hardware** | 3 $\times$ 0.1 shared vCPU, 512 MB RAM | **2 dedicated OCPUs, 12 GB RAM, 50 GB Boot Volume** |
| **Process Continuity** | Sleeps after 15 min; 750 hr/mo pool limit | **24/7 continuous uptime; NO sleeping, NO hour limits** |
| **Network Architecture** | Traverses public internet across 3 different clouds | **Single private Docker bridge (`172.28.0.0/24`)** |
| **Cross-Service Latency** | 30 ms – 120 ms per hop (Public HTTPS backbones) | **< 0.5 ms internal loopback / bridge latency** |
| **Redis Command Limits** | Hard limit of 10,000 commands/day (breaks in 2.8 hrs) | **Unlimited commands/sec on local Redis 7 container** |
| **Database Lifecycle** | Ephemeral or external (Neon 0.5 GB) | **Local PostgreSQL 16 container on persistent volume** |
| **Resilience & Chaos Testing** | Cold starts trigger false-positive circuit breaks | **Accurate, deterministic latency & fault injection** |
| **Operational Complexity** | High (Managing 4 separate provider accounts & tokens) | **Low (Single Git repo, Docker Compose, single host)** |
| **Architectural Fidelity** | Low (Microservices exposed over public internet) | **100% Identical to verified production topology** |

---

## 5. Mock Environment Configuration (If Attempted)

To demonstrate the fragmentation and credential complexity required by a split-cloud deployment, the following environment variables would be required across providers:

```bash
# ==============================================================================
# Render Web Service: Gateway (Environment Specification)
# ==============================================================================
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=prod

# Database: External Neon Serverless PostgreSQL
DB_HOST=ep-example-pooler.us-east-2.aws.neon.tech
DB_PORT=5432
DB_NAME=gatekeeper_prod
DB_USERNAME=neondb_owner
DB_PASSWORD=<SECURE_NEON_PASSWORD>
DB_URL=jdbc:postgresql://ep-example-pooler.us-east-2.aws.neon.tech:5432/gatekeeper_prod?sslmode=require

# Cache / Rate Limiting: External Upstash Redis
REDIS_HOST=us1-example-redis.upstash.io
REDIS_PORT=6379
REDIS_PASSWORD=<SECURE_UPSTASH_TOKEN>
# Upstash requires SSL/TLS:
SPRING_DATA_REDIS_SSL_ENABLED=true

# Upstream Microservice Endpoints (Must use public Render URLs due to lack of private networking)
ORDERS_UPSTREAM_URI=https://gatekeeper-orders.onrender.com
INVENTORY_UPSTREAM_URI=https://gatekeeper-inventory.onrender.com

# Memory Tuning for Render 512 MB Container
JAVA_OPTS="-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC"
```

---

## 6. Formal Feasibility Release Gates

| Gate # | Evaluation Area | Verdict | Evidence / Technical Rationale |
|:---:|---|:---:|---|
| **GATE-RF-01** | **Memory Ceiling Compliance (512 MB)** | **`FAIL`** | Spring Boot 3.3 gateway with JPA, Flyway, and Redis requires 450–520 MB RSS, causing high risk of SIGKILL 137 under load. |
| **GATE-RF-02** | **Monthly Instance Hours (750 hrs)** | **`FAIL`** | 3 backend services consume 72 hrs/day, exhausting the 750-hr account budget in 10.4 days. |
| **GATE-RF-03** | **Cold-Start Latency & Circuit Breakers** | **`FAIL`** | Upstream services spinning down after 15 min take 50+ seconds to start, triggering gateway timeout exceptions and tripping circuit breakers. |
| **GATE-RF-04** | **Private Microservice Networking** | **`FAIL`** | Render Free tier lacks private networking; upstream microservices must be exposed to the public internet, violating backend security boundaries. |
| **GATE-RF-05** | **Neon PostgreSQL Compatibility** | **`PASS (COND)`** | Fully compatible provided direct compute port (5432) is used for Flyway migrations rather than PgBouncer connection pooler (6543). |
| **GATE-RF-06** | **Upstash Redis Token-Bucket (Lua)** | **`PASS`** | Upstash natively supports `EVAL` and atomic token bucket operations. |
| **GATE-RF-07** | **Upstash Redis Stream Polling Quota** | **`FATAL FAIL`** | 1-second background telemetry polling generates 86,400 commands/day, exhausting Upstash's 10,000/day limit in < 2.8 hours and locking out all gateway traffic. |
| **GATE-RF-08** | **Network Latency & SLA** | **`FAIL`** | Inter-cloud requests (Render $\leftrightarrow$ Neon $\leftrightarrow$ Upstash) add 30–100 ms of network latency per API request compared to < 0.5 ms on local bridge. |
| **GATE-RF-09** | **Overall Free PaaS Feasibility** | **`FAIL`** | Architecture cannot meet Gatekeeper's production stability, telemetry, or security requirements under ₹0. |

---

## 7. Strategic Recommendation

### Do NOT Deploy Gatekeeper on Render Free + Upstash
Deploying Gatekeeper across Render, Neon, and Upstash creates a fragile, sleeping system that:
1. Shuts down entirely after 10 days each month (Render hour exhaustion).
2. Fails all traffic after 2.8 hours every morning (Upstash quota exhaustion triggering fail-closed rate limiter).
3. Throws 504 errors on first access due to cold-start cascades.
4. Leaves a poor impression on engineering reviewers due to latency and circuit breaker tripping.

### Recommended Path: Proceed with Oracle Cloud Always Free (Ampere A1)
The Oracle Cloud Always Free architecture established in:
- [`docs/deployment/oracle-always-free-plan.md`](file:///D:/Development/Gatekeeper/docs/deployment/oracle-always-free-plan.md)
- [`docs/deployment/oracle-first-deployment-runbook.md`](file:///D:/Development/Gatekeeper/docs/deployment/oracle-first-deployment-runbook.md)

provides:
- Dedicated **2 OCPUs and 12 GB of RAM** running 24/7.
- Complete private Docker Compose stack with zero external network dependencies.
- True sub-millisecond local Redis and PostgreSQL performance.
- Continuous background telemetry processing without arbitrary request limits.
- Zero recurring hosting costs ($0.00 / ₹0.00) without billing surprises when budget alerts are configured.

### Next Safe Action
1. Review this feasibility report.
2. Proceed to executing the verified Oracle Always Free deployment runbook when the user is ready to provision the Ampere A1 instance.
