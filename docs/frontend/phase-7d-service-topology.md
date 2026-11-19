# Gatekeeper Phase 7D — Configured Service Topology Semantics & Health Propagation

## 1. Declarative Configured Topology vs. Dynamic Discovery

A core architectural principle in enterprise infrastructure control planes is distinguishing **configured architecture** from **empirically discovered nodes**:
- Gatekeeper's Service Topology view explicitly displays the **Configured Architecture** (derived from declarative route configurations, proxy upstreams, and registered infrastructure dependencies).
- It is clearly labeled as a **Static Declarative Graph** to avoid misleading operators into believing that dynamic service-mesh discovery (such as Consul or Eureka) is running when the gateway routes are statically bound in configuration.

---

## 2. Visual Architecture & SVG Topology Canvas

The interactive SVG topology canvas renders nodes and connections across distinct architectural tiers:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        CONFIGURED TOPOLOGY CANVAS                      │
├─────────────────┬──────────────────┬─────────────────┬─────────────────┤
│  Client Tier    │   Gateway Edge   │ Upstream Tier   │ Stateful Store  │
├─────────────────┼──────────────────┼─────────────────┼─────────────────┤
│                 │                  │ ┌─────────────┐ │                 │
│                 │                  ├►│   Orders    │ │                 │
│ ┌─────────────┐ │ ┌──────────────┐ │ └─────────────┘ │ ┌─────────────┐ │
│ │ API Clients ├─┼►│  Gatekeeper  ├─┤                 ├►│ PostgreSQL  │ │
│ └─────────────┘ │ │   Gateway    ├─┤                 │ └─────────────┘ │
│                 │ └──────────────┘ │ ┌─────────────┐ │ ┌─────────────┐ │
│                 │                  ├►│  Inventory  │ ├►│ Redis 7.2   │ │
│                 │                  │ └─────────────┘ │ └─────────────┘ │
└─────────────────┴──────────────────┴─────────────────┴─────────────────┘
```

### 2.1 Topology Node Directory & Roles
1. **API Clients (`CLIENT_ENTRY`)**:
   - Source of external HTTPS traffic and API key tokens.
   - Status: Bound to gateway ingress activity.
2. **Gatekeeper Gateway (`GATEWAY_EDGE`)**:
   - Central reverse proxy, policy engine, and rate limiter.
   - Host: Port 8080.
   - Status: Sourced directly from `/api/v1/health` (`UP` -> `HEALTHY`).
3. **Orders Microservice (`UPSTREAM_SERVICE`)**:
   - Target route: `orders-service-v1` (`/api/v1/proxy/orders/**`).
   - Upstream address: `http://orders:8081`.
   - Circuit breaker: Dedicated Resilience4j instance.
4. **Inventory Microservice (`UPSTREAM_SERVICE`)**:
   - Target route: `inventory-service-v1` (`/api/v1/proxy/inventory/**`).
   - Upstream address: `http://inventory:8082`.
   - Circuit breaker: Dedicated Resilience4j instance.
5. **PostgreSQL Database (`STATEFUL_DEPENDENCY`)**:
   - Persistence layer for consumers, API keys, subscription plans, and policy rules.
   - Port: 5432.
   - Status: Sourced from authenticated Spring Boot Actuator (`/actuator/health` -> `components.db`).
6. **Redis Cluster / Cache (`STATEFUL_DEPENDENCY`)**:
   - Distributed rate limiter state, token bucket counters, and stream events.
   - Port: 6379 / 6380.
   - Status: Sourced from authenticated Spring Boot Actuator (`/actuator/health` -> `components.redis`).

---

## 3. Real Health Data vs. UNKNOWN Fallback Handling

Gatekeeper strictly avoids fabricating synthetic health or latency for upstream services. In production and local environments:

### 3.1 Transparent Health Mapping Rules
1. **Gateway Node**:
   - Sourced from `GET /api/v1/health`.
   - If response `status === 'UP'`, marked as `HEALTHY` (Green).
   - If unreachable or network error, marked as `DOWN` (Red).
2. **Stateful Dependencies (PostgreSQL & Redis)**:
   - Sourced from `GET /actuator/health`.
   - If actuator reports `status: "UP"`, marked as `HEALTHY`.
   - If actuator endpoint is disabled, unreachable, or returns HTTP 401/403 (unauthenticated), marked explicitly as **`UNKNOWN`** (Slate / Gray).
3. **Upstream Microservices (Orders & Inventory)**:
   - Sourced from active route resilience state (`/api/v1/resilience/routes`):
     - Breaker `CLOSED` & failure rate < 10%: marked as `HEALTHY`.
     - Breaker `HALF_OPEN` or failure rate >= 10%: marked as `DEGRADED` (Amber).
     - Breaker `OPEN`: marked as `DOWN` (Red).
   - If no traffic has been proxied or resilience telemetry is unreachable, marked explicitly as **`UNKNOWN`**.

> **Design Guarantee**: No upstream service is ever displayed as `HEALTHY` without authentic verification telemetry.

---

## 4. Interactive Node Inspector Drawer

Clicking on any node in the SVG topology canvas opens the right-hand **Node Inspector Drawer**:
- Displays node ID, architectural role, address/port, protocol, and observed health.
- For upstream microservices, provides deep-linking to the **Resilience Workbench** focused on that specific route's circuit breaker and bulkhead metrics.
- SREs can jump directly to **Usage Analytics** to inspect real-time throughput, status code distributions, and access logs.
