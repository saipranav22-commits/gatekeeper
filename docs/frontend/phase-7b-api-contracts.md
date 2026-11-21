# Gatekeeper Phase 7B — Frontend Telemetry & API Contracts

## 1. Overview & Architectural Principles

Gatekeeper Phase 7B anchors the frontend Command Center (`/overview`) and Usage Analytics Workspace (`/analytics`) strictly to real, verified backend telemetry endpoints. No synthetic data generators, hardcoded metric mockers, or unverified counters are used.

All metrics are fetched over authenticated HTTPS channels using JWT bearer tokens (with the exception of the public `/api/v1/health` probe). Requests are managed via TanStack Query (`@tanstack/react-query`) with deterministic caching, stale-while-revalidate policies, visibility-aware polling, and explicit request cancellation (`AbortController`).

---

## 2. Telemetry Endpoints Specification

### 2.1 System Health Probe
- **Endpoint**: `GET /api/v1/health`
- **Authentication**: Public (unauthenticated)
- **Purpose**: Verifies that the gateway kernel process is running and responsive.
- **Request Parameters**: None
- **Response Status**: `200 OK`
- **Response Schema**:
  ```json
  {
    "status": "UP",
    "service": "gatekeeper",
    "serviceName": "gatekeeper",
    "timestamp": "2026-11-19T18:00:00.000Z"
  }
  ```
- **Error Handling**: Non-200 or network timeout immediately transitions composite health to `OFFLINE`.

---

### 2.2 Route Resilience & Circuit Breakers
- **Endpoint**: `GET /api/v1/resilience/routes`
- **Authentication**: Bearer JWT (Admin role)
- **Controller**: `com.gatekeeper.resilience.controller.ResilienceAdminController`
- **Purpose**: Telemetry feed of Resilience4j circuit breakers and bulkheads per upstream route.
- **Response Status**: `200 OK`
- **Response Schema** (`List<RouteResilienceSummary>`):
  ```json
  [
    {
      "routeId": "orders-service",
      "circuitBreakerState": "CLOSED",
      "failureRate": 0.0,
      "slowCallRate": 0.0,
      "bufferedCalls": 120,
      "failedCalls": 0,
      "slowCalls": 0,
      "bulkheadAvailableConcurrentCalls": 50,
      "bulkheadMaxConcurrentCalls": 50
    },
    {
      "routeId": "inventory-service",
      "circuitBreakerState": "CLOSED",
      "failureRate": 0.0,
      "slowCallRate": 0.0,
      "bufferedCalls": 95,
      "failedCalls": 0,
      "slowCalls": 0,
      "bulkheadAvailableConcurrentCalls": 50,
      "bulkheadMaxConcurrentCalls": 50
    }
  ]
  ```
- **Status State Machine**:
  - `CLOSED`: Route healthy and operational (Titanium Mint `#31E99A`).
  - `HALF_OPEN`: Circuit recovery probing in flight (Amber Alert `#E9B35A`).
  - `OPEN`: Upstream trip, traffic shedding active (Crimson Warning `#F05460`).
  - `FORCED_OPEN` / `DISABLED`: Administrative maintenance mode.

---

### 2.3 System Analytics Overview
- **Endpoint**: `GET /api/v1/analytics/overview`
- **Authentication**: Bearer JWT
- **Query Parameters**:
  - `start`: ISO-8601 UTC timestamp (`YYYY-MM-DDTHH:mm:ssZ`)
  - `end`: ISO-8601 UTC timestamp (`YYYY-MM-DDTHH:mm:ssZ`)
- **Controller**: `com.gatekeeper.analytics.controller.AnalyticsController`
- **Purpose**: Global KPI rollup across all managed gateway consumers and routes.
- **Response Schema** (`GlobalAnalyticsDto`):
  ```json
  {
    "totalRequests": 128450,
    "allowedRequests": 121300,
    "rateLimitedRequests": 5400,
    "scopeDeniedRequests": 1500,
    "errorRequests": 250,
    "rateLimitDenialPercentage": 4.2,
    "usageByPlan": {
      "ENTERPRISE": 85000,
      "GROWTH": 32000,
      "STARTER": 11450
    }
  }
  ```
- **Null & Zero Safety**: When traffic is zero, `rateLimitDenialPercentage` defaults to `0.0`. `usageByPlan` defaults to an empty object `{}`.

---

### 2.4 Time-Series Traffic Flow
- **Endpoint**: `GET /api/v1/analytics/timeseries`
- **Authentication**: Bearer JWT
- **Query Parameters**:
  - `start`: ISO-8601 UTC timestamp
  - `end`: ISO-8601 UTC timestamp
  - `granularity`: `hourly` or `daily`
- **Purpose**: Interval bucket counts feeding throughput area and bar charts.
- **Response Schema** (`List<TimeSeriesPointDto>`):
  ```json
  [
    {
      "timestamp": "2026-11-19T00:00:00Z",
      "allowedRequests": 4500,
      "rateLimitedRequests": 120,
      "scopeDeniedRequests": 15,
      "errorRequests": 2
    }
  ]
  ```
- **Sorting**: Monotonically ascending by `timestamp`.

---

### 2.5 Top Consumer Rankings
- **Endpoint**: `GET /api/v1/analytics/top-consumers`
- **Authentication**: Bearer JWT
- **Query Parameters**:
  - `start`: ISO-8601 UTC timestamp
  - `end`: ISO-8601 UTC timestamp
  - `limit`: Integer (default 5, maximum 50)
- **Purpose**: Leaderboard of tenants driving the highest gateway load.
- **Response Schema** (`List<TopConsumerDto>`):
  ```json
  [
    {
      "consumerId": "8f8b3d60-7a08-412e-a128-4ef8dc8793b1",
      "consumerName": "Apex Financial Services",
      "consumerEmail": "api-ops@apexfin.com",
      "plan": "ENTERPRISE",
      "totalRequests": 64200,
      "allowedRequests": 62500,
      "rateLimitedRequests": 1700,
      "rateLimitDenialPercentage": 2.65
    }
  ]
  ```

---

### 2.6 Consumer-Specific Telemetry
- **Endpoint**: `GET /api/v1/analytics/consumers/{id}`
- **Authentication**: Bearer JWT
- **Path Parameters**: `id` (UUID string)
- **Response Schema** (`ConsumerAnalyticsDto`):
  ```json
  {
    "consumerId": "8f8b3d60-7a08-412e-a128-4ef8dc8793b1",
    "consumerName": "Apex Financial Services",
    "consumerEmail": "api-ops@apexfin.com",
    "plan": "ENTERPRISE",
    "totalRequests": 64200,
    "allowedRequests": 62500,
    "rateLimitedRequests": 1700,
    "scopeDeniedRequests": 0,
    "firstSeen": "2026-01-15T08:30:00Z",
    "lastSeen": "2026-11-19T17:42:10Z",
    "requestsByEndpoint": {
      "/api/v1/orders": 41200,
      "/api/v1/inventory": 23000
    },
    "requestsByKeyPrefix": {
      "gk_live_apex": 64200
    }
  }
  ```

---

### 2.7 Granular Consumer Usage Audit Events
- **Endpoint**: `GET /api/v1/analytics/consumers/{id}/usage`
- **Authentication**: Bearer JWT
- **Query Parameters**:
  - `page`: Zero-indexed integer (default: 0)
  - `size`: Integer (default: 15)
  - `outcome`: Optional filter (`ALLOWED`, `RATE_LIMITED`, `SCOPE_DENIED`)
- **Response Schema** (`PageableResponse<UsageEventDetailDto>`):
  ```json
  {
    "content": [
      {
        "id": "e458392a-3b56-4912-9c98-17a86cd7f012",
        "timestamp": "2026-11-19T17:42:10.124Z",
        "apiKeyId": "k8294a71-0021-49b6-89d1-0f72bda58410",
        "keyPrefixSnapshot": "gk_live_apex",
        "endpointPath": "/api/v1/orders",
        "httpMethod": "POST",
        "httpStatus": 200,
        "latencyMs": 14,
        "outcomeCategory": "ALLOWED",
        "rateLimitPlan": "ENTERPRISE",
        "correlationId": "gatekeeper-corr-98a72b14"
      }
    ],
    "totalPages": 428,
    "totalElements": 64200,
    "size": 15,
    "number": 0,
    "first": true,
    "last": false,
    "numberOfElements": 15,
    "empty": false
  }
  ```
