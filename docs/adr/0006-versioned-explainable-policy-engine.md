# ADR 0006: Versioned, Immutable, and Explainable Policy Engine

## Status
Accepted

## Context
As Gatekeeper evolved into an upstream API gateway (Phases 5A and 5B), route protection and traffic filtering required capabilities beyond static role-based access control and token-bucket rate limiting. Gatekeeper requires fine-grained operational policies (e.g., maintenance windows, plan-specific path bans, IP allowlists/denylists, UTC time-based restrictions) with the following enterprise requirements:

1. **Explainability:** Administrative operators must know exactly why a request was admitted or rejected, which rule matched, and why higher-priority candidate rules did not apply.
2. **Immutable Versioning & Auditing:** Policy configurations cannot be modified in place; each version must be immutable, sequential, cryptographically tracked, and paired with an audit log of who activated or rolled back the version and why.
3. **Safety & Zero Side-Effects:** Operators must be able to dry-run simulate proposed policies against arbitrary synthetic contexts and replay candidate policies against historical `usage_events` without mutating live states, altering quotas, triggering circuit breakers, or making network calls.
4. **Mandatory Security Invariants:** Platform security (API key authentication, active consumer verification, required scopes, SSRF protection, route allowlisting) is fundamental and must strictly precede policy evaluation. A policy rule of `ALLOW_CONTINUE` must **never** grant access to an unauthenticated request or a request lacking mandatory scopes.
5. **Quota Preservation:** When a policy rejects a request (`DENY`), it returns HTTP 403 Forbidden with a safe reason code. It must **not** decrement the consumer's distributed Redis Token Bucket, nor execute an upstream network dispatch.
6. **High Performance & Multi-Instance Consistency:** Policy evaluation sits on the gateway hot path and must execute in microseconds (< 1 ms). Multiple gateway instances must invalidate local caches atomically via Redis pub/sub upon policy activation or rollback.

---

## Decision

### 1. Database Schema & Immutable Versioning (Flyway V8)
We introduced three relational tables and enriched historical telemetry:
- `policy_definitions`: Holds the aggregate root (`policy_key`, `name`, `description`, `active_version_id`, `shadow_version_id`, `version_lock`).
- `policy_versions`: Holds immutable rule sets serialized as validated JSON with an enforced sequential `version_number` and status (`DRAFT`, `ACTIVE`, `RETIRED`, `ROLLED_BACK`).
- `policy_activation_audits`: Immutable ledger recording every activation, deactivation, and rollback event with timestamp, actor username, and rationale.
- `usage_events`: Enriched with `policy_decision` and `matched_rule_id` columns to enable future audit queries and historical replay.

### 2. Deterministic Rule Evaluation Core
The policy evaluation core (`PolicyEvaluationEngine`) adheres to strict invariants:
- **First-Match Semantics:** Rules are evaluated strictly by priority (ascending, where priority 1 is evaluated before priority 10).
- **Deterministic Tie-Breaking:** In the event of identical priorities, the engine breaks ties deterministically by `ruleId` alphanumeric collation.
- **Complexity Bound:** Each policy version is capped at a maximum of 500 rules to prevent algorithmic complexity attacks or memory starvation.
- **Fail-Secure Default:** If no rule matches, the policy defaults to `ALLOW_CONTINUE` (proceed to rate limiting and upstream dispatch).

### 3. Pipeline Order & Mandatory Security Boundaries
The filter chain sequence in `ConsumerRateLimitFilter` enforces:
1. Route allowlisting and HTTP method validation
2. Mandatory consumer API key verification (`ConsumerApiKeyAuthenticationFilter`)
3. Mandatory scope checking
4. **Fine-grained Policy Engine Evaluation** (`PolicyCacheManager` -> `PolicyEvaluationEngine`)
   - If `DENY`: immediately terminates the pipeline, returns HTTP 403 Forbidden with safe reason message, emits telemetry (`OutcomeCategory.POLICY_DENIED`), and does NOT consume Redis quota.
   - If `ALLOW_CONTINUE`: proceeds to step 5.
5. Redis Token Bucket rate limiting
6. Concurrency bulkhead permit acquisition
7. Circuit breaker state check
8. Upstream HTTP dispatch

### 4. Zero Side-Effect Simulation and Replay
- `POST /api/v1/policies/{policyKey}/simulate`: Evaluates a synthetic context against any policy version, returning complete candidate evaluation traces with zero writes to database, Redis, or upstreams.
- `POST /api/v1/policies/{policyKey}/replay`: Evaluates historical telemetry against candidate rules. To preserve cryptographic and architectural truth, the replay engine **refuses to fabricate missing historical metadata** (such as historical API key scopes) and flags those events as `NOT_EVALUABLE` / `UNKNOWN`.

### 5. Multi-Instance Cache Consistency & Fail-Secure Protection
`PolicyCacheManager` caches active and shadow policies in concurrent in-memory snapshots.
- **Atomic Reference Swapping:** Updates replace the immutable snapshot pointer atomically.
- **Redis Invalidation:** Activations and rollbacks publish an invalidation event over `gatekeeper:policy:invalidation`.
- **Fail-Secure Cache Retention:** If the database or Redis becomes unreachable during an attempted cache reload, Gatekeeper strictly retains its existing validated cache snapshot rather than failing open or clearing rules.

---

## Consequences

### Positive
- **Deterministic Safety:** Zero risk of accidental policy overwrites or race conditions during multi-operator deployments.
- **Sub-Millisecond Evaluation:** In-memory cached rule evaluation completes in < 50 microseconds for 10 rules and < 400 microseconds for 500 rules.
- **Operational Confidence:** Operators can simulate and replay proposed rules against real production history prior to live activation.
- **Auditing Compliance:** Complete non-repudiation audit trail for all policy state changes.

### Neutral
- Historical events captured prior to Phase 5C lack API key scopes; historical replay correctly marks these records as `NOT_EVALUABLE` rather than guessing permissions.
