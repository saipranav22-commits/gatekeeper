# Local Container Stack Validation Guide

This guide describes how to validate the production deployment stack (`docker-compose.deploy.yml`) locally on a development machine without disturbing existing development containers (`gatekeeper-postgres` on port 5434 and `gatekeeper-redis` on port 6380).

## 1. Safety and Coexistence Guarantees

The deployment stack has been strictly isolated:
- **Project Name**: `gatekeeper-deploy` (isolated namespace).
- **Network**: `gatekeeper-deploy-network` (bridge network distinct from `gatekeeper-network`).
- **Data Volumes**: `gatekeeper_deploy_pgdata` and `gatekeeper_deploy_redisdata` (isolated named volumes; never binds to `database-data` or `docker-data`).
- **Exposed Host Ports**: Only Gateway `:8080` and Frontend `:3000`. PostgreSQL and Redis are internal to the Docker network and do not bind to any host ports.

> [!WARNING]
> Never execute `docker compose down -v` without specifying the project file. Running bare `docker compose down -v` might affect the default `gatekeeper` development volumes. Always specify `-f docker-compose.deploy.yml`.

---

## 2. Pre-requisite Configuration

Create a local deployment environment file by copying `.env.example`:

```powershell
# In PowerShell:
Copy-Item .env.example .env.deploy
```

Edit `.env.deploy` to configure validation secrets:

```dotenv
POSTGRES_DB=gatekeeper
POSTGRES_USER=gatekeeper_app
POSTGRES_PASSWORD=local_validation_db_password_1234
REDIS_PASSWORD=local_validation_redis_password_1234
GATEKEEPER_JWT_SECRET=local_validation_jwt_secret_32bytes_entropy_key!
SPRING_PROFILES_ACTIVE=prod
GATEWAY_PORT=8080
FRONTEND_PORT=3000
```

---

## 3. Configuration & Syntax Validation

To validate the Compose specification without spinning up containers:

```powershell
docker compose --env-file .env.deploy -f docker-compose.deploy.yml config
```

Expected output: Complete resolved YAML configuration tree with exit code 0.

---

## 4. Building Production Images

Build each container stage to verify build reproducibility and layer caching:

```powershell
docker compose --env-file .env.deploy -f docker-compose.deploy.yml build
```

Verify that all 4 custom images build successfully:
- `gatekeeper-deploy-orders`
- `gatekeeper-deploy-inventory`
- `gatekeeper-deploy-gateway`
- `gatekeeper-deploy-frontend`

---

## 5. Starting the Stack (Optional Live Test)

To start the isolated deployment stack in the background:

```powershell
docker compose --env-file .env.deploy -f docker-compose.deploy.yml up -d
```

Monitor service health and readiness:

```powershell
docker compose --env-file .env.deploy -f docker-compose.deploy.yml ps
```

All 6 services will progress to `healthy` state:
- `gatekeeper-deploy-postgres` (healthy)
- `gatekeeper-deploy-redis` (healthy)
- `gatekeeper-deploy-orders` (healthy)
- `gatekeeper-deploy-inventory` (healthy)
- `gatekeeper-deploy-gateway` (healthy)
- `gatekeeper-deploy-frontend` (healthy)

---

## 6. Functional Verification Endpoints

1. **Frontend Dashboard**:
   Open browser at `http://localhost:3000`. Verify that the dashboard loads and `/healthz` returns `healthy`.
2. **Gateway Healthcheck**:
   `Invoke-RestMethod http://localhost:8080/actuator/health` -> `{"status":"UP"}`
3. **SSRF-Safe Proxy Route (Orders)**:
   `Invoke-RestMethod http://localhost:8080/api/v1/proxy/orders` (requires valid API key or token).
4. **Chaos Lockout Verification**:
   `Invoke-RestMethod http://localhost:8080/api/v1/chaos/experiments`
   Expected response: **HTTP 404 Not Found** (confirming zero chaos beans are active in the `prod` profile).

---

## 7. Graceful Teardown

To shut down the deployment stack and release memory without deleting persisted volumes:

```powershell
docker compose --env-file .env.deploy -f docker-compose.deploy.yml down
```

To clean up volumes created exclusively for this test:

```powershell
docker compose --env-file .env.deploy -f docker-compose.deploy.yml down -v
```
*(This only deletes `gatekeeper_deploy_pgdata` and `gatekeeper_deploy_redisdata`; it leaves local dev databases untouched.)*
