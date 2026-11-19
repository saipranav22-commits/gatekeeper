# Gatekeeper Phase 7C — Advanced Policy Studio & Rule Engine Architecture

## 1. Executive Summary

The **Policy Studio** is Gatekeeper's signature governance experience. It provides an enterprise-grade control plane for inspecting, authoring, simulating, replaying, promoting, and rolling back declarative authorization and access policies.

All operations strictly invoke Spring Boot `PolicyAdminController` endpoints (`/api/v1/policies/*`). Gatekeeper policies are immutable, versioned, and deterministically evaluated by priority order.

---

## 2. Policy Data Model & Rule Schema

### 2.1 Policy Definition
A policy is uniquely identified by an alphanumeric `policyKey` (e.g., `api-gateway-default` or `financial-compliance`).
- **Policy Definition Attributes**:
  - `policyKey`: Unique string key.
  - `name`: Human-readable identifier.
  - `description`: Architectural rationale.
  - `activeVersion`: Integer pointing to the currently enforced version in production runtime.
  - `shadowVersion`: Optional integer pointing to a version being evaluated in shadow mode against live traffic without blocking requests.
  - `versionLock`: Optimistic locking sequence number guarding against concurrent activation races.
  - `createdAt` & `updatedAt`: ISO-8601 audit timestamps.

### 2.2 Rule Precedence & Deterministic Evaluation Order
Each policy version contains an array of immutable `PolicyRule` objects.
- **Evaluation Order**: Rules are evaluated strictly by **ascending numerical priority** (`priority: 1` executes before `priority: 2`).
- **Short-Circuit Evaluation**:
  - `DENY`: Immediately terminates evaluation, short-circuiting all subsequent rules, and emits the specified `reasonCode` (e.g., `UNAUTHORIZED_SCOPE`, `RESTRICTED_METHOD`).
  - `ALLOW_CONTINUE`: Permits request flow to evaluate subsequent rules until the terminal rule or default gateway policy is reached.
- **Rule Attributes**:
  - `id`: Stable string identifier (e.g., `rule-admin-only`).
  - `priority`: Positive integer defining evaluation precedence.
  - `enabled`: Boolean toggle indicating whether the rule is active during evaluation.
  - `action`: `ALLOW_CONTINUE` or `DENY`.
  - `reasonCode`: Explanatory machine code emitted on match.
  - `conditions`: Key-value matching criteria:
    - `routePattern`: Path glob / regex (e.g., `/api/v1/admin/**`).
    - `methodPattern`: HTTP verb (e.g., `DELETE`, `POST|PUT`).
    - `consumerPlanTier`: Required consumer plan level (`FREE`, `STANDARD`, `ENTERPRISE`).
    - `requiredScopes`: Space or comma-delimited OAuth2/JWT scopes.
    - `timeWindow`: Scheduled time restrictions.

---

## 3. Core Studio Workspaces

The Policy Studio interface is organized into five specialized workspaces:

### 3.1 Rule Inspector & Candidate Version Builder
- **Active / Candidate Selector**: Operators select a policy definition and toggle between the currently active production version and historical or draft candidate versions.
- **Rule Inspector Table**: Visualizes each rule's numerical priority, status pill (`ENABLED` vs `DISABLED`), action badge (`ALLOW_CONTINUE` in emerald, `DENY` in rose), route and method filters, required scopes, and reason code.
- **Candidate Version Builder**:
  - Modal providing structured JSON authoring with strict schema validation.
  - Prevents malformed rule syntax, duplicate rule IDs, or non-integer priorities.
  - Submits to `POST /api/v1/policies/{policyKey}/versions`, generating a new immutable candidate version ready for simulation.

### 3.2 Dry-Run Simulation Engine
Before any rule modification touches production traffic, operators can simulate synthetic request probes:
- **Probe Configuration**:
  - Path / Route (e.g., `/api/v1/orders/purge`)
  - HTTP Method (`GET`, `POST`, `PUT`, `DELETE`, `PATCH`)
  - Consumer Plan Tier (`FREE`, `STANDARD`, `ENTERPRISE`)
  - Granted Scopes (e.g., `read:orders write:admin`)
  - Client IP Address (e.g., `192.168.1.100`)
- **Backend Execution**:
  - Dispatches `POST /api/v1/policies/{policyKey}/simulate` against either the candidate or active version.
- **Decision Trace Visualization**:
  - Large outcome banner: `ALLOW` (Emerald) or `DENY` (Rose).
  - Microsecond execution latency (`executionDurationMicros`).
  - Step-by-step trace showing every evaluated rule in priority order, match/skip indicators, and terminal decision emitter.

### 3.3 Historical Impact Analysis (Replay Engine)
To verify whether a candidate policy version will accidentally break existing consumers:
- **Telemetry Replay**:
  - Replays candidate rules against recent real gateway telemetry events via `POST /api/v1/policies/{policyKey}/replay`.
- **Outcome Delta Metrics**:
  - `Decisions Unchanged`: Requests whose access verdict remains identical.
  - `Newly Denied`: Previously permitted requests that candidate rules will block (crucial for breaking change detection).
  - `Newly Permitted`: Previously denied requests that candidate rules will allow.
  - `Not Evaluable`: Requests lacking sufficient metadata to determine an outcome.
- **Difference Inspector**:
  - Table displaying the specific request paths, methods, previous decisions, and new decisions for all differing outcomes.

### 3.4 Version Comparison (Diff)
- Side-by-side comparative inspector rendering the currently active production version alongside the selected candidate version.
- Highlights added rules, removed rules, priority shifts, and condition modifications before activation.

### 3.5 Governance & Audit Trail
- **Promote to Active**:
  - Dispatches `POST /api/v1/policies/{policyKey}/versions/{version}/activate`.
  - Requires explicit confirmation with an impact disclosure banner.
  - Updates runtime active pointer and increments the version lock.
- **Rollback Operation**:
  - Dispatches `POST /api/v1/policies/{policyKey}/rollback`.
  - Operators select target historical version and must provide an audit reason (minimum 5 characters).
  - Instantly reverts runtime traffic to the proven version.
- **Shadow Mode**:
  - Dispatches `POST /api/v1/policies/{policyKey}/shadow`.
  - Sets candidate version to run asynchronously in shadow evaluation against live requests.
- **Audit History**:
  - Fetches `GET /api/v1/policies/{policyKey}/audit`.
  - Tabular timeline showing timestamp, action type (`ACTIVATED`, `ROLLED_BACK`, `SHADOW_SET`), version number, previous version, acting administrator, and reason.

---

## 4. Security & Role-Based Access Control (RBAC)

The Policy Studio enforces strict UI and backend permission boundaries:

| Action | Required Role | UI Control State |
| :--- | :--- | :--- |
| View Policies, Rules, Audits | `SUPER_ADMIN`, `ADMIN`, `ANALYST` | Fully visible & accessible |
| Run Simulation & Replay | `SUPER_ADMIN`, `ADMIN`, `ANALYST` | Active simulation controls |
| Create Candidate Version | `SUPER_ADMIN`, `ADMIN` | Disabled / hidden for `ANALYST` |
| Activate Version | `SUPER_ADMIN`, `ADMIN` | Action modal restricted |
| Rollback Version | `SUPER_ADMIN`, `ADMIN` | Action modal restricted |
| Configure Shadow Mode | `SUPER_ADMIN`, `ADMIN` | Action modal restricted |

---

## 5. Visual Design & Obsidian Titanium Integration

The Policy Studio adheres strictly to the Obsidian Titanium design language:
- **Canvas**: Depth-layered backgrounds (`#070A09` base, `#131A18` elevated cards).
- **Typography**: Tabular data, priority badges, and JSON schemas render in high-contrast monospace font (`JetBrains Mono`).
- **Telemetry Indicators**: Real microsecond durations rendered with pulse animations and emerald telemetry accents (`#31E99A`).
- **Decision Hierarchy**: Clear semantic separation between `ALLOW_CONTINUE` (Emerald `#31E99A`) and `DENY` (Crimson `#F05460`).
