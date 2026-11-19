# Gatekeeper Environment Variables & Configuration Reference

This document provides a comprehensive reference of all configuration options across the Gatekeeper gateway, microservices, database, and frontend tiers.

## Summary Matrix

| Variable Name | Service | Default Value | Sensitivity | Production Recommendation | Description |
|---|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Gateway, Services | `prod` | Low | Always set to `prod` | Activates production configuration. Prevents any chaos or fault beans from loading. |
| `POSTGRES_DB` | Postgres, Gateway | `gatekeeper` | Low | Standard database name | PostgreSQL logical database name. |
| `POSTGRES_USER` | Postgres, Gateway | `gatekeeper_app` | Medium | Use dedicated non-superuser | Database username for application connection pool. |
| `POSTGRES_PASSWORD` | Postgres, Gateway | *None (Required)* | **Critical** | Strong 24+ char secret | Master password for PostgreSQL application role. |
| `DB_HOST` | Gateway | `postgres` | Low | Internal container hostname | Hostname of the PostgreSQL service. |
| `DB_PORT` | Gateway | `5432` | Low | `5432` | Port of the PostgreSQL service. |
| `DB_POOL_MAX` | Gateway | `10` | Low | `10` (up to `20` for larger VM) | HikariCP maximum connection pool size. |
| `DB_POOL_MIN_IDLE` | Gateway | `2` | Low | `2` | HikariCP minimum idle connections. |
| `REDIS_PASSWORD` | Redis, Gateway | *None (Required)* | **Critical** | Strong 24+ char secret | Authentication token for Redis server (`requirepass`). |
| `REDIS_HOST` | Gateway | `redis` | Low | Internal container hostname | Hostname of Redis service. |
| `REDIS_PORT` | Gateway | `6379` | Low | `6379` | Port of Redis service. |
| `REDIS_TIMEOUT` | Gateway | `2000ms` | Low | `2000ms` | Network timeout for Redis operations. |
| `GATEKEEPER_JWT_SECRET` | Gateway | *None (Required)* | **Critical** | 32+ bytes high-entropy hex/base64 | Secret key used for signing and verifying HMAC-SHA256 JWTs. |
| `GATEKEEPER_JWT_ISSUER` | Gateway | `gatekeeper-auth` | Low | Unique domain identifier | Issuer claim (`iss`) for validation. |
| `GATEKEEPER_JWT_EXPIRATION_SECONDS` | Gateway | `900` (15m) | Low | `900` | Expiration window for access tokens. |
| `GATEKEEPER_LOGIN_MAX_ATTEMPTS` | Gateway | `5` | Low | `5` | Failed login threshold before triggering account lockout. |
| `GATEKEEPER_LOGIN_LOCKOUT_SECONDS`| Gateway | `900` (15m) | Low | `900` | Account lockout duration upon repeated failed logins. |
| `ORDERS_HOST` | Gateway | `orders` | Low | Container hostname | Added to Gateway SSRF allowed hosts list. |
| `ORDERS_UPSTREAM_URI` | Gateway | `http://orders:8081` | Low | Container URI | Target URI for orders proxy route. |
| `INVENTORY_HOST` | Gateway | `inventory` | Low | Container hostname | Added to Gateway SSRF allowed hosts list. |
| `INVENTORY_UPSTREAM_URI` | Gateway | `http://inventory:8082` | Low | Container URI | Target URI for inventory proxy route. |
| `SERVER_PORT` | Gateway | `8080` | Low | `8080` | Internal HTTP listening port for Gateway. |
| `ORDERS_PORT` | Orders | `8081` | Low | `8081` | Internal HTTP listening port for Orders microservice. |
| `INVENTORY_PORT` | Inventory | `8082` | Low | `8082` | Internal HTTP listening port for Inventory microservice. |
| `GATEWAY_PORT` | Host Ingress | `8080` | Low | `8080` | Host port mapped to Gateway container. |
| `FRONTEND_PORT` | Host Ingress | `3000` | Low | `3000` or `80` | Host port mapped to Frontend Nginx container. |
| `VITE_API_BASE_URL` | Frontend | `""` (empty) | Low | Empty for same-origin proxy | Base URL for API client. Empty string routes to `/api/` on same origin. |
| `JAVA_OPTS` | Gateway, Services | `-XX:MaxRAMPercentage=75.0 ...` | Low | See memory guidelines | Container JVM runtime tuning flags. |

---

## Secret Generation Commands

To generate cryptographically secure production credentials on Linux, macOS, or Windows Git Bash / WSL:

```bash
# PostgreSQL Password
openssl rand -base64 24

# Redis Password
openssl rand -base64 24

# JWT HMAC-SHA256 Signing Secret (64 hex characters = 256 bits)
openssl rand -hex 32
```

In Windows PowerShell 5.1:

```powershell
# 32-byte secure random string
$bytes = New-Object byte[] 32
(New-Object System.Security.Cryptography.RNGCryptoServiceProvider).GetBytes($bytes)
[System.BitConverter]::ToString($bytes).Replace("-", "").ToLower()
```
