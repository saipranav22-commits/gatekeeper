# Gatekeeper Production Readiness & Operational Checklist

This document details the hardening principles, resource ergonomics, and operational controls implemented in Gatekeeper for production environments.

---

## 1. Security Hardening & Zero-Chaos Posture

### A. Strict Profile Exclusion
- **Defect Neutralization**: In earlier iterations, `@Profile({"default", "local", "test", "dev"})` allowed chaos engineering controllers and fault injection endpoints to remain active when no profile was specified.
- **Production Hardening**: The `"default"` profile has been completely excised across all 16 chaos and fault injection classes in `backend`, `orders-service`, and `inventory-service`.
- **Default Behavior**: Running without `--spring.profiles.active` loads standard production defaults where zero chaos beans exist in the Spring application context.
- **Access Lockout**: Attempting to invoke `/api/v1/chaos/**` or `/fault-injection/**` in production yields `HTTP 404 Not Found`. `ChaosSafetyGuard.validateEnvironment()` additionally enforces programmatic rejection (`SecurityException`) if invoked outside explicit `local`, `test`, or `dev` profiles.

### B. Non-Root Container Execution
All custom production images enforce non-privileged execution:
- **Backend & Microservices**: Execute as `appuser:appgroup` (UID/GID `10001:10001`).
- **Filesystem Permissions**: The application binary and working directory (`/app`) are read-only to non-root processes except for necessary temporary execution spaces.
- **Nginx Frontend**: Alpine-based minimal distribution with strict root directory permissions.

### C. Secret Separation & Fail-Closed Guards
- No secrets are baked into container images or checked into source control.
- All credentials (`POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `GATEKEEPER_JWT_SECRET`) are strictly supplied via runtime environment variables.
- Redis rate limiting operates on a deterministic **fail-closed** policy: if Redis becomes unavailable, high-risk unauthenticated or unmetered traffic is denied rather than allowing quota bypasses.
- Gatekeeper's routing engine enforces strict **SSRF validation**: all target hostnames must belong to the explicitly configured `gatekeeper.routing.allowed-hosts` set.

---

## 2. JVM Resource Ergonomics & Low-Memory Tuning

### The Constrained Host Challenge
In developer workstations and budget cloud instances (such as a 7.5 GB laptop or a 2 GB / 4 GB VM), Java processes can trigger operating system paging exhaustion (e.g., Windows error 1455 `ERROR_COMMITMENT_LIMIT` or Linux `OOMKiller`).

### Implemented JVM Mitigations

1. **CGroup-Aware RAM Percentage**:
   ```bash
   -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0
   ```
   Instead of hardcoding `-Xmx` values that cause container crashes when container memory limits change, the JVM dynamically calculates its heap based on the container cgroup memory limit.

2. **Immediate Fail-Fast on Allocation Failure**:
   ```bash
   -XX:+ExitOnOutOfMemoryError
   ```
   Prevents the JVM from entering unresponsive thrashing states when native memory or heap is exhausted. Docker or the container orchestrator immediately restarts the faulted container.

3. **Garbage Collection Optimization**:
   ```bash
   -XX:+UseG1GC
   ```
   G1GC provides predictable pause times, compacts memory to prevent fragmentation, and promptly uncommits unused heap back to the OS.

4. **Fast Secure Entropy**:
   ```bash
   -Djava.security.egd=file:/dev/./urandom
   ```
   Prevents blocking during cryptographic key generation on headless Linux hosts.

### Container Sizing Budget (Total ~2.4 GB)

| Container | Memory Limit | Memory Reservation | CPU Limit | Rationale |
|---|---|---|---|---|
| `gateway` | 768 MB | 384 MB | 1.0 | Core routing, rate limiting, and JPA operations. |
| `orders` | 384 MB | 192 MB | 0.5 | Domain microservice; in-memory data store. |
| `inventory` | 384 MB | 192 MB | 0.5 | Domain microservice; in-memory data store. |
| `postgres` | 512 MB | 256 MB | 0.5 | Relational persistence with moderate connection pool. |
| `redis` | 256 MB | 128 MB | 0.5 | In-memory token bucket and stream buffers. |
| `frontend` | 128 MB | 64 MB | 0.5 | Nginx static asset serving and reverse proxying. |
| **Total Stack** | **2,432 MB (~2.4 GB)** | **1,216 MB (~1.2 GB)** | **3.5 CPUs** | Safely coexists within constrained host environments. |

---

## 3. Healthchecks & Self-Healing

Every service defines explicit container health checks:
- **Postgres**: `pg_isready -U gatekeeper_app -d gatekeeper`
- **Redis**: `redis-cli -a "$REDIS_PASSWORD" ping`
- **Gateway**: `wget -qO- http://localhost:8080/actuator/health`
- **Orders**: `wget -qO- http://localhost:8081/orders`
- **Inventory**: `wget -qO- http://localhost:8082/inventory`
- **Frontend**: `wget -qO- http://localhost:80/healthz`

The compose specification leverages `depends_on: { service: { condition: service_healthy } }` to ensure strict startup sequencing: Gateway never attempts Flyway migrations before PostgreSQL is healthy, and the Frontend never accepts traffic before the Gateway is ready.
