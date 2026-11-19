# Gatekeeper Phase 7D — Reliability & Chaos API Contracts Matrix

## 1. Resilience & Circuit Breaker Endpoints

### 1.1 List Route Resilience Summaries
- **Method & Path**: `GET /api/v1/resilience/routes`
- **Security / Auth**: Bearer JWT (`ROLE_SUPER_ADMIN`, `ROLE_ADMIN`, `ROLE_ANALYST`)
- **Description**: Returns real-time sliding window telemetry and bulkhead states for all registered proxy routes.
- **Response `200 OK`**:
  ```json
  [
    {
      "routeId": "orders-service-v1",
      "circuitBreakerState": "CLOSED",
      "failureRate": 0.0,
      "slowCallRate": 0.0,
      "totalCalls": 120,
      "failedCalls": 0,
      "slowCalls": 0,
      "rejectedCalls": 0,
      "bulkheadMaxConcurrent": 10,
      "bulkheadAvailablePermits": 10,
      "bulkheadInFlightCalls": 0,
      "bulkheadRejectedCalls": 0,
      "retryMaxAttempts": 3,
      "timeoutMs": 5000,
      "lastStatusChange": "2026-11-20T00:00:00Z"
    }
  ]
  ```

### 1.2 Get Route Resilience Detail
- **Method & Path**: `GET /api/v1/resilience/routes/{routeId}`
- **Security / Auth**: Bearer JWT (`ROLE_SUPER_ADMIN`, `ROLE_ADMIN`, `ROLE_ANALYST`)
- **Description**: Returns detailed sliding-window statistics, configuration thresholds, and retry parameters for a single route.
- **Response `200 OK`**:
  ```json
  {
    "routeId": "orders-service-v1",
    "circuitBreaker": {
      "state": "CLOSED",
      "failureRate": 0.0,
      "failureRateThreshold": 50.0,
      "slowCallRate": 0.0,
      "slowCallRateThreshold": 50.0,
      "slowCallDurationThresholdMs": 2000,
      "slidingWindowType": "COUNT_BASED",
      "slidingWindowSize": 20,
      "minimumNumberOfCalls": 5,
      "permittedNumberOfCallsInHalfOpenState": 5,
      "waitDurationInOpenStateMs": 10000,
      "automaticTransitionFromOpenToHalfOpenEnabled": true,
      "bufferedCalls": 20,
      "failedCalls": 0,
      "successfulCalls": 20
    },
    "bulkhead": {
      "maxConcurrentCalls": 10,
      "availableConcurrentCalls": 10,
      "maxWaitDurationMs": 0
    },
    "retry": {
      "maxAttempts": 3,
      "waitDurationMs": 200,
      "jitterFactor": 0.5,
      "maxCumulativeDeadlineMs": 5000
    }
  }
  ```

### 1.3 Manual Circuit Breaker Reset
- **Method & Path**: `POST /api/v1/resilience/routes/{routeId}/reset`
- **Security / Auth**: Bearer JWT (`ROLE_SUPER_ADMIN` or `ROLE_ADMIN` only)
- **Description**: Manually resets the circuit breaker to `CLOSED` and purges the sliding window.
- **Response `200 OK`**:
  ```json
  {
    "routeId": "orders-service-v1",
    "previousState": "OPEN",
    "currentState": "CLOSED",
    "resetBy": "sre@gatekeeper.local",
    "timestamp": "2026-11-20T01:30:00Z",
    "message": "Circuit breaker successfully reset to CLOSED state"
  }
  ```
- **Error Responses**:
  - `401 Unauthorized`: Missing or invalid JWT.
  - `403 Forbidden`: User lacks mutation role (e.g. `ROLE_ANALYST`).
  - `404 Not Found`: Unknown `routeId`.

---

## 2. Health & Actuator Endpoints

### 2.1 Public Gateway Liveness
- **Method & Path**: `GET /api/v1/health`
- **Security / Auth**: Public (No JWT required)
- **Description**: Returns public gateway liveness and deployment identity.
- **Response `200 OK`**:
  ```json
  {
    "status": "UP",
    "service": "gatekeeper",
    "timestamp": "2026-11-20T01:30:00Z"
  }
  ```

### 2.2 Authenticated Actuator Health
- **Method & Path**: `GET /actuator/health`
- **Security / Auth**: Bearer JWT
- **Description**: Component-level diagnostic health for database, Redis, and disk space.
- **Response `200 OK`**:
  ```json
  {
    "status": "UP",
    "components": {
      "db": {
        "status": "UP",
        "details": {
          "database": "PostgreSQL",
          "validationQuery": "isValid()"
        }
      },
      "redis": {
        "status": "UP",
        "details": {
          "version": "7.2.4"
        }
      },
      "diskSpace": {
        "status": "UP",
        "details": {
          "total": 499963174912,
          "free": 312074551296,
          "threshold": 10485760
        }
      }
    }
  }
  ```

---

## 3. Controlled Chaos Engineering Endpoints

> **IMPORTANT**: The following endpoints are enabled strictly under Spring profiles `local`, `test`, and `dev`. In `prod`, the controller bean is unmounted and returns `HTTP 404`.

### 3.1 List Recorded Experiments
- **Method & Path**: `GET /api/v1/chaos/experiments`
- **Security / Auth**: Bearer JWT (`ROLE_SUPER_ADMIN` or `ROLE_ADMIN`)
- **Description**: Returns all live chaos experiments executed in the current non-production session.
- **Response `200 OK`**: Array of `ChaosExperiment` objects.

### 3.2 Execute Controlled Experiment Run
- **Method & Path**: `POST /api/v1/chaos/experiments/run`
- **Security / Auth**: Bearer JWT (`ROLE_SUPER_ADMIN` or `ROLE_ADMIN`)
- **Request Body**:
  ```json
  {
    "scenario": "ORDERS_OUTAGE",
    "parameters": {}
  }
  ```
- **Validation Ceiling (`ChaosSafetyGuard`)**:
  - Rejects if concurrency > 50 or total requests > 500.
  - Rejects if target host is not a loopback address.
- **Response `200 OK`**:
  ```json
  {
    "id": "chaos-exp-101",
    "scenarioType": "ORDERS_OUTAGE",
    "target": "orders-service-v1",
    "faultType": "HTTP_ERROR",
    "parameters": {},
    "state": "COMPLETED",
    "createdAt": "2026-11-20T01:30:00Z",
    "completedAt": "2026-11-20T01:30:12Z",
    "cleanupStatus": "SUCCESS",
    "provenance": "LIVE_LOCAL_EXPERIMENT",
    "assertions": [
      {
        "name": "CircuitBreakerTrip",
        "description": "Circuit breaker transitions from CLOSED to OPEN after error threshold",
        "outcome": "PASS",
        "observedDetails": "Observed CLOSED -> OPEN transition after 5 failures"
      },
      {
        "name": "FastRejection",
        "description": "Post-trip requests fast-fail with HTTP 503 in < 25 ms",
        "outcome": "PASS",
        "observedDetails": "Observed fast rejection in 8 ms"
      }
    ]
  }
  ```
