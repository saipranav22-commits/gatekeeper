# Gatekeeper

Gatekeeper is an API Key Management and Intelligent Rate Limiting Platform built with Spring Boot and Java 21.

## Prerequisites

- **Java**: Eclipse Temurin OpenJDK 21 (`D:\Development\tools\jdk-21`)
- **Maven**: Apache Maven 3.9.16 (`D:\Development\tools\maven`)
- **Docker Desktop**: Engine running with storage on `D:\Development\docker-data\disk`
- **Environment**: Activated via `env.ps1` located at the root of the project

## Environment Initialization

Before running build, test, or execution commands in PowerShell:

```powershell
. .\env.ps1
```

This script:
- Sets `JAVA_HOME` (`D:\Development\tools\jdk-21`) and `MAVEN_HOME` (`D:\Development\tools\maven`)
- Redirects Maven local repository to `D:\Development\maven-repository`
- Redirects temporary files to `D:\Development\temp`
- Safely loads `.env` variables (`DB_USERNAME`, `DB_PASSWORD`, `REDIS_PASSWORD`, etc.) into the PowerShell session without printing credentials to the console

## Local Infrastructure (Docker Compose)

Gatekeeper uses Docker Compose with isolated containers and host bind mounts on `D:`:
- **PostgreSQL 16**: Port `127.0.0.1:5434` -> container `5432` (Storage: `D:\Development\database-data\gatekeeper`)
- **Redis 7**: Port `127.0.0.1:6380` -> container `6379` (Storage: `D:\Development\docker-data\redis`)

To manage infrastructure:

```powershell
# Start containers in detached mode
docker compose up -d

# Verify container status and health
docker compose ps

# View container logs
docker compose logs -f
```

## Database Architecture

### Entity-Relationship Diagram

```mermaid
erDiagram
    subscription_plans ||--o{ api_consumers : "assigns rate limit tier"
    api_consumers ||--o{ api_keys : "owns multiple"
    api_keys ||--|{ api_key_scopes : "scoped with"
    api_consumers ||--o{ usage_events : "associated with"
    api_keys ||--o{ usage_events : "authenticated via"

    admin_users {
        uuid id PK
        varchar username UK
        varchar email UK
        varchar password_hash
        varchar role
        boolean is_active
        boolean must_change_password
        timestamptz created_at
        timestamptz updated_at
    }

    subscription_plans {
        uuid id PK
        varchar name UK
        varchar description
        bigint bucket_capacity
        bigint refill_tokens
        integer refill_period_seconds
        boolean is_active
        timestamptz created_at
        timestamptz updated_at
    }

    api_consumers {
        uuid id PK
        uuid plan_id FK
        varchar name
        varchar email UK
        varchar status
        boolean is_deleted
        timestamptz deleted_at
        timestamptz created_at
        timestamptz updated_at
    }

    api_keys {
        uuid id PK
        uuid consumer_id FK
        varchar name
        varchar key_prefix UK
        varchar secret_digest
        varchar status
        timestamptz expires_at
        timestamptz revoked_at
        varchar revocation_reason
        timestamptz last_used_at
        bigint version
        boolean is_deleted
        timestamptz created_at
        timestamptz updated_at
    }

    api_key_scopes {
        uuid api_key_id PK, FK
        varchar scope PK
    }

    usage_events {
        uuid id PK
        timestamptz timestamp
        uuid consumer_id FK
        uuid api_key_id FK
        varchar consumer_email_snapshot
        varchar key_prefix_snapshot
        varchar endpoint_path
        varchar http_method
        integer http_status
        integer latency_ms
        boolean rate_limited
        varchar rejection_reason
        varchar ip_address
        varchar user_agent
    }
```

### Flyway Migration Order

Database migrations reside in `backend/src/main/resources/db/migration/`:

1. **`V1__init_security_and_plans.sql`**:
   - Creates `admin_users` table with unique constraints on username and email (no default credentials seeded).
   - Creates `subscription_plans` table implementing Token Bucket semantics (`bucket_capacity`, `refill_tokens`, `refill_period_seconds`).
   - Seeds standard plans: `FREE` (60 tokens / 60s), `PRO` (600 tokens / 60s), and `ENTERPRISE` (6000 tokens / 60s).
2. **`V2__init_consumers_and_keys.sql`**:
   - Creates `api_consumers` with soft-deletion support (`is_deleted`, `deleted_at`) and foreign key referencing plans.
   - Creates `api_keys` with fixed 24-character public lookup prefix, SHA-256 secret digest, and optimistic concurrency version.
   - Creates `api_key_scopes` junction table for granular key scopes.
3. **`V3__init_usage_events.sql`**:
   - Creates `usage_events` table for audit and telemetry logging.
   - `consumer_id` and `api_key_id` are nullable to allow logging unauthenticated or invalid-key requests.
   - Includes historical snapshot columns (`consumer_email_snapshot`, `key_prefix_snapshot`) and composite performance indexes.
4. **`V4__add_admin_token_version.sql`**:
   - Adds `token_version` (BIGINT DEFAULT 1) and `password_changed_at` (TIMESTAMPTZ) to `admin_users`.
   - Enables instantaneous cryptographic invalidation of previously issued JWTs upon password change or credential rotation.

### Verifying Migrations

Verify migration status directly in PostgreSQL:

```powershell
docker exec gatekeeper-postgres psql -U gatekeeper_app -d gatekeeper -c "SELECT installed_rank, version, description, success FROM flyway_schema_history;"
```

## Building and Running Tests

```powershell
cd D:\Development\Gatekeeper\backend

# Execute the test suite (includes database integration and foundation tests)
mvn clean test
```

## Running the Application

### Local Development Profile (`local`)

Connects to local Docker PostgreSQL (`127.0.0.1:5434`) and Redis (`127.0.0.1:6380`) with Flyway migrations and Hibernate schema validation (`ddl-auto: validate`):

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### Local Foundation Profile (`foundation`)

Runs without requiring live PostgreSQL or Redis instances (excludes auto-configurations while maintaining full Spring Security enforcement):

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=foundation
```

## Verification Endpoints

- **Health Endpoint**:
  - `GET http://localhost:8080/api/v1/health`
  - Expected Response: `{"status":"UP","service":"gatekeeper","serviceName":"gatekeeper","timestamp":"..."}`
- **Protected Endpoints**:
  - Any protected route without credentials returns `401 Unauthorized`.

## Secure API Key Lifecycle & Domain Services

Gatekeeper implements a high-throughput, cryptographically secure API key lifecycle management system designed for API gateways.

### 1. API Key Format and Token Anatomy

API keys adhere to a strict 68-character structured token format:

```
gk_live_1122334455667788_aB3dE5gH7jK9mN1pQ3sU5wY7zB1dF3hJ5lM7oP9rT1v
\______/\______________/\/\_________________________________________/
Prefix     Public ID     |              Secret Component
(8 chars)  (16 hex chars)|                 (43 chars)
\_______________________/ \
   Lookup Key Prefix       Delimiter
       (24 chars)          (1 char)
```

| Component | Format | Length | Description |
|---|---|---|---|
| **Prefix / Environment** | `gk_live_` | 8 chars | Environment identifier enabling secret scanners (e.g. GitHub Secret Scanning) to detect leaked production keys. |
| **Public Identifier** | `[0-9a-f]{16}` | 16 hex chars | 8 cryptographically secure random bytes rendered as lowercase hexadecimal. Combined with `gk_live_`, forms the unique 24-character `key_prefix` used for $O(1)$ database/cache indexing. |
| **Delimiter** | `_` | 1 char | Single underscore separator between public prefix and secret component. |
| **Secret Component** | `[A-Za-z0-9_-]{43}` | 43 chars | 32 cryptographically secure random bytes (256 bits entropy) encoded in URL-safe Base64 without padding. |
| **Full API Key** | `gk_live_<publicId>_<secret>` | 68 chars | The complete token issued to the consumer. |

### 2. Cryptographic Architecture & Entropy Rationale

- **CSPRNG Generation**: All public IDs and secret components are generated using `java.security.SecureRandom`.
- **256-Bit Entropy**: The secret component contains $2^{256}$ possible combinations, rendering offline dictionary attacks, rainbow tables, or brute-force enumeration computationally infeasible.
- **Prefix Collision Resistance**: Key generation enforces bounded retry loops (maximum 5 attempts) to guarantee uniqueness on the database `uk_api_keys_key_prefix` constraint.
- **Constant-Time Verification**: API secret digests are compared using `MessageDigest.isEqual()` to prevent timing side-channel attacks during authentication.

### 3. Hashing Strategy: SHA-256 vs. BCrypt

Gatekeeper deliberately differentiates between machine credentials and human credentials:

| Metric | API Key Secrets (Gatekeeper) | Human User Passwords |
|---|---|---|
| **Entropy** | High ($\ge 256$ bits cryptographically random) | Low (typically human-chosen words/patterns) |
| **Hashing Algorithm** | **SHA-256** (Cryptographic Hash) | **BCrypt / Argon2** (Key Derivation Function) |
| **Compute Overhead** | Sub-microsecond ($\approx 1\ \mu\text{s}$) | High work factor ($\approx 100\text{--}500\text{ ms}$) |
| **Gateway Throughput** | Thousands of authentications / sec | Single login session initialization |
| **Dictionary Risk** | Zero (impossible to precompute $2^{256}$ values) | High (requires work factor and salt to slow attacks) |

### 4. API Key Lifecycle States

```
[ Active Consumer ]
       |
       v
+--------------+     Expires At Reached      +--------------+
|    ACTIVE    | --------------------------> |   EXPIRED    |
+--------------+                             +--------------+
       |                                            |
       | Revoke / Consumer Suspended                | Revoke
       v                                            v
+--------------+                             +--------------+
|   REVOKED    | <-------------------------- |   REVOKED    |
+--------------+                             +--------------+
```

1. **Creation**:
   - Generates the full 68-character token.
   - Extracts the 24-character `key_prefix` and computes the SHA-256 `secret_digest`.
   - Returns an `ApiKeyCreationResult` containing the plaintext key **strictly once**. Plaintext secrets are never stored in the database or logged.
2. **Lookup & Validation**:
   - The gateway parses the incoming key, extracts the 24-character prefix in $O(1)$, and performs an indexed database query.
   - Verifies consumer status is `ACTIVE` and key status is `ACTIVE`.
   - Checks expiration timestamp (`expires_at`).
   - Constant-time verifies SHA-256 digest of secret.
   - Authorizes requested scopes against granted scopes.
3. **Revocation**:
   - Idempotent revocation transitions status to `REVOKED` and sets `revoked_at` and `revocation_reason`.
   - Safe against concurrent revocation races via JPA optimistic locking (`@Version`).
4. **Soft Deletion**:
   - Consumers and keys support non-destructive deactivation (`is_deleted = true`, `deleted_at = NOW()`), preserving historical audit integrity for usage events and logs.

### 5. Domain Services Overview (Java Backend Architecture)

- **`ApiKeyGenerator`**: Cryptographic component responsible for producing structured tokens (`gk_live_<publicId>_<secret>`) with `SecureRandom` and validating raw key string format and length.
- **`ApiKeyHasher`**: Cryptographic hashing service performing deterministic SHA-256 hashing and constant-time digest verification using `MessageDigest.isEqual`.
- **`ApiKeyService`**: Primary domain service orchestrating API key generation, prefix collision handling, validation, scope authorization, idempotent revocation, and metadata queries.
- **`ConsumerService`**: Manages consumer lifecycle, plan assignment, email uniqueness, suspension, reactivation, and soft deletion.
- **`SubscriptionPlanService`**: Manages rate-limiting plans, validates token bucket parameters (`bucket_capacity > 0`, `refill_tokens > 0`, `refill_period_seconds > 0`), and provides cached read-only plan lookups.

### 6. Scope Authorization

Key scopes follow a hierarchical format:
- `resource:action` (e.g. `billing:read`, `analytics:export`, `users:write`)
- Validated via regex `^[a-z0-9]+(:[a-z0-9_-]+)*$`
- Validation passes if the key possesses all requested scopes. If any requested scope is missing, an `InsufficientScopeException` is thrown.

### 7. Current Architectural Limitations & Phase 3 Roadmap

- **Database as Source of Truth**: Key validation currently executes against PostgreSQL. In Phase 3, an active-key Redis cache with instant invalidation will be integrated to eliminate database latency on high-frequency gateway requests.
- **Distributed Rate Limiting**: Token bucket rate limiting will be enforced via Redis Lua scripts in Phase 3.

## Administrator Authentication & Spring Security (Phase 2E)

Gatekeeper implements an enterprise-grade administrator authentication and Role-Based Access Control (RBAC) system built on Spring Security and signed JSON Web Tokens (JWT).

### 1. Security Architecture Overview

```
Client (Admin)
      |
      | 1. POST /api/v1/auth/login { username, password }
      v
+-----------------------------+
| AuthController              |
+-----------------------------+
      |
      v
+-----------------------------+
| LoginRateLimiter            | ---> Checks IP/Username Abuse (Fail-Closed: 503)
+-----------------------------+
      |
      v
+-----------------------------+
| AdminAuthenticationService  | ---> Verifies User & BCrypt Hash (Generic 401)
+-----------------------------+
      |
      v
+-----------------------------+
| JwtService (HS256)          | ---> Issues 15-Minute Signed Bearer Token
+-----------------------------+
```

### 2. Administrator Password Security

- **Algorithm**: `BCryptPasswordEncoder` with strength factor `12`.
- **Entropy & Salting**: Passwords use per-user random salts automatically generated and embedded by BCrypt. Work factor 12 enforces $\approx 250\text{--}350\text{ ms}$ computation time per hash, preventing offline GPU/ASIC cracking.
- **Password Complexity Rules**:
  - Minimum 12 characters.
  - At least one uppercase letter (`[A-Z]`).
  - At least one lowercase letter (`[a-z]`).
  - At least one digit (`[0-9]`).
  - At least one special symbol (`[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]`).
- **Memory Safety**: Plaintext passwords are accepted as `char[]` arrays during bootstrap and wiped with `\0` immediately after hashing.
- **Generic Error Responses**: Both non-existent usernames, bad passwords, and deactivated accounts return a uniform HTTP 401 `"Invalid username or password"` to prevent user enumeration attacks.

### 3. Secure Initial Administrator Bootstrap

Gatekeeper strictly forbids default credentials or generating random passwords in startup logs:

- **Controlled Trigger**: Bootstrap is disabled during normal startup. It can **only** run when explicitly invoked via `--gatekeeper.bootstrap.enabled=true`.
- **One-Time Execution**: If any administrator exists (`adminUserRepository.count() > 0`), the bootstrap process aborts immediately.
- **Interactive Local Setup**:
  Run the secure PowerShell bootstrap script:
  ```powershell
  .\setup-admin.ps1
  ```
  This script securely prompts for `username`, `email`, and `password` (using `Read-Host -AsSecureString`), passes them via transient process-only memory, runs the bootstrap runner, and scrubs all memory variables upon completion.
- **No Unauthenticated HTTP Bootstrap**: Initial administrator setup is completely isolated from HTTP endpoints.

### 4. JWT Access Token Design & Signing Key Persistence

- **Signing Algorithm**: Strict `HMAC-SHA256` (`HS256`) via Nimbus JOSE + JWT.
- **Key Persistence**: Local development generates a persistent, cryptographically secure 256-bit signing key saved in `D:\Development\Gatekeeper\.jwt.key` (Git-ignored). Existing keys are preserved across backend restarts, preventing silent token invalidation.
- **Production Key Governance**: Production environments load the secret strictly from `${GATEKEEPER_JWT_SECRET}`. Hardcoded fallback secrets are completely disallowed; keys with $< 256$ bits of entropy fail startup immediately.
- **Token Lifetime**: Fixed 15-minute validity (`access-token-expiration-seconds: 900`).
- **Minimal Claims Structure**:
  ```json
  {
    "sub": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "username": "superadmin",
    "role": "ROLE_SUPER_ADMIN",
    "ver": 1,
    "iss": "gatekeeper-auth",
    "iat": 1700000000,
    "exp": 1700000900
  }
  ```
- **Cryptographic Token Invalidation (Token Version)**:
  Every issued JWT includes a `ver` (token version) claim corresponding to `admin_users.token_version`. Changing an administrator's password increments `token_version` in PostgreSQL, instantly invalidating all previously issued access tokens on subsequent requests.
- **Real-Time Account Status Check**:
  `JwtAuthenticationFilter` validates on every request that the administrator account remains active in PostgreSQL (`admin.isActive() == true`). Deactivating an account revokes access immediately.

### 5. Password-Change Enforcement Workflow

Administrators flagged with `must_change_password = true` have restricted access:
- **Restricted Access**: Any attempt to access standard administrative resources returns **HTTP 403 Forbidden** (`"Password change is required before accessing protected resources. Please change your password at /api/v1/auth/change-password"`).
- **Change Password Endpoint**: `POST /api/v1/auth/change-password` (Authenticated via Bearer token).
  - Verifies current password against stored BCrypt hash.
  - Enforces strong password complexity (min 12 chars, upper, lower, digit, special symbol).
  - Rejects new password identical to current password.
  - Clears `must_change_password = false`, increments `token_version`, updates `password_changed_at`.
  - Returns HTTP 200 with a new valid JWT bearing the updated `ver` claim.

### 6. Login Abuse & Brute-Force Rate Limiting

- **Distributed Enforcement**: Backed by atomic Redis counters (`gatekeeper:auth:abuse:<key>`) with explicit TTL, providing multi-instance consistency across backend replicas. Falls back to in-memory tracking in offline testing environments.
- **Anti-Spoofing & Normalization**:
  - Usernames are normalized to lowercase (`username.toLowerCase().trim()`) to prevent casing evasion attacks.
  - Client IP defaults to `request.getRemoteAddr()`. Forwarded proxy headers (`X-Forwarded-For`) are untrusted unless explicitly enabled via `gatekeeper.security.login.trust-proxy-headers=true`.
- **Threshold**: 5 failed attempts per `client_ip:username` within a 15-minute lockout window.
- **Lockout (HTTP 429)**: Exceeding the threshold triggers HTTP 429 with standard `Retry-After: <seconds>` response header.
- **Fail-Closed Guarantee (HTTP 503)**: If Redis or login protection experiences an infrastructure failure, it fails closed returning HTTP 503 `"Service Unavailable"` to safeguard credentials.

### 7. Role-Based Access Control (RBAC) Permissions Matrix

| Resource / Action | Public | ROLE_SUPER_ADMIN | ROLE_ADMIN | ROLE_ANALYST |
|---|---|---|---|---|
| **Health Check (`GET /api/v1/health`)** | Yes | Yes | Yes | Yes |
| **Admin Login (`POST /api/v1/auth/login`)** | Yes | Yes | Yes | Yes |
| **Admin User Provisioning / Roles** | No | Full Access | No Access | No Access |
| **Subscription Plan Management** | No | Full Access | Full Access | Read-Only |
| **Consumer Management (Create/Update/Suspend)** | No | Full Access | Full Access | Read-Only |
| **API Key Management (Create/Revoke)** | No | Full Access | Full Access | Read-Only (Metadata) |
| **Telemetry & Usage Analytics** | No | Full Access | Full Access | Full Access |

- **Default Fail-Secure**: All endpoints outside `/api/v1/health` and `/api/v1/auth/login` require explicit authentication.
- **Missing / Invalid Token**: Returns **HTTP 401 Unauthorized**.
- **Insufficient Role**: Returns **HTTP 403 Forbidden** (`"Access is denied: insufficient role privileges"`).

### 7. Stateless Architecture & CSRF Decision

- **Session Management**: Configured as `SessionCreationPolicy.STATELESS`. Spring Security never creates HTTP sessions (`JSESSIONID`).
- **CSRF Disabled Rationale**: Cross-Site Request Forgery (CSRF) relies on web browsers automatically appending ambient credentials (such as cookies or HTTP basic auth headers) to cross-origin requests. Gatekeeper authenticates purely via explicit HTTP `Authorization: Bearer <JWT>` headers. Because browsers never automatically attach custom bearer headers cross-origin, standard CSRF exploits are structurally impossible, making disabling CSRF safe and standard.

### 8. Core Conceptual Differences (Backend Interview Knowledge)

#### Authentication vs. Authorization
- **Authentication (AuthN)**: Answering *"Who are you?"* (verifying identity via username/password against BCrypt hashes and issuing a signed JWT).
- **Authorization (AuthZ)**: Answering *"What are you allowed to do?"* (verifying permissions, e.g. checking if `ROLE_ADMIN` has privileges to revoke an API key).

#### BCrypt vs. SHA-256
- **BCrypt**: A slow, salted, adaptive Key Derivation Function (KDF) designed specifically for **low-entropy human passwords**. Its high work factor (configurable rounds) protects against offline GPU/ASIC brute-force dictionary attacks.
- **SHA-256**: A fast, fixed-time cryptographic digest function designed for **high-entropy machine tokens** (e.g., 256-bit API key secrets). It evaluates in sub-microsecond time to enable tens of thousands of API gateway validations per second without CPU exhaustion.

#### JWT vs. API Keys
- **JWT (JSON Web Token)**: Short-lived (15 minutes), signed, client-held credential conveying user identity and role claims for interactive administrative sessions.
- **API Key**: Long-lived, machine-to-machine credential with fixed prefix indexing and constant-time secret validation, scoped to high-frequency automated consumption.

#### HTTP 401 Unauthorized vs. HTTP 403 Forbidden
- **HTTP 401 (Unauthorized / Unauthenticated)**: The client has not provided valid credentials or the token is missing/expired/malformed. *"Please log in."*
- **HTTP 403 (Forbidden)**: The client's identity is authenticated and verified, but their role/permissions do not grant access to the requested resource. *"You are authenticated, but you are not allowed here."*

## Management REST APIs (Phase 2F)

Gatekeeper provides secure REST management APIs for subscription plans, API consumers, and API keys. All endpoints require HTTP Bearer JWT authentication.

### OpenAPI & Interactive Documentation

- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **OpenAPI 3.0 Specification**: `http://localhost:8080/v3/api-docs`

### Endpoint Catalog

| Method | Endpoint | Allowed Roles | Description | Status Code |
|---|---|---|---|---|
| `GET` | `/api/v1/plans` | SUPER_ADMIN, ADMIN, ANALYST | List all active subscription plans | `200 OK` |
| `GET` | `/api/v1/plans/{planId}` | SUPER_ADMIN, ADMIN, ANALYST | Get subscription plan by ID | `200 OK` / `404` |
| `POST` | `/api/v1/consumers` | SUPER_ADMIN, ADMIN | Create a new API consumer | `201 Created` (`Location`) |
| `GET` | `/api/v1/consumers` | SUPER_ADMIN, ADMIN, ANALYST | List consumers with bounded pagination | `200 OK` |
| `GET` | `/api/v1/consumers/{consumerId}` | SUPER_ADMIN, ADMIN, ANALYST | Get consumer details by ID | `200 OK` / `404` |
| `PATCH` | `/api/v1/consumers/{consumerId}/plan` | SUPER_ADMIN, ADMIN | Update consumer subscription plan | `200 OK` / `404` |
| `PATCH` | `/api/v1/consumers/{consumerId}/suspend` | SUPER_ADMIN, ADMIN | Suspend consumer access | `200 OK` / `404` |
| `PATCH` | `/api/v1/consumers/{consumerId}/reactivate` | SUPER_ADMIN, ADMIN | Reactivate suspended consumer | `200 OK` / `404` |
| `DELETE` | `/api/v1/consumers/{consumerId}` | SUPER_ADMIN, ADMIN | Soft-delete consumer (audit preserved) | `204 No Content` |
| `POST` | `/api/v1/consumers/{consumerId}/keys` | SUPER_ADMIN, ADMIN | Generate API key (one-time plaintext) | `201 Created` (`Cache-Control: no-store`) |
| `GET` | `/api/v1/consumers/{consumerId}/keys` | SUPER_ADMIN, ADMIN, ANALYST | List sanitized key metadata for consumer | `200 OK` |
| `GET` | `/api/v1/consumers/{consumerId}/keys/{keyId}` | SUPER_ADMIN, ADMIN, ANALYST | Get sanitized key metadata by ID | `200 OK` / `404` |
| `POST` | `/api/v1/consumers/{consumerId}/keys/{keyId}/revoke` | SUPER_ADMIN, ADMIN | Revoke key with reason (idempotent) | `200 OK` / `404` |

### Security Guarantees

1. **One-Time Plaintext Credential Exposure**: The plaintext API key is delivered **only once** in the HTTP 201 response body of `POST /api/v1/consumers/{consumerId}/keys`. It is never stored in plaintext and is never returned in listing or detail APIs.
2. **Cache Prevention**: Key generation responses enforce `Cache-Control: no-store` to prevent caching of plaintext credentials in intermediate proxies or browser caches.
3. **Cross-Consumer Isolation**: Any key lookup or revocation request where `{keyId}` does not belong to `{consumerId}` is rejected with `HTTP 404 Not Found`.
4. **Transaction Guarantee**: The database transaction is committed before the plaintext API key is returned to the HTTP client.
5. **Bounded Pagination**: Consumer pagination is bounded (max page size: 100, default: 20) with deterministic multi-column ordering (`createdAt DESC, id ASC`).

### Live Verification Script

Run the automated live verification script to test all 10 management steps against the running server:

```powershell
Set-Location D:\Development\Gatekeeper
.\verify-management-apis.ps1
```

## Distributed Token Bucket Rate Limiting Engine (Phase 3A)

Gatekeeper implements an enterprise-grade, distributed Token Bucket rate limiter designed for high-concurrency API gateway environments. The engine is backed by Redis and executes via an atomic Lua script that guarantees sub-millisecond evaluation, zero race conditions, server-authoritative time synchronization, and fail-closed security.

### 1. Token Bucket Architecture & Lua Atomicity

Unlike Fixed Window or Sliding Window counters which suffer from boundary burst vulnerabilities or high memory consumption, the Token Bucket algorithm allows bounded bursts while enforcing a continuous, smooth replenishment rate.

```mermaid
sequenceDiagram
    autonumber
    participant Client as Application / Service
    participant Limiter as RedisTokenBucketRateLimiter
    participant Redis as Redis (gatekeeper-redis:6380)

    Client->>Limiter: consume(consumerId, plan, cost)
    Limiter->>Limiter: Validate inputs & calculate dynamic TTL
    Limiter->>Redis: EVALSHA token_bucket.lua [key] [capacity, refill, period, cost, ttl]
    Note over Redis: Atomic Lua Script Execution
    Redis->>Redis: 1. redis.call('TIME') -> server-authoritative ms
    Redis->>Redis: 2. HMGET tokens, last_refill_ms
    Redis->>Redis: 3. elapsed = now_ms - last_refill_ms (clamp >= 0)
    Redis->>Redis: 4. Add refilled tokens, clamp to capacity
    alt current_tokens >= cost
        Redis->>Redis: Deduct cost, allowed = 1
    else current_tokens < cost
        Redis->>Redis: allowed = 0 (balance unchanged)
        Redis->>Redis: Compute retry_after_sec
    end
    Redis->>Redis: Compute reset_after_sec
    Redis->>Redis: HSET tokens, last_refill_ms
    Redis->>Redis: EXPIRE key ttl_sec
    Redis-->>Limiter: [allowed, tokens_remaining, capacity, retry_after, reset_after]
    Limiter-->>Client: RateLimitDecision
```

### 2. Atomic Redis Script Mechanics (`token_bucket.lua`)

1. **Server-Authoritative Clock**: Time is derived directly from Redis using `redis.call('TIME')` in microseconds:
   $$\text{now\_ms} = (\text{now\_sec} \times 1000) + \lfloor\text{now\_usec} / 1000\rfloor$$
   This eliminates clock skew across multiple distributed Spring Boot instances.
2. **Defensive Clock Handling**: If $\text{elapsed\_ms} < 0$ due to leap seconds or NTP adjustments, elapsed time is safely clamped to 0 to prevent balance corruption.
3. **Continuous Refill**: Refill tokens are added proportionally to the exact millisecond delta:
   $$\text{tokens\_to\_add} = \frac{\text{elapsed\_ms} \times \text{refill\_tokens}}{\text{refill\_period\_sec} \times 1000}$$
4. **Fractional Precision**: The Redis hash stores tokens as floating-point numbers to preserve fractional refill progress across high-frequency requests, while returning integer floors ($\lfloor\text{current\_tokens}\rfloor$) to callers.
5. **Atomic State & TTL Persistence**: Evaluates and updates `tokens` and `last_refill_ms` within a single Redis atomic transaction (`EVALSHA`), guaranteeing zero race conditions between concurrent worker threads.

### 3. Redis Data Structure & Key Design

- **Key Format**: `gatekeeper:ratelimit:consumer:{consumerId}`
- **Storage Type**: Redis Hash (`HMGET` / `HSET`)
  - `tokens`: Current token count (stringified float, e.g., `"59.45"`)
  - `last_refill_ms`: Timestamp of last refill evaluation in milliseconds
- **Multi-Key Consumer Quota Sharing**: All API keys belonging to a consumer resolve to the same `consumerId` bucket key, ensuring a unified rate limit across multiple keys without quota duplication.
- **TTL Lifecycle**: Keys are automatically set with an expiration:
  $$\text{TTL} = \max\left(86400, 2 \times \left\lceil \frac{\text{bucket\_capacity} \times \text{refill\_period\_sec}}{\text{refill\_tokens}} \right\rceil\right)$$
  Inactive consumer buckets naturally expire without creating Redis memory leaks.

### 4. Plan Upgrade and Downgrade Dynamics

- **Plan Upgrade (e.g., FREE 60 $\to$ PRO 600)**:
  - Existing token balance is preserved.
  - The bucket does not give an unearned instant burst to 600 tokens; instead, it immediately begins refilling towards the new 600-token capacity at the upgraded rate (10 tokens/sec).
- **Plan Downgrade (e.g., PRO with 450 tokens $\to$ FREE 60)**:
  - Token balance is immediately clamped to the new plan's lower capacity: $\text{tokens} = \min(60, 450) = 60$.
  - Prevents consumers from retaining excess capacity after downgrading.

### 5. Fail-Closed Security Policy

In accordance with strict zero-trust API security standards:
- If Redis is unreachable, times out, or encounters a connection error, `RedisTokenBucketRateLimiter` catches the exception, logs a structured error, and throws `RateLimitUnavailableException` (**HTTP 503 Service Unavailable**).
- The limiter **never falls back to an in-memory bypass** and **never fails open**, preventing backend exhaustion or unauthorized quota overruns during infrastructure incidents.

### 6. Local Performance Benchmark Results

A high-concurrency stress test was executed using JUnit 5 against the live `gatekeeper-redis` container (`127.0.0.1:6380`):

| Metric | Result |
|---|---|
| **Total Operations** | **5,000 requests** |
| **Allowed Requests** | **5,000 (100%)** |
| **Denied Requests** | **0 (0%)** |
| **Concurrent Worker Threads** | **20 threads** |
| **Total Elapsed Duration** | **1.691 seconds** |
| **Throughput** | **2,956.3 operations / second** |
| **Average Latency** | **6.711 ms** |
| **Median (p50) Latency** | **6.427 ms** |
| **95th Percentile (p95)** | **9.584 ms** |
| **99th Percentile (p99)** | **13.897 ms** |
| **Maximum Latency** | **27.708 ms** |
| **Race Conditions Observed** | **0 (Zero)** |

### 7. Automated Test Suite

The rate limiting engine is verified with 22 automated unit and integration tests (`RedisTokenBucketRateLimiterTest`):
1. Initial bucket capacity and single token consumption
2. Sequential token consumption until exhaustion
3. Empty bucket rejection and zero negative balance
4. Partial refill over time (sub-second millisecond precision)
5. Full refill capping at bucket capacity without over-refill
6. High concurrency stress test (100 simultaneous requests against 60-token bucket: exactly 60 allowed, 40 denied)
7. Consumer isolation (Consumer A cannot affect Consumer B)
8. Multi-key shared quota across distinct API keys
9. Plan upgrade preserving balance
10. Plan downgrade clamping balance to lower capacity
11. Redis key TTL setting and expiration
12. Fail-closed on Redis outage (throwing `RateLimitUnavailableException`)
13. Corrupted Redis state fail-closed (returns `allowed = -1`, throws HTTP 503 `RateLimitUnavailableException`)
14. Malformed Redis state self-healing on legitimate first-time consumer
15. Invalid request cost validation
16. Null parameter validation
17. Retry-After calculation accuracy
18. Reset-After calculation accuracy
19. Reset bucket clearing state in Redis
20. SubscriptionPlan entity adapter overloads
21. Local performance benchmark (5,000 operations across 20 threads)

---

## Phase 3B: External API Key Authentication & HTTP Rate Limit Enforcement

Phase 3B connects consumer API keys to the atomic Redis Token Bucket engine through a dedicated Spring Security HTTP filter pipeline and demonstrates quota enforcement over live HTTP traffic.

### 1. Dual Security Filter Chain Architecture

To maintain strict boundaries between internal administrator management and external consumer traffic, Gatekeeper configures two independent `SecurityFilterChain` beans:

```mermaid
flowchart TD
    ClientReq["Incoming HTTP Request"] --> SecurityFilter["Spring Security Filter Chain Dispatcher"]

    SecurityFilter -->|Path matches /api/v1/gateway/**| GatewayChain["Gateway Security Chain (@Order 1)"]
    SecurityFilter -->|All other paths /api/v1/**| MgmtChain["Management Security Chain (@Order 2)"]

    subgraph Gateway Pipeline
        GatewayChain --> ApiKeyFilter["ConsumerApiKeyAuthenticationFilter"]
        ApiKeyFilter -->|Missing / Invalid / Revoked / Expired| Err401["HTTP 401 Unauthorized (JSON)"]
        ApiKeyFilter -->|Valid Key & Active Consumer| ScopeCheck["Scope Check (gateway:invoke, read:orders)"]
        ScopeCheck -->|Insufficient Scopes| Err403["HTTP 403 Forbidden (No Token Deducted)"]
        ScopeCheck -->|Authorized| RateLimitFilter["ConsumerRateLimitFilter"]
        RateLimitFilter -->|Atomic Lua Eval: allowed=1| Controller["GatewayDemoController (/demo, /orders)"]
        RateLimitFilter -->|Atomic Lua Eval: allowed=0| Err429["HTTP 429 Too Many Requests (Retry-After)"]
        RateLimitFilter -->|Redis Unavailable / Malformed| Err503["HTTP 503 Service Unavailable"]
        Controller -->|HTTP 200 OK| GatewayResp["Response with RateLimit-* Headers"]
    end

    subgraph Management Pipeline
        MgmtChain --> PublicCheck{"Is /api/v1/health or /auth/login?"}
        PublicCheck -->|Yes| PublicEndpoint["Allow Public Access (HTTP 200)"]
        PublicCheck -->|No| JwtFilter["JwtAuthenticationFilter (Bearer JWT)"]
        JwtFilter -->|Valid Admin JWT| MgmtControllers["Plans, Consumers, Keys Management Controllers"]
        JwtFilter -->|Missing / Expired / Invalid| Admin401["HTTP 401 Unauthorized"]
    end
```

### 2. Security Separation & Boundaries

- **Consumer Key $\to$ Management Route**: A consumer API key sent via `X-API-Key` to `/api/v1/consumers` or `/api/v1/plans` is rejected with **HTTP 401 Unauthorized** by the management filter chain (which requires an `Authorization: Bearer <jwt>` header).
- **Admin JWT $\to$ Gateway Route**: An administrator JWT sent via `Authorization: Bearer <jwt>` to `/api/v1/gateway/**` is rejected with **HTTP 401 Unauthorized** by `ConsumerApiKeyAuthenticationFilter` (which requires an `X-API-Key` header).
- **Public Health Route**: `GET /api/v1/health` remains unauthenticated and excluded from both security filters.

### 3. Execution Pipeline & Scope Pre-Authorization

1. **API Key Extraction & Format Verification**:
   - Header: `X-API-Key: gk_live_<prefix>_<secret>`
   - Format: Must match `gk_(live|test)_[a-zA-Z0-9]{8}_[a-zA-Z0-9]{32}` (total 48 chars).
   - Validation: Constant-time SHA-256 secret verification prevents timing attacks.
   - Rejection: Missing, blank, duplicate, malformed, revoked, expired keys, or suspended consumers immediately terminate with standard JSON error responses and HTTP 401.
2. **Scope Enforcement Before Rate Limiting**:
   - Route `GET /api/v1/gateway/demo` requires scope `gateway:invoke`.
   - Route `GET /api/v1/gateway/orders` requires scope `read:orders`.
   - **Crucial Rule**: Scope authorization occurs *prior* to contacting Redis. If a request lacks required scopes, it returns **HTTP 403 Forbidden** immediately with **zero quota deducted**, protecting consumer balances from invalid calls.
3. **Atomic Token Bucket Rate Limiting**:
   - Consumers consume 1 token per authorized request via `RedisTokenBucketRateLimiter`.
   - All keys of the same consumer share the consumer-level token bucket.
4. **Standard HTTP Rate Limit Headers**:
   - Every gateway response includes standard IETF-compliant rate limit headers:
     - `RateLimit-Limit`: Bucket burst capacity.
     - `RateLimit-Remaining`: Floor of remaining token balance.
     - `RateLimit-Reset`: Integer seconds until the bucket is completely full.
   - On quota exhaustion (**HTTP 429 Too Many Requests**):
     - `Retry-After`: Integer seconds until at least 1 token is refilled.
     - Response Body: RFC 7807 problem details JSON with `code: RATE_LIMIT_EXCEEDED`.
5. **Fail-Closed Availability**:
   - If Redis is down or data is corrupt, the system returns **HTTP 503 Service Unavailable** with `code: RATE_LIMIT_SERVICE_UNAVAILABLE`.
   - Quota bypass is never permitted.

### 4. Consumer Demonstration Endpoints

| Endpoint | Method | Required Scope | Description |
|---|---|---|---|
| `/api/v1/gateway/demo` | `GET` | `gateway:invoke` | Returns consumer identity, rate limit tier, and gateway timestamp |
| `/api/v1/gateway/orders` | `GET` | `read:orders` | Demonstrates endpoint-specific scope gating with mock order data |

#### Example Request:

```http
GET /api/v1/gateway/demo HTTP/1.1
Host: localhost:8080
X-API-Key: gk_live_a1b2c3d4_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

#### Example Response (HTTP 200 OK):

```http
HTTP/1.1 200 OK
Content-Type: application/json
RateLimit-Limit: 60
RateLimit-Remaining: 59
RateLimit-Reset: 1

{
  "status": "UP",
  "consumerId": "4a71b123-5e92-4f11-8a90-e38029b3c4d1",
  "consumerName": "Acme Corp",
  "keyPrefix": "a1b2c3d4",
  "plan": "FREE",
  "timestamp": "2026-11-16T22:00:00Z"
}
```

#### Example Response on Quota Exhaustion (HTTP 429 Too Many Requests):

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 1
RateLimit-Limit: 60
RateLimit-Remaining: 0
RateLimit-Reset: 60

{
  "timestamp": "2026-11-16T22:00:05Z",
  "status": 429,
  "error": "Too Many Requests",
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "Rate limit quota exceeded. Retry after 1 seconds.",
  "path": "/api/v1/gateway/demo"
}
```

### 5. Automated Test Suite (149 Passing Tests)

Phase 3B added 20 comprehensive HTTP integration tests in `GatewayRateLimitIntegrationTest`:
1. Valid API key authentication on `/api/v1/gateway/demo` returns HTTP 200 with `RateLimit-*` headers.
2. Missing `X-API-Key` returns HTTP 401 Unauthorized.
3. Empty/whitespace `X-API-Key` returns HTTP 401 Unauthorized.
4. Duplicate `X-API-Key` headers return HTTP 401 Unauthorized.
5. Malformed API key prefix returns HTTP 401 Unauthorized.
6. Unknown API key returns HTTP 401 Unauthorized.
7. Revoked API key returns HTTP 401 Unauthorized.
8. Expired API key returns HTTP 401 Unauthorized.
9. Suspended consumer API key returns HTTP 401 Unauthorized.
10. Soft-deleted consumer API key returns HTTP 401 Unauthorized.
11. Insufficient scope on `/api/v1/gateway/demo` returns HTTP 403 Forbidden.
12. Scope failure deducts zero tokens from Redis token bucket.
13. Endpoint requiring specific scope (`GET /api/v1/gateway/orders`) returns HTTP 200 with `read:orders`.
14. Endpoint requiring specific scope returns HTTP 403 without `read:orders`.
15. Quota exhaustion returns HTTP 429 with `Retry-After` header.
16. Multiple keys of same consumer share the consumer token bucket.
17. Concurrent HTTP requests respect bucket capacity and do not overspend.
18. Administrator JWT token rejected with HTTP 401 on gateway endpoints.
19. Consumer API key rejected with HTTP 401 on management endpoints.
20. Health check endpoint `/api/v1/health` accessible without authentication.

### 6. Live Verification Script

The end-to-end gateway and rate limiting pipeline can be verified using the PowerShell 5.1-compatible test script:

```powershell
.\verify-gateway-rate-limit.ps1
```

The script executes 17 comprehensive verification checks:
1. Health check verification (`GET /api/v1/health`)
2. Secure administrator authentication (`POST /api/v1/auth/login`)
3. Plan query to retrieve `FREE` plan (`GET /api/v1/plans`)
4. Isolated test consumer creation (`POST /api/v1/consumers`)
5. Key A generation (`gateway:invoke`, `read:orders`)
6. Key B generation (`gateway:invoke` only)
7. Key C generation (`metrics:read` lacking `gateway:invoke`)
8. Missing API key rejection (`GET /api/v1/gateway/demo` $\to$ 401)
9. Malformed API key rejection (`GET /api/v1/gateway/demo` $\to$ 401)
10. Scope enforcement check (`GET /api/v1/gateway/demo` with Key C $\to$ 403 with zero quota deducted)
11. Valid request verification with Key A (`RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`)
12. Quota sharing verification with Key B (remaining tokens decremented across keys)
13. Specific scope gating on `/api/v1/gateway/orders` (Key B $\to$ 403, Key A $\to$ 200)
14. Rapid quota exhaustion loop ($\to$ HTTP 429 with `Retry-After`)
15. Immediate key revocation enforcement (Key A revoked $\to$ 401, Key B active)
16. Security boundary isolation (Admin JWT on gateway $\to$ 401; Consumer API key on management $\to$ 401)
17. Consumer soft-deletion lifecycle enforcement (Key B immediately invalidated with 401)




