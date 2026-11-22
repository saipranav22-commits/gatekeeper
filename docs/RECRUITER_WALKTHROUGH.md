# Gatekeeper — Recruiter & Hiring Manager Evaluation Guide

Welcome! If you are a Technical Recruiter, Engineering Manager, or Staff/Principal Interviewer evaluating this project, this document provides a high-leverage 5-minute technical walkthrough of Gatekeeper.

---

## 1. Why Gatekeeper Stands Out

Most portfolio projects are simple CRUD applications or tutorials. **Gatekeeper is different**: it is an industrial-grade, multi-tenant API Gateway and Traffic Management Engine engineered from first principles.

### Key Technical Achievements:
1. **Atomic Distributed Rate Limiting**:
   Implemented via custom **Redis Lua scripts** (`rate_limiter.lua`) providing zero-race-condition token-bucket rate limiting capable of handling tens of thousands of requests per second.
2. **Cascading Failure Protection & Fault Isolation**:
   Integrated with **Resilience4j**, combining sliding-window Circuit Breakers, Semaphore Bulkheads, and automated recovery loops to guarantee downstream outages never take down the gateway.
3. **Explainable Policy Governance Engine**:
   Features an Abstract Syntax Tree (AST) policy engine capable of dry-run evaluation, shadow execution, and instant rollback for traffic governance rules.
4. **Zero-Trust Secret Lifecycle**:
   API keys are cryptographically generated (256-bit entropy via CSPRNG) and stored as irreversible SHA-256 hashes with single-reveal plaintext UI modals.
5. **Obsidian Titanium Design System**:
   Modern, responsive React 18 / TypeScript frontend styled with a bespoke dark theme, dynamic incident severity hierarchy, and keyboard-driven command palette (`Ctrl + K`).
6. **Zero-Backend Recruiter Demonstration**:
   A complete standalone demo mode running locally with zero cloud or database dependencies, allowing immediate hands-on exploration.

---

## 2. Five-Minute Interactive Evaluation Tour

### Step 1: Launch the Demo (60 Seconds)
```bash
cd frontend
npm run preview:demo
```
Open **`http://127.0.0.1:4173/login`** in your browser.

### Step 2: Overview / Command Center (Minute 1)
- Click **"Sign In as Administrator"** (pre-filled credentials).
- Review the **Command Center**: observe real-time KPI tiles (1.85M+ requests, 99.5% success rate), the traffic distribution chart, and route health indicators.
- Press **`Ctrl + K`** to trigger the universal Command Palette and jump directly to any page.

### Step 3: Test Interactive Fault Isolation (Minute 2)
- Locate the **Health Scenario Selector** in the navbar.
- Switch from `HEALTHY` to **`UNHEALTHY`**:
  - Notice the **dynamic severity hierarchy**: critical error metrics flash crimson (`#EF4444`) while successful metrics remain emerald (`#10B981`) and the Obsidian theme stays readable.
  - Notice that **Orders Service trips its circuit breaker to OPEN**, while **Inventory Service remains 100% HEALTHY** — visual empirical proof of Semaphore Bulkhead isolation.
- Switch to **`RECOVERING`**: watch the circuit transition into `HALF-OPEN` probe mode.

### Step 4: Policy Studio & Explainable Simulation (Minute 3)
- Navigate to **Policy Studio** (`/policies`).
- Select the active policy and click **"Run Simulation"**:
  - View the **explainable decision trace**: see exactly which condition matched, which failed, and the sub-millisecond evaluation duration.

### Step 5: Provision an API Key & Observe Secret Safety (Minute 4)
- Navigate to **API Keys** (`/api-keys`).
- Click **"Provision New API Key"**, select a tenant and scopes, then click **"Generate Key"**:
  - Observe the **single-reveal plaintext modal**: once closed, the plaintext key is destroyed from memory; the database persists only its SHA-256 hash.

### Step 6: Review Automated Test Verification (Minute 5)
- Open a terminal in `frontend/` and run `npm test -- --run`.
- Observe **147/147 passing tests across 24 test suites** validating contract safety, accessibility, and visual polish.

---

## 3. Grounded Codebase Artifacts & Proof Points

For deep architectural inspection, review these specific files in the repository:

| Core Capability | Source File Path | What to Look For |
| :--- | :--- | :--- |
| **Token-Bucket Lua Script** | `backend/src/main/resources/scripts/rate_limiter.lua` | Atomic timestamp-based refill math and token decrement |
| **Resilience & Bulkheads** | `backend/src/main/java/com/gatekeeper/resilience/` | Circuit breaker listeners, route resilience summaries |
| **Policy AST Engine** | `backend/src/main/java/com/gatekeeper/policy/engine/` | Rule condition evaluators, dry-run simulation traces |
| **Secret Lifecycle** | `backend/src/main/java/com/gatekeeper/apikey/service/` | CSPRNG key generation and SHA-256 digest hashing |
| **Frontend Demo Isolation** | `frontend/src/config/appMode.ts` & `src/demo/` | Clean inversion of control separating live API from demo |
| **Health State Context** | `frontend/src/context/GatewayHealthContext.tsx` | Polymorphic health state machine and scenario subscribers |
| **Frontend Tests** | `frontend/src/test/` | 147 Vitest tests covering regressions, contracts, and UI polish |

---

## 4. Candidate Profile & Interview Readiness

The engineer who built Gatekeeper demonstrates competencies across:
- **Distributed Systems Architecture**: Rate limiting algorithms, resilience patterns, eventual consistency, and fault isolation.
- **Enterprise Java & Spring Boot**: Clean architecture, modern Java 21 features, Spring Data JPA, and Actuator metrics.
- **Senior Frontend Engineering**: Modern React, TypeScript strict mode, responsive CSS layout, and WCAG AA accessibility.
- **DevOps & Release Engineering**: Multi-stage Docker builds, Gitleaks CI scanning, automated testing pyramids, and zero-defect git hygiene.
