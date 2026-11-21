# Gatekeeper — Troubleshooting & Operational Runbook

This guide diagnoses and resolves common operational, setup, and runtime issues.

---

## 1. Port Conflicts

Gatekeeper and its infrastructure components listen on several local ports:

| Service | Port | Common Conflicting Applications |
| :--- | :--- | :--- |
| **Gatekeeper Gateway** | `8080` | Jenkins, Tomcat, existing Spring Boot apps |
| **Orders Microservice** | `8081` | Secondary dev services, McAfee/Norton security agents |
| **Inventory Microservice** | `8082` | Secondary dev services |
| **PostgreSQL (Container)** | `5434` (host) | Local standalone PostgreSQL installations (usually 5432) |
| **Redis (Container)** | `6380` (host) | Local standalone Redis servers (usually 6379) |
| **Vite Dev Server** | `5173` | Other Vite or Webpack dev servers |
| **Vite Demo Preview** | `4173` | Previous Vite preview processes |

### Diagnosing Conflicting Ports (Windows PowerShell):
```powershell
Get-NetTCPConnection -LocalPort 8080, 8081, 8082, 5434, 6380, 5173, 4173 -ErrorAction SilentlyContinue |
    Select-Object LocalPort, State, OwningProcess |
    ForEach-Object {
        $proc = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue
        [PSCustomObject]@{
            Port = $_.LocalPort
            PID = $_.OwningProcess
            Name = $proc.ProcessName
        }
    }
```

### Freeing a Bound Port:
```powershell
# Terminate process by PID (e.g. PID 1234)
Stop-Process -Id 1234 -Force
```

---

## 2. Docker & Container Infrastructure Issues

### Issue: PostgreSQL Container Fails to Start
- **Symptoms**: `docker compose up -d` exits with error or container restarts continuously.
- **Root Cause**: Directory permission collision or corrupted data directory on host bind mount (`D:\Development\database-data\gatekeeper`).
- **Resolution**:
  1. Check Docker logs: `docker compose logs postgres`
  2. If data is corrupted in dev:
     ```powershell
     docker compose down -v
     docker compose up -d
     .\setup-admin.ps1
     ```

### Issue: Redis Authentication Error
- **Symptoms**: Gatekeeper logs show `WRONGPASS invalid username-password pair or user is disabled`.
- **Root Cause**: The Redis password in Gatekeeper's `application.yml` or `.env` does not match the Redis container start command.
- **Resolution**:
  1. Ensure you have sourced `. .\env.ps1`.
  2. Verify that `REDIS_PASSWORD` matches the value configured in `docker-compose.yml` (`redis_dev_secret`).

---

## 3. Frontend & Build Issues

### Issue: `npm run preview:demo` Returns `dist/index.html not found`
- **Symptoms**: Vite preview fails to start.
- **Root Cause**: Production demo bundle has not been built yet.
- **Resolution**:
  ```bash
  cd frontend
  npm run build:demo
  npm run preview:demo
  ```

### Issue: Node Module or TypeScript Resolution Errors
- **Symptoms**: `tsc -b` reports missing modules or syntax errors.
- **Resolution**:
  ```bash
  cd frontend
  # Clear vite cache
  rm -rf node_modules/.vite
  npm run build
  ```

---

## 4. Backend & Spring Boot Issues

### Issue: ClassLoader or Reflective Access Warnings on Java 21
- **Symptoms**: Warnings regarding illegal reflective access by CGLIB or ByteBuddy during startup.
- **Explanation**: Java 21 enforces strict module boundaries. These are informational warnings and do not affect runtime functionality.
- **Resolution**: Ensure you are running with Eclipse Temurin OpenJDK 21.0.x and Maven 3.9+.

### Issue: Gateway Returns `503 Service Unavailable` for Proxied Routes
- **Symptoms**: Requests to `http://localhost:8080/api/v1/proxy/orders` return 503.
- **Root Cause**: Downstream Orders Service (`http://localhost:8081`) is not running, or its circuit breaker is currently in `OPEN` state.
- **Resolution**:
  1. Check Orders Service health: `curl http://localhost:8081/health`
  2. Start Orders Service if not running:
     ```bash
     cd services/orders-service && mvn spring-boot:run
     ```
  3. If circuit breaker is stuck open, reset it via management API:
     ```bash
     curl -X POST http://localhost:8080/api/v1/resilience/routes/route-orders-service/reset
     ```
