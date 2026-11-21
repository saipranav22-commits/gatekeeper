# Gatekeeper — Getting Started Guide

Welcome to Gatekeeper! This guide will help you launch and explore Gatekeeper in your local environment.

Depending on your goals, choose one of the two execution workflows:
- **Workflow A: Standalone Recruiter Demonstration (Fastest — 60 Seconds)**:
  Requires only Node.js. No Docker, Java, PostgreSQL, Redis, or cloud services needed.
- **Workflow B: Full Local Microservice Stack**:
  Launches the entire real-world distributed system (PostgreSQL 16, Redis 7, Spring Boot Gateway, Orders Microservice, Inventory Microservice, and React Frontend).

---

## Prerequisites Overview

| Requirement | Workflow A (Standalone Demo) | Workflow B (Full Microservice Stack) |
| :--- | :--- | :--- |
| **Node.js** | 20.x or later (LTS recommended) | 20.x or later |
| **npm** | 10.x or later | 10.x or later |
| **Java JDK** | *Not Required* | Eclipse Temurin OpenJDK 21 |
| **Apache Maven** | *Not Required* | 3.9.x or later |
| **Docker & Docker Compose** | *Not Required* | Docker Desktop 4.x+ |
| **RAM** | ~300 MB | ~4-6 GB |

---

## Workflow A: Standalone Recruiter Demonstration (60 Seconds)

The standalone demonstration provides an interactive, high-fidelity experience of the entire Gatekeeper control plane with realistic mock telemetry, dynamic health-state switching, and zero external dependencies.

### Step 1: Navigate to Frontend Directory
```bash
cd frontend
```

### Step 2: Install Node Dependencies
```bash
npm install
```

### Step 3: Launch Static Recruiter Demo
```bash
npm run preview:demo
```
*Alternatively, to run with hot-module reloading during development:*
```bash
npm run dev:demo
```

### Step 4: Open in Your Browser
Visit:
```text
http://127.0.0.1:4173/login
```
*(or port `5173` if running `npm run dev:demo`)*

### Step 5: Explore the Demo
- **Login**: Pre-filled administrator credentials are provided on the login screen. Click **"Sign In"** to access the dashboard.
- **Interactive Health Scenarios**: Locate the health scenario ribbon at the top of the screen to switch between:
  - `HEALTHY` (Nominal operation)
  - `DEGRADED` (42.5% failure in Orders Service; amber warnings)
  - `UNHEALTHY` (Orders Circuit Breaker OPEN; high-contrast localized red alerts)
  - `RECOVERING` (Circuit Breaker transitioning to HALF-OPEN)
- **Navigation**: Explore all 9 views via the sidebar or press `Ctrl + K` to open the Command Palette.

---

## Workflow B: Full Local Microservice Stack

Run the genuine distributed Java 21, Spring Boot 3, PostgreSQL 16, and Redis 7 architecture.

### Step 1: Environment Initialization (Windows PowerShell)
Gatekeeper provides an environment helper script that configures JDK, Maven, and local storage variables without polluting your global environment:

```powershell
# From the repository root:
. .\env.ps1
```

*For Linux / macOS bash environments:*
```bash
export JAVA_HOME=/path/to/jdk-21
export PATH=$JAVA_HOME/bin:$PATH
export DB_USERNAME=gatekeeper_app
export DB_PASSWORD=gatekeeper_dev_secret
export REDIS_PASSWORD=redis_dev_secret
```

### Step 2: Start PostgreSQL & Redis Infrastructure
Start the isolated Docker containers:

```bash
docker compose up -d
```

Verify containers are running:
```bash
docker compose ps
```
- **PostgreSQL 16**: Accessible at `localhost:5434` (mapped from container port `5432`).
- **Redis 7**: Accessible at `localhost:6380` (mapped from container port `6379`).

### Step 3: Bootstrap Database & Initial Admin User
Execute the automated database bootstrap script:

```powershell
.\setup-admin.ps1
```
This applies database migrations, creates initial subscription tiers (`Starter`, `Pro`, `Enterprise`), registers sample consumers, and initializes the `admin` superuser.

### Step 4: Start Downstream Microservices

Open two separate terminal sessions:

**Terminal 1 — Orders Microservice (Port 8081):**
```bash
cd services/orders-service
mvn spring-boot:run
```

**Terminal 2 — Inventory Microservice (Port 8082):**
```bash
cd services/inventory-service
mvn spring-boot:run
```

Verify microservice health:
- `curl http://localhost:8081/health`
- `curl http://localhost:8082/health`

### Step 5: Start Gatekeeper API Gateway (Port 8080)

**Terminal 3 — Gatekeeper Core:**
```bash
cd backend
mvn spring-boot:run
```

Verify gateway health:
```bash
curl http://localhost:8080/api/v1/health
```

### Step 6: Start React Frontend in Live Mode

**Terminal 4 — Live Frontend:**
```bash
cd frontend
npm run dev
```

Open:
```text
http://127.0.0.1:5173
```
Log in using:
- **Username**: `admin`
- **Password**: `Password123!`

---

## Verification & Smoke Testing

To test real API routing, rate limiting, and circuit breaking:

```powershell
# Test live reverse proxy routing through the gateway
.\verify-real-api-routing.ps1

# Test token-bucket rate limiting enforcement
.\verify-gateway-rate-limit.ps1

# Test circuit breaker tripping under simulated downstream failure
.\verify-resilience-engine.ps1
```

---

## Port Allocation Reference

| Service | Host Port | Internal Container Port | Description |
| :--- | :--- | :--- | :--- |
| **Gatekeeper Gateway** | `8080` | `8080` | Edge reverse proxy, auth, and rate limiter |
| **Orders Microservice** | `8081` | `8081` | Downstream order processing service |
| **Inventory Microservice** | `8082` | `8082` | Downstream catalog & stock service |
| **PostgreSQL 16** | `5434` | `5432` | Relational store for tenants, keys, audit |
| **Redis 7** | `6380` | `6379` | Token bucket Lua cache and auth cache |
| **React Frontend (Dev)** | `5173` | — | Vite dev server with hot module reload |
| **React Frontend (Demo Preview)** | `4173` | — | Vite production static preview |
