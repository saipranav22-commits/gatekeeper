# Gatekeeper — REST API Specification & Contract Reference

This document provides the authoritative, exhaustive specification for Gatekeeper's REST APIs, management endpoints, downstream proxy contracts, and microservice integration interfaces.

---

## 1. Global API Standards

### 1.1 Base URLs
- **Local Gateway Management API**: `http://localhost:8080/api/v1`
- **Local Gateway Proxy Route**: `http://localhost:8080/api/v1/proxy`
- **Orders Microservice (Direct)**: `http://localhost:8081`
- **Inventory Microservice (Direct)**: `http://localhost:8082`

### 1.2 Authentication Schemes
- **Administrator Endpoints (`/api/v1/**` except public health/auth)**:
  - Header: `Authorization: Bearer <JWT_ACCESS_TOKEN>`
  - Format: Standard RFC 7519 JWT signed with HMAC-SHA256 (`HS256`).
- **Downstream Consumer Proxy Calls (`/api/v1/proxy/**`)**:
  - Header: `X-API-Key: <gk_live_...>`
  - Key Format: `gk_live_<tenant_prefix>_<32_random_alphanumeric>`
  - Key Verification: Constant-time comparison against SHA-256 hash in database with Redis tier cache.

### 1.3 Standard Response Headers
Every proxied request through Gatekeeper includes real-time rate limit headers:
| Header | Description | Example |
| :--- | :--- | :--- |
| `X-RateLimit-Limit` | Total token bucket capacity per replenishment window | `1000` |
| `X-RateLimit-Remaining` | Remaining tokens in current token bucket | `842` |
| `X-RateLimit-Reset-Seconds`| Seconds until bucket reaches full capacity | `14` |
| `X-Gateway-Route-Id` | Identifier of matched upstream route | `route-orders-service` |
| `X-Circuit-Breaker-State` | Current status of upstream circuit breaker | `CLOSED` |

### 1.4 Standard Error Response Format
All 4xx and 5xx responses comply with RFC 7807 (Problem Details for HTTP APIs):
```json
{
  "type": "https://gatekeeper.dev/errors/rate-limit-exceeded",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Token bucket quota exhausted for consumer 'enterprise-retail-app'. Limit: 1000 req/min.",
  "instance": "/api/v1/proxy/orders",
  "timestamp": "2026-11-22T04:00:00Z",
  "errorCode": "RATE_LIMIT_EXCEEDED"
}
```

---

## 2. Authentication API (`/api/v1/auth`)

### 2.1 Administrator Login
- **Endpoint**: `POST /api/v1/auth/login`
- **Access**: Public (protected by brute-force sliding rate limiter: max 5 failures per 15 minutes per IP)
- **Request Body**:
```json
{
  "username": "admin",
  "password": "Password123!"
}
```
- **Response `200 OK`**:
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresInSeconds": 86400,
  "username": "admin",
  "role": "ROLE_SUPER_ADMIN"
}
```
- **Errors**:
  - `401 Unauthorized`: Invalid credentials.
  - `429 Too Many Requests`: IP temporarily blocked due to brute-force detection.

### 2.2 Change Password
- **Endpoint**: `POST /api/v1/auth/change-password`
- **Access**: Authenticated Admin (`ROLE_ADMIN` or `ROLE_SUPER_ADMIN`)
- **Request Body**:
```json
{
  "currentPassword": "Password123!",
  "newPassword": "NewSecurePassword456!"
}
```
- **Response `204 No Content`**

---

## 3. Consumer Management API (`/api/v1/consumers`)

### 3.1 List All Consumers
- **Endpoint**: `GET /api/v1/consumers`
- **Access**: Authenticated Admin
- **Query Parameters**:
  - `page` (optional, default: `0`): Zero-based page index.
  - `size` (optional, default: `20`): Page size.
  - `status` (optional): Filter by `ACTIVE`, `SUSPENDED`, or `PENDING`.
- **Response `200 OK`**:
```json
[
  {
    "id": "c7a8b9c0-1111-2222-3333-444455556666",
    "name": "Acme Global Logistics",
    "description": "Primary enterprise ERP integration",
    "tier": "ENTERPRISE",
    "status": "ACTIVE",
    "createdAt": "2026-08-15T10:00:00Z",
    "activeKeysCount": 3,
    "rateLimitPerMinute": 10000,
    "burstCapacity": 15000
  }
]
```

### 3.2 Get Consumer By ID
- **Endpoint**: `GET /api/v1/consumers/{consumerId}`
- **Response `200 OK`**: Single consumer object with detailed metadata and active plan configuration.

### 3.3 Create New Consumer
- **Endpoint**: `POST /api/v1/consumers`
- **Request Body**:
```json
{
  "name": "Nova FinTech Corp",
  "description": "High-frequency payment processing client",
  "planId": "tier-enterprise-01"
}
```
- **Response `201 Created`**: Created consumer object with generated UUID.

### 3.4 Update Consumer Plan Tier
- **Endpoint**: `PATCH /api/v1/consumers/{consumerId}/plan`
- **Request Body**:
```json
{
  "planId": "tier-unlimited-01"
}
```
- **Response `200 OK`**: Updated consumer object. Note: Redis rate limit token bucket capacity updates dynamically upon next token refill calculation.

### 3.5 Suspend / Reactivate Consumer
- **Endpoint**: `PATCH /api/v1/consumers/{consumerId}/suspend`
- **Endpoint**: `PATCH /api/v1/consumers/{consumerId}/reactivate`
- **Response `200 OK`**: Suspended consumers immediately receive `403 Forbidden` on all proxied API key requests.

### 3.6 Delete Consumer
- **Endpoint**: `DELETE /api/v1/consumers/{consumerId}`
- **Response `204 No Content`**: Cascades soft-deletion or key revocation.

---

## 4. API Key Lifecycle API (`/api/v1/consumers/{consumerId}/keys`)

### 4.1 Provision Scoped API Key (Single-Reveal Plaintext)
- **Endpoint**: `POST /api/v1/consumers/{consumerId}/keys`
- **Request Body**:
```json
{
  "name": "Orders Service Production Key",
  "scopes": ["orders:read", "orders:write", "inventory:read"],
  "expiresAt": "2027-01-01T00:00:00Z"
}
```
- **Response `201 Created`**:
```json
{
  "id": "k8b9c0d1-2222-3333-4444-555566667777",
  "consumerId": "c7a8b9c0-1111-2222-3333-444455556666",
  "name": "Orders Service Production Key",
  "keyPrefix": "gk_live_acme_",
  "plaintextKey": "gk_live_acme_7f9a8b2c4d6e8f0a1b2c3d4e5f6a7b8c",
  "scopes": ["orders:read", "orders:write", "inventory:read"],
  "status": "ACTIVE",
  "createdAt": "2026-11-22T04:00:00Z",
  "expiresAt": "2027-01-01T00:00:00Z",
  "warning": "Store this key securely. It will never be displayed again."
}
```
*Security Invariant*: `plaintextKey` is returned **only once** upon creation. The database persists solely the SHA-256 hash.

### 4.2 List API Keys for Consumer
- **Endpoint**: `GET /api/v1/consumers/{consumerId}/keys`
- **Response `200 OK`**: Returns array of keys with `keyPrefix` (e.g. `gk_live_acme_****`) and status. `plaintextKey` is omitted.

### 4.3 Revoke API Key
- **Endpoint**: `POST /api/v1/consumers/{consumerId}/keys/{keyId}/revoke`
- **Response `200 OK`**: Key status changes to `REVOKED`. Evicts key from Redis validation cache within <10ms.

---

## 5. Subscription Plans & Quota API (`/api/v1/plans`)

### 5.1 List Subscription Plans
- **Endpoint**: `GET /api/v1/plans`
- **Response `200 OK`**:
```json
[
  {
    "id": "tier-free",
    "name": "Starter",
    "description": "Developer exploration and sandboxing",
    "requestsPerMinute": 60,
    "burstCapacity": 100,
    "priceMonthly": 0.00,
    "features": ["Standard latency", "Community support", "1 Active API Key"]
  },
  {
    "id": "tier-pro",
    "name": "Professional",
    "description": "Production applications with sustained workloads",
    "requestsPerMinute": 1000,
    "burstCapacity": 2000,
    "priceMonthly": 299.00,
    "features": ["99.9% SLA guarantee", "Dedicated Redis token bucket", "5 Active API Keys"]
  },
  {
    "id": "tier-enterprise",
    "name": "Enterprise",
    "description": "High-throughput mission-critical distributed systems",
    "requestsPerMinute": 10000,
    "burstCapacity": 20000,
    "priceMonthly": 1499.00,
    "features": ["99.99% SLA guarantee", "Sub-millisecond token checks", "Unlimited API Keys", "Custom Policy Rules"]
  }
]
```

---

## 6. Policy Studio & Governance Engine API (`/api/v1/policies`)

### 6.1 List Policies
- **Endpoint**: `GET /api/v1/policies`
- **Response `200 OK`**: List of policy definitions including active versions and rule counts.

### 6.2 Get Policy Details
- **Endpoint**: `GET /api/v1/policies/{policyKey}`
- **Response `200 OK`**: Complete policy metadata, active version AST rules, conditions, and action definitions.

### 6.3 Create New Policy Version
- **Endpoint**: `POST /api/v1/policies/{policyKey}/versions`
- **Request Body**:
```json
{
  "versionNumber": 2,
  "description": "Enforce strict IP CIDR restriction and regional payload validation",
  "rules": [
    {
      "id": "rule-ip-allowlist",
      "condition": "request.ip in ['10.0.0.0/8', '192.168.1.0/24']",
      "action": "ALLOW",
      "priority": 100
    },
    {
      "id": "rule-geo-blocking",
      "condition": "request.headers['CF-IPCountry'] in ['XX', 'YY']",
      "action": "DENY",
      "priority": 200
    }
  ]
}
```

### 6.4 Activate Policy Version
- **Endpoint**: `POST /api/v1/policies/{policyKey}/versions/{versionNumber}/activate`
- **Response `200 OK`**: Promotes version to production active state with immutable audit logging.

### 6.5 Rollback Policy Version
- **Endpoint**: `POST /api/v1/policies/{policyKey}/rollback`
- **Request Body**: `{ "targetVersion": 1 }`
- **Response `200 OK`**: Instantly switches active evaluation tree back to previous verified version.

### 6.6 Dry-Run Simulation (Explainable Decision Trace)
- **Endpoint**: `POST /api/v1/policies/{policyKey}/simulate`
- **Request Body**:
```json
{
  "testPayload": {
    "ip": "198.51.100.42",
    "headers": {
      "X-Consumer-Tier": "FREE",
      "CF-IPCountry": "US"
    },
    "path": "/api/v1/proxy/orders",
    "method": "POST"
  }
}
```
- **Response `200 OK`**:
```json
{
  "decision": "DENY",
  "matchedRuleId": "rule-ip-allowlist",
  "evaluationDurationMs": 0.45,
  "decisionTrace": [
    {
      "ruleId": "rule-ip-allowlist",
      "conditionEvaluated": "request.ip in ['10.0.0.0/8', '192.168.1.0/24']",
      "result": false,
      "effect": "DENIED (IP not within corporate CIDR range)"
    }
  ]
}
```

### 6.7 Policy Governance Audit History
- **Endpoint**: `GET /api/v1/policies/{policyKey}/audit`
- **Response `200 OK`**: Append-only list of administrative actions, timestamps, and actor IDs.

---

## 7. Resilience & Reliability Engine API (`/api/v1/resilience`)

### 7.1 Get Resilience Status for All Routes
- **Endpoint**: `GET /api/v1/resilience/routes`
- **Response `200 OK`**:
```json
[
  {
    "routeId": "route-orders-service",
    "serviceName": "Orders Service",
    "upstreamUrl": "http://localhost:8081",
    "circuitBreakerState": "CLOSED",
    "failureRateThreshold": 50.0,
    "slowCallRateThreshold": 75.0,
    "slowCallDurationThresholdMs": 2000,
    "slidingWindowSize": 100,
    "minimumNumberOfCalls": 10,
    "currentFailureRate": 1.2,
    "currentSlowCallRate": 0.4,
    "bufferedCalls": 100,
    "failedCalls": 1,
    "slowCalls": 0,
    "bulkheadMaxConcurrent": 50,
    "bulkheadAvailableConcurrent": 48
  },
  {
    "routeId": "route-inventory-service",
    "serviceName": "Inventory Service",
    "upstreamUrl": "http://localhost:8082",
    "circuitBreakerState": "CLOSED",
    "failureRateThreshold": 50.0,
    "currentFailureRate": 0.0,
    "bulkheadMaxConcurrent": 50,
    "bulkheadAvailableConcurrent": 50
  }
]
```

### 7.2 Reset Circuit Breaker for Route
- **Endpoint**: `POST /api/v1/resilience/routes/{routeId}/reset`
- **Response `200 OK`**: Resets circuit breaker state to `CLOSED` and clears failure rate sliding buffer.

---

## 8. Usage Analytics API (`/api/v1/analytics`)

### 8.1 KPI Overview
- **Endpoint**: `GET /api/v1/analytics/overview`
- **Query Parameters**:
  - `range` (default: `24h`): Time range (`1h`, `24h`, `7d`, `30d`).
- **Response `200 OK`**:
```json
{
  "totalRequests": 1845200,
  "successfulRequests": 1836124,
  "rateLimitedRequests": 8412,
  "blockedByPolicyRequests": 412,
  "upstreamFailureRequests": 252,
  "averageLatencyMs": 14.2,
  "p95LatencyMs": 38.6,
  "p99LatencyMs": 84.1
}
```

### 8.2 Time-Series Traffic Telemetry
- **Endpoint**: `GET /api/v1/analytics/timeseries`
- **Query Parameters**:
  - `range`: `24h`
  - `interval`: `5m`, `1h`, `1d`
- **Response `200 OK`**: Array of timestamped telemetry buckets with 2xx, 4xx, 429, and 5xx counters.

### 8.3 Top Consumers Analytics
- **Endpoint**: `GET /api/v1/analytics/top-consumers`
- **Response `200 OK`**: List of top consuming tenants ordered by request volume and quota consumption percentage.

---

## 9. Chaos Engineering Lab API (`/api/v1/chaos`)

### 9.1 Run Chaos Experiment
- **Endpoint**: `POST /api/v1/chaos/experiments/run`
- **Request Body**:
```json
{
  "scenario": "ORDERS_OUTAGE",
  "targetService": "orders-service",
  "durationSeconds": 60,
  "failureType": "HTTP_500",
  "failureRatePercent": 100
}
```
- **Response `200 OK`**: Experiment execution record with ID, empirical baseline, and circuit breaker trip assertions.

### 9.2 List Past Experiments
- **Endpoint**: `GET /api/v1/chaos/experiments`
- **Response `200 OK`**: History of chaos runs with verified provenance metrics.

---

## 10. Gateway Reverse Proxy Router (`/api/v1/proxy/**`)

### 10.1 Proxied Order Creation
- **Endpoint**: `POST /api/v1/proxy/orders`
- **Headers**:
  - `X-API-Key: gk_live_...`
  - `Content-Type: application/json`
- **Request Body**:
```json
{
  "customerId": "cust-98712",
  "items": [
    { "sku": "SKU-LOGITECH-MX3", "quantity": 1, "price": 99.99 }
  ],
  "shippingAddress": "100 Enterprise Way, Austin, TX"
}
```
- **Pipeline Execution**:
  1. Validates API Key & extracts tenant plan (`ENTERPRISE`).
  2. Executes Redis Lua token bucket rate check (`PASS`).
  3. Evaluates active Policy Studio rules (`ALLOW`).
  4. Passes through Resilience4j Bulkhead & Circuit Breaker.
  5. Forwards to Orders Microservice (`http://localhost:8081/orders`).
  6. Dispatches async usage event to analytics queue.
- **Response `201 Created`**:
```json
{
  "id": "ord-88123-abc",
  "status": "CONFIRMED",
  "totalAmount": 99.99,
  "createdAt": "2026-11-22T04:00:00Z"
}
```

---

## 11. Downstream Microservices Specifications

### 11.1 Orders Service (`localhost:8081`)
- `GET /orders`: List recent orders.
- `GET /orders/{id}`: Get order details.
- `POST /orders`: Place order.
- `GET /health`: Actuator liveness check.
- **Fault Injection (`/fault`)**:
  - `POST /fault/configure`: Configure latency injection or synthetic HTTP 500 error rates.
  - `POST /fault/reset`: Reset fault simulator to nominal state.
  - `GET /fault/status`: Report current fault configuration.

### 11.2 Inventory Service (`localhost:8082`)
- `GET /inventory`: List product catalog stock levels.
- `GET /inventory/{sku}`: Query stock count for specific SKU.
- `GET /health`: Actuator liveness check.
- **Fault Injection (`/fault`)**: Same fault control API as Orders Service for testing bulkhead isolation.

---

## 12. Actuator & Liveness API

- `GET /api/v1/health`: Gateway top-level health summary (`UP`, `DEGRADED`, `DOWN`).
- `GET /actuator/health`: Spring Boot Actuator deep health check with database, redis, and disk space health contributors.
- `GET /actuator/metrics`: Actuator Prometheus/Micrometer metrics registry.
- `GET /actuator/prometheus`: Prometheus scrape endpoint.
