# Gatekeeper Container Deployment Architecture

## Overview

Gatekeeper is packaged as an isolated, containerized multi-tier distributed gateway platform. The production topology guarantees strict separation of concerns, defense-in-depth network isolation, zero-downtime resilience, and minimal resource footprint suitable for low-overhead production or demonstration environments.

```mermaid
flowchart TD
    subgraph Public_Internet["Public / Client Ingress"]
        Browser["User Browser / Client API Consumer"]
    end

    subgraph Host_System["Host Machine / Cloud VM"]
        subgraph Ports["Ingress Ports"]
            Port3000["Port 3000 (HTTP)"]
            Port8080["Port 8080 (HTTP Gateway)"]
        end

        subgraph Docker_Network["Docker Network: gatekeeper-deploy-network (bridge)"]
            subgraph Frontend_Tier["Frontend Tier"]
                NginxContainer["gatekeeper-deploy-frontend (Nginx 1.27 Alpine)<br/>• SPA Static Routing<br/>• Reverse Proxy /api/ to Gateway<br/>• Security Headers & Gzip"]
            end

            subgraph Gateway_Tier["Core Gateway & Policy Tier"]
                GatewayContainer["gatekeeper-deploy-gateway (Temurin 21 JRE)<br/>• Port 8080<br/>• JWT & API Key Auth Filters<br/>• Token Bucket Rate Limiting<br/>• Policy Simulation & Execution<br/>• Resilience (Circuit Breakers / Retries)"]
            end

            subgraph Upstream_Services["Upstream Microservices Tier"]
                OrdersService["gatekeeper-deploy-orders (Port 8081)<br/>• Order Management Domain"]
                InventoryService["gatekeeper-deploy-inventory (Port 8082)<br/>• Inventory Management Domain"]
            end

            subgraph Data_Storage["Data & State Tier"]
                PostgresContainer["gatekeeper-deploy-postgres (PostgreSQL 16.8 Alpine)<br/>• Port 5432 (Internal)<br/>• Flyway Migrations (V1-V9)<br/>• Named Volume: gatekeeper_deploy_pgdata"]
                RedisContainer["gatekeeper-deploy-redis (Redis 7.4 Alpine)<br/>• Port 6379 (Internal)<br/>• Password Authenticated<br/>• AOF Persistence & Redis Streams<br/>• Named Volume: gatekeeper_deploy_redisdata"]
            end
        end
    end

    Browser -->|Web Traffic| Port3000
    Browser -->|Direct API Calls| Port8080
    Port3000 --> NginxContainer
    Port8080 --> GatewayContainer
    NginxContainer -->|Reverse Proxy /api/| GatewayContainer
    GatewayContainer -->|Proxy Routing & SSRF Safe| OrdersService
    GatewayContainer -->|Proxy Routing & SSRF Safe| InventoryService
    GatewayContainer -->|Flyway Migrations & JPA Queries| PostgresContainer
    GatewayContainer -->|Token Bucket & Quota State| RedisContainer
```

---

## Container Topologies and Roles

### 1. Frontend Ingress (`gatekeeper-deploy-frontend`)
- **Base Image**: `nginx:1.27-alpine`
- **Internal Port**: 80
- **External Port**: `${FRONTEND_PORT:-3000}`
- **Responsibilities**:
  - Serves static assets compiled by Vite with immutable caching headers.
  - SPA Fallback routing via `try_files $uri $uri/ /index.html`.
  - Terminates or forwards API requests under `/api/` directly to `http://gateway:8080/api/` via container-internal DNS.
  - Enforces HTTP security headers (`X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy`).

### 2. Core Gateway & Policy Engine (`gatekeeper-deploy-gateway`)
- **Base Image**: `eclipse-temurin:21-jre-alpine`
- **Internal Port**: 8080
- **External Port**: `${GATEWAY_PORT:-8080}`
- **Responsibilities**:
  - Intercepts, authenticates, and inspects all inbound requests using OAuth2/JWT and Scoped API Key filters.
  - Enforces sub-millisecond Redis token-bucket rate limiting with deterministic fail-closed guarantees.
  - Runs the declarative policy engine (DENY, ALLOW_CONTINUE, RATELIMIT_OVERRIDE, HEADER_MUTATION).
  - Handles circuit breaker state transitions, retries with jitter, timeouts, and bulkheads.
  - Operates under `SPRING_PROFILES_ACTIVE=prod` with all chaos engineering endpoints completely excised from the runtime bean registry.

### 3. Upstream Microservices (`gatekeeper-deploy-orders`, `gatekeeper-deploy-inventory`)
- **Base Image**: `eclipse-temurin:21-jre-alpine`
- **Internal Ports**: `8081` (Orders), `8082` (Inventory)
- **External Ports**: None (internal network only; shielded from external internet ingress).
- **Responsibilities**:
  - Serve business domain entities (Orders, Inventory items).
  - Validated by Gatekeeper SSRF Protection Filter (`gatekeeper.routing.allowed-hosts: [orders, inventory]`).

### 4. Relational Data Tier (`gatekeeper-deploy-postgres`)
- **Base Image**: `postgres:16.8-alpine`
- **Internal Port**: 5432
- **External Ports**: Unmapped in production for zero-exposure security.
- **Persistence**: Named volume `gatekeeper_deploy_pgdata`.
- **Flyway Migrations**: Applied automatically on gateway startup (V1 through V9).

### 5. High-Throughput State Tier (`gatekeeper-deploy-redis`)
- **Base Image**: `redis:7.4-alpine`
- **Internal Port**: 6379
- **External Ports**: Unmapped in production.
- **Persistence**: Append-Only File (`appendonly yes`, `appendfsync everysec`) stored in named volume `gatekeeper_deploy_redisdata`.
- **Security**: Mandatory `requirepass` authentication.

---

## Network Architecture & Isolation

- **Network Name**: `gatekeeper-deploy-network` (`driver: bridge`).
- **Isolation Boundary**: All inter-service communication (PostgreSQL, Redis, Orders, Inventory, Gateway) occurs exclusively within this virtual bridge.
- **Host Port Exposure**: Only the Frontend (`:3000`) and Gateway (`:8080`) are exposed to the host interface. Database and Redis ports are never published to external host ports.
- **Coexistence Guarantee**: Operates completely disjoint from local development containers (`gatekeeper-postgres` on host `5434`, `gatekeeper-redis` on host `6380`), eliminating any port or volume collision.
