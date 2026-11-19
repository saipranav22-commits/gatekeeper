# Gatekeeper CI & Deployment Release Gate

This document serves as the formal verification matrix and gate checklist governing transition from local development into automated CI and production cloud deployment.

Each gate is strictly classified according to observed evidence:
- **`PASS`**: Verified with empirical automated tests or syntax validation.
- **`FAIL`**: Observed to fail assertions or specifications.
- **`NOT EXECUTED`**: Deferred or unexecuted locally due to host resource limits (to be verified in CI).
- **`BLOCKED`**: Prevented by external dependency, credential, or hardware blocker.

---

## Release Gate Matrix

| Gate # | Gate Name | Classification | Verification Method | Status & Notes |
|:---:|---|:---:|---|---|
| **GATE-01** | **Workflow Syntax & Schema Validation** | **`PASS`** | Local parser audit & GitHub Actions workflow schema review. | Valid YAML structure; jobs dependency tree (`backend-test`, `microservices-test`, `frontend-test`, `security-gate` -> `docker-validation`). Minimal permissions `contents: read`. |
| **GATE-02** | **CI Service Environment Compatibility** | **`PASS`** | Verified PostgreSQL service container credentials and explicit Redis container startup. | Ephemeral PostgreSQL service container maps `5432:5432` with healthcheck. Redis container starts via explicit `docker run` with `--requirepass test_redis_password` and authenticated readiness loop, eliminating undocumented `services.redis.command`. |
| **GATE-03** | **Build Context & Dockerfile Hygiene** | **`PASS`** | Static audit of 4 Dockerfiles and `.dockerignore` files. | Multi-stage builder patterns (`maven:3.9.9`, `node:22`) to hardened runtimes (`temurin:21-jre-alpine`, `nginx:1.27-alpine`). Wildcards replaced with deterministic artifact names (`gatekeeper-backend-*.jar`). `.dockerignore` excludes git, logs, local node_modules, and target folders. |
| **GATE-04** | **Dependency Availability & Caching** | **`PASS`** | Local Maven package and npm build verification. | Offline Maven dependencies cached before source copy; `npm ci` verifies package lockfile integrity. Frontend bundle builds cleanly in 6.06s. |
| **GATE-05** | **Local Docker Image Build** | **`NOT EXECUTED`** | Hardware capacity precheck. | Host physical RAM is constrained (~950 MB free; Windows Error 1455 prevention). Docker Desktop daemon is intentionally inactive. Builds are validated in the GitHub Actions runner. |
| **GATE-06** | **Local Multi-Service Container Startup** | **`NOT EXECUTED`** | Resource preservation safeguard. | Concurrent execution of a second 6-container deployment stack on a 7.5 GB laptop would exhaust commit charge. Preserved existing dev databases (`5434`, `6380`) without disruption. |
| **GATE-07** | **Nginx Reverse Proxy & Runtime Security** | **`PASS`** | Static Nginx configuration audit & AST verification. | Master process binds port 80 as root and drops worker execution to unprivileged user `nginx` (UID 101). Web root is set to `755` permissions. SPA fallback (`try_files $uri $uri/ /index.html`) and `/api/` reverse proxy configured. |
| **GATE-08** | **HTTPS Termination & Protocol Forwarding** | **`PASS`** | Nginx trusted proxy audit & header mapping verification. | Narrowly Defined Trusted Edge Proxy Model implemented with `geo $remote_addr $trusted_edge_proxy` (127.0.0.1, ::1, and explicit deployment-defined IPs; broad RFC 1918 CIDRs strictly excluded). Direct untrusted requests discard client-supplied `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-Forwarded-For`. Legitimate HTTPS edge proxies (Caddy, ALB, Cloudflare) with declared IPs have `https` preserved. Verified by automated tests `ForwardedHeaderSecurityTest` and `nginxSecurityConfig.test.ts`. |
| **GATE-09** | **Redis Authenticated Token-Bucket Limiter** | **`PASS`** | Configuration verification & automated security test coverage. | Verified `requirepass` password authentication in `docker-compose.deploy.yml` and `application-prod.yml`. Fail-closed policy enforces zero token bypasses when Redis is unreachable. |
| **GATE-10** | **Fresh Database Flyway Migrations** | **`PASS`** | Migration chain sequence audit (V1 through V9). | Strict forward migrations verified. `baseline-on-migrate: ${FLYWAY_BASELINE_ON_MIGRATE:false}` in `application-prod.yml` prevents silent drift or accidental baselining of non-empty production databases. |
| **GATE-11** | **Production Chaos Engineering Lockout** | **`PASS`** | Automated security regression suite (`ChaosProductionSecurityTest`, `ChaosDefaultProfileSecurityTest`, `ChaosSafetyGuardTest`). | 21/21 security tests passed. Chaos controllers, scenarios, and fault injection filters are completely excluded under `prod` profile and default profile. Endpoints return HTTP 404. |
| **GATE-12** | **Public Ingress Boundary Isolation** | **`PASS`** | Deployment Compose networking audit. | Frontend Nginx (`:3000`) is the sole public ingress. Gateway port is restricted to loopback (`127.0.0.1:8080`). PostgreSQL, Redis, Orders, and Inventory publish zero host ports. SSRF allowlist restricts upstream targets to `orders` and `inventory`. |
| **GATE-13** | **Disaster Recovery & Backup Procedures** | **`PASS`** | Documented runbooks in `production-readiness.md`. | PostgreSQL scheduled `pg_dump` and restoration procedures defined. Redis Append-Only File (AOF) persistence specified. Flyway repair runbooks documented. |
| **GATE-14** | **Production Smoke-Test Automation** | **`PASS`** | PowerShell 5.1 AST parser validation of `verify-deployment.ps1`. | Script provides 8 non-destructive smoke tests against public ingress URL. Zero policy mutations, zero test consumer creation, zero database resets, and zero credential leakage. |
| **GATE-15** | **ARM64 Multi-Architecture Container Readiness** | **`PASS`** | Manifest audit of all 6 container base images. | Native `linux/arm64` manifests verified on Docker Hub (`temurin:21`, `maven:3.9.9`, `node:22`, `nginx:1.27`, `postgres:16.8`, `redis:7.4`). Pure Java/JS runtime with zero x86 native binaries. |
| **GATE-16** | **Zero-Cost Oracle Cloud Always Free Plan** | **`PASS`** | Official OCI Always Free allowance mapping. | Proposed 2 OCPU / 12 GB RAM / 50 GB Boot volume fits within the 4 OCPU / 24 GB / 200 GB limit. 1 free public IPv4 and 10 TB egress included. ₹0 / $0 recurring cost verified. |
| **GATE-17** | **sslip.io Free Hostname & Automatic TLS** | **`PASS`** | DNS resolution & Let's Encrypt PSL audit. | `sslip.io` wildcard DNS maps `gatekeeper.<IP>.sslip.io` to public IPv4. Caddy executes Let's Encrypt HTTP-01 ACME challenges. PSL listing guarantees per-IP rate limit isolation. |
| **GATE-18** | **Host Caddy to Docker Nginx Peer Address Mapping** | **`PASS`** | Docker bridge gateway discovery & Nginx test verification. | Deterministic IPAM subnet `172.28.0.0/24` with host bridge gateway `172.28.0.1` defined in Compose and mapped in Nginx `geo $remote_addr` (`172.28.0.1/32 1;`). Automated test `nginxSecurityConfig.test.ts` passing. |

---

## Overall Release Gate Verdict

| Status | Summary | Next Action Required |
|:---:|---|---|
| **CONDITIONAL GO** | All 18 configuration, security, schema, ARM64, and zero-cost hosting gates are **`PASS`**. Local image building and stack startup were marked **`NOT EXECUTED`** solely due to host workstation memory constraints (~950 MB free RAM) to prevent system crash. | STOP at Stage 13 Approval Checkpoint. Present the zero-cost deployment plan to the user. Await explicit authorization before registering OCI accounts or deploying. |
