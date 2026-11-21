# Gatekeeper Phase 7C — Enterprise API Management Architecture

## 1. Architectural Philosophy

Phase 7C establishes Gatekeeper as a mature, enterprise-grade API infrastructure management console. The management planes—Consumers, API Keys, and Subscription Plans—follow strict operational principles:

1. **Deterministic State Synchronization**: Direct binding to PostgreSQL and Redis data layers.
2. **Zero Plaintext Secret Retention**: Cryptographic credentials are never held in browser storage, tables, or analytics streams.
3. **Role-Enforced Action Gating**: Mutating controls (provisioning, plan migration, suspension, revocation) are visible and active only for authenticated `ADMIN` and `SUPER_ADMIN` roles; non-privileged roles receive read-only observability access.
4. **Obsidian Titanium Theme Harmony**: Dense, high-contrast, professional typography and dark card surfaces with emerald accents.

---

## 2. API Consumer Management (`/consumers` & `/consumers/:consumerId`)

### 2.1 Directory & Multi-Field Filtering
- Server-side sorting on `createdAt`, `name`, `email`, and `status`.
- Deterministic pagination preserving sort order across concurrent insertions via secondary sort on `id ASC`.
- Status filter: `ALL`, `ACTIVE`, `SUSPENDED`, `DEACTIVATED`.

### 2.2 Consumer Lifecycle Workflows
- **Registration**: Captures organization name, contact email, and initial subscription tier.
- **Plan Migration**: Interactive modal selecting target plan tier. Discloses operational impact: active tokens and burst limits immediately adapt in Redis cluster upon confirmation.
- **Access Suspension & Reactivation**: Immediate edge suspension halting all key validation for the consumer.
- **Audit-Preserving Soft Deletion**: Marks consumer as soft-deleted (`isDeleted = true`) while preserving historical analytics, correlation IDs, and billing telemetry in the relational store.

---

## 3. Cryptographic API Key Management (`/api-keys`)

### 3.1 Security & Secret Lifecycle Guarantees
- **Prefix Isolation**: The directory and tables render ONLY the sanitized key prefix (e.g. `gk_live_8f3a92...`). The raw secret is never transmitted in list queries or rendered in DOM elements.
- **One-Time Plaintext Reveal (`OneTimeSecretModal`)**:
  - The plaintext secret is delivered exclusively in the `201 Created` response body under `Cache-Control: no-store`.
  - The modal provides a high-contrast read-only input, copy-to-clipboard button with visual confirmation, and an explicit security warning explaining that Gatekeeper stores only the salted SHA-256 digest.
  - Upon dismissal, the plaintext key is wiped from React state and is never recoverable again.
- **Zero LocalStorage Policy**: Plaintext keys are never persisted to `localStorage`, `sessionStorage`, or cookies.

### 3.2 Revocation Semantics
- Revocation modal requires confirmation and an audit reason (max 255 characters).
- Once revoked, the key status becomes `REVOKED` and any subsequent gateway ingress request sending the credential receives `HTTP 401 Unauthorized`.

---

## 4. Subscription Plans & Rate Limits (`/plans`)

### 4.1 Token Bucket Mathematical Model
Gatekeeper enforces rate limits via distributed Redis 7 Token Bucket scripts:
- **Burst Capacity ($B$)**: Maximum tokens stored in the Redis hash key (`bucketCapacity`). Allows burst traffic without shedding.
- **Replenishment Rate ($R$)**: Number of tokens added per period (`refillTokens`).
- **Replenishment Window ($P$)**: Refill period in seconds (`refillPeriodSeconds`).
- **Sustained Throughput**: Calculated dynamically on the interface as:
  $$\text{Sustained Rate} = \frac{\text{refillTokens}}{\text{refillPeriodSeconds}} \text{ req/s}$$

### 4.2 Interactive Comparison & Quota Transparency
- Side-by-side comparison matrix of all active tiers (Enterprise, Growth/Pro, Starter).
- Highlighting of burst capacity, replenishment frequency, and architectural guarantees of atomic Redis Lua execution.
