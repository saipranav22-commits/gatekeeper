# Gatekeeper Phase 7C — Enterprise Management & Policy Studio API Contracts

## 1. Overview & Verification Summary

Phase 7C expands Gatekeeper's frontend administration surfaces to bind strictly to verified Spring Boot controllers:
- `ConsumerController`: `/api/v1/consumers`
- `ApiKeyController`: `/api/v1/consumers/{consumerId}/keys`
- `PlanController`: `/api/v1/plans`
- `PolicyAdminController`: `/api/v1/policies`

Every UI action invokes an authentic backend endpoint. No mock generators or synthetic responses are used. All calls require administrative JWT authentication and honor Spring Security `@PreAuthorize` role gates.

---

## 2. API Consumers (`ConsumerController`)

### 2.1 List Consumers
- **Endpoint**: `GET /api/v1/consumers`
- **Roles**: `SUPER_ADMIN`, `ADMIN`, `ANALYST`
- **Query Parameters**:
  - `page`: integer (default 0)
  - `size`: integer (1 to 100, default 20)
  - `sortBy`: string (`createdAt`, `name`, `email`, `status`; default `createdAt`)
  - `direction`: string (`asc`, `desc`; default `desc`)
- **Ordering**: Secondary sort on `id ASC` enforces deterministic pagination across concurrent inserts.
- **Response**: `Page<ConsumerDto>`

### 2.2 Create Consumer
- **Endpoint**: `POST /api/v1/consumers`
- **Roles**: `SUPER_ADMIN`, `ADMIN`
- **Request Body**:
  ```json
  {
    "name": "Acme Global",
    "email": "api@acme.com",
    "planId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
  }
  ```
- **Response**: `201 Created` with `ConsumerDto` and `Location` header.

### 2.3 Migrate Plan
- **Endpoint**: `PATCH /api/v1/consumers/{consumerId}/plan`
- **Roles**: `SUPER_ADMIN`, `ADMIN`
- **Request Body**: `{ "planId": "UUID" }`
- **Side Effect**: Updates assigned subscription; subsequent requests immediately inherit new Redis token bucket capacity.

### 2.4 Suspend / Reactivate / Soft Delete
- **Suspend**: `PATCH /api/v1/consumers/{consumerId}/suspend`
- **Reactivate**: `PATCH /api/v1/consumers/{consumerId}/reactivate`
- **Soft Delete**: `DELETE /api/v1/consumers/{consumerId}` (Returns `204 No Content`)

---

## 3. Cryptographic API Keys (`ApiKeyController`)

### 3.1 Provision Key
- **Endpoint**: `POST /api/v1/consumers/{consumerId}/keys`
- **Roles**: `SUPER_ADMIN`, `ADMIN`
- **Header Guard**: `Cache-Control: no-store` strictly enforced by controller.
- **Request Body**:
  ```json
  {
    "name": "Production Ingress Key",
    "scopes": ["gateway:invoke", "read:orders"],
    "expiresAt": "2027-01-01T00:00:00Z"
  }
  ```
- **Response**:
  ```json
  {
    "metadata": {
      "id": "key-uuid",
      "consumerId": "consumer-uuid",
      "name": "Production Ingress Key",
      "keyPrefix": "gk_live_8f3a92",
      "status": "ACTIVE",
      "scopes": ["gateway:invoke", "read:orders"],
      "expiresAt": "2027-01-01T00:00:00Z",
      "createdAt": "2026-11-20T00:00:00Z"
    },
    "plaintextKey": "gk_live_8f3a92_secretkey..."
  }
  ```
- **Security Invariant**: Plaintext secret is returned exactly once in the response body. Backend stores only salted SHA-256 digest. Frontend immediately purges the plaintext key from memory upon modal dismissal.

### 3.2 Revoke Key
- **Endpoint**: `POST /api/v1/consumers/{consumerId}/keys/{keyId}/revoke`
- **Roles**: `SUPER_ADMIN`, `ADMIN`
- **Request Body**: `{ "reason": "Credential rotated during audit" }` (max 255 chars).
- **Semantics**: Idempotent. Subsequent ingress attempts immediately receive `401 Unauthorized`.

---

## 4. Subscription Plans (`PlanController`)

- **List Plans**: `GET /api/v1/plans`
- **Roles**: `SUPER_ADMIN`, `ADMIN`, `ANALYST`
- **Response**: `List<SubscriptionPlanDto>`
  ```json
  [
    {
      "id": "plan-uuid",
      "name": "ENTERPRISE",
      "description": "High-throughput production tier",
      "bucketCapacity": 10000,
      "refillTokens": 1000,
      "refillPeriodSeconds": 1,
      "isActive": true
    }
  ]
  ```

---

## 5. Policy Studio & Engine (`PolicyAdminController`)

### 5.1 Policy Container Registry
- **List Definitions**: `GET /api/v1/policies`
- **Get Definition**: `GET /api/v1/policies/{policyKey}`
- **Create Definition**: `POST /api/v1/policies`
  - Constraints: `policyKey` must match `^[a-zA-Z0-9_-]{3,64}$`.

### 5.2 Immutable Policy Versions
- **List Versions**: `GET /api/v1/policies/{policyKey}/versions`
- **Get Version**: `GET /api/v1/policies/{policyKey}/versions/{versionNumber}`
- **Create Version**: `POST /api/v1/policies/{policyKey}/versions`
  - Constraints:
    - Rule list must be non-empty and $\le 100$ rules.
    - `ruleId` must match `^[a-zA-Z0-9_-]{3,64}$` with no duplicates.
    - `priority` integer between 1 and 100,000 (ascending numerical order).
    - `action`: `ALLOW_CONTINUE` or `DENY`.
    - `timeWindow`: both `timeWindowStartUtc` and `timeWindowEndUtc` required if either specified (`HH:mm` or `HH:mm:ss`).

### 5.3 Promotion, Rollback & Shadow Mode
- **Activate Version**: `POST /api/v1/policies/{policyKey}/versions/{versionNumber}/activate?reason=...`
  - Retires previous active version and atomically invalidates Redis gateway caches.
- **Rollback**: `POST /api/v1/policies/{policyKey}/rollback?targetVersion=...&reason=...`
- **Configure Shadow**: `POST /api/v1/policies/{policyKey}/shadow`
  - Evaluates candidate version asynchronously during live traffic without affecting responses.

### 5.4 Dry-Run Simulation
- **Endpoint**: `POST /api/v1/policies/{policyKey}/simulate`
- **Request Body**:
  ```json
  {
    "candidateVersionNumber": 1,
    "routeId": "/api/v1/orders",
    "httpMethod": "POST",
    "consumerPlan": "STARTER",
    "apiKeyScopes": ["gateway:invoke"],
    "clientIp": "192.168.1.100"
  }
  ```
- **Response**: `PolicySimulationResponse` with `decision` (`ALLOW_CONTINUE` | `DENY`), `matchedRuleId`, `reasonCode`, `durationNanos`, and full `PolicyDecisionTrace`.

### 5.5 Historical Replay Impact Analysis
- **Endpoint**: `POST /api/v1/policies/{policyKey}/replay`
- **Request Body**: `{ "candidateVersionNumber": 2, "sampleLimit": 100, "sinceHours": 24 }`
- **Response**: `PolicyReplayResponse` with `decisionsUnchanged`, `newlyDenied`, `newlyPermitted`, `sampleDifferences`.

### 5.6 Governance Audit Trail
- **Endpoint**: `GET /api/v1/policies/{policyKey}/audit`
- **Response**: Chronological activation/rollback events with `actor`, `action`, `versionNumber`, `previousVersionNumber`, and `reason`.
