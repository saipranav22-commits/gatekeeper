# Gatekeeper — Developer Guide & Contribution Standards

This guide provides instructions and conventions for developers modifying or extending Gatekeeper.

---

## 1. Project Organization

```text
Gatekeeper/
├── backend/                  # Gatekeeper Core Gateway (Java 21, Spring Boot 3)
│   ├── src/main/java/com/gatekeeper/
│   │   ├── analytics/        # Async usage event ingestion, DLQ, & time-series
│   │   ├── apikey/           # API key generation (CSPRNG), SHA-256 hashing, scopes
│   │   ├── chaos/            # Fault injection management & experiment controller
│   │   ├── common/           # Shared exceptions, DTOs, & configuration
│   │   ├── consumer/         # Multi-tenant API consumer management
│   │   ├── gateway/          # Route filtering & dynamic reverse proxy
│   │   ├── plan/             # Subscription tiers & quota rate limit models
│   │   ├── policy/           # AST rule evaluation engine & shadow simulation
│   │   ├── ratelimit/        # Atomic Redis Lua token-bucket algorithm
│   │   ├── resilience/       # Resilience4j Circuit Breakers & Semaphore Bulkheads
│   │   ├── routing/          # Reverse proxy controller & header normalization
│   │   └── security/         # Admin JWT auth, BCrypt/Argon2 hashing, login limiter
│   └── pom.xml
├── frontend/                 # Admin Control Plane (React 19, TypeScript, Vite)
│   ├── src/
│   │   ├── components/       # UI components, layout, and reliability workbench
│   │   ├── config/           # App mode toggle (live vs standalone demo)
│   │   ├── context/          # AuthContext, GatewayHealthContext, ToastContext
│   │   ├── demo/             # In-memory mock data stores & demo health services
│   │   ├── lib/              # Axios client, utilities, and date formatters
│   │   ├── pages/            # 9 production dashboards & error views
│   │   ├── services/         # Live API integration service layer
│   │   ├── styles/           # Obsidian Titanium design system design tokens
│   │   ├── test/             # Vitest & React Testing Library test suites
│   │   └── types/            # TypeScript interfaces and polymorphic health types
│   └── package.json
├── services/                 # Upstream Microservices
│   ├── orders-service/       # Orders microservice with fault-injection harness (8081)
│   └── inventory-service/    # Inventory microservice with stock management (8082)
├── docs/                     # Architecture, API, testing, security, and portfolio docs
├── scripts/                  # CI verification and automation scripts
└── docker-compose.yml        # PostgreSQL 16 and Redis 7 local definitions
```

---

## 2. Engineering Conventions

### 2.1 Backend (Java / Spring Boot)
- **Language Level**: Java 21 LTS with record classes, pattern matching, and sealed types where appropriate.
- **Dependency Injection**: Constructor injection with `@RequiredArgsConstructor` (Lombok) or explicit final fields. Field injection (`@Autowired`) is strictly prohibited.
- **Error Handling**: Use RFC 7807 `ProblemDetail` responses wrapped in `ResponseEntity<ProblemDetail>` via `@ControllerAdvice`.
- **Database Access**: Spring Data JPA repositories with explicit method naming or native queries for bulk operations.
- **Async Execution**: Asynchronous tasks must run on configured `ThreadPoolTaskExecutor` beans with bounded queues.

### 2.2 Frontend (React / TypeScript)
- **Strict Typing**: Zero tolerance for `any` types. All API responses must map to interfaces in `src/types/`.
- **Design Tokens**: Adhere strictly to the Obsidian Titanium design system defined in `src/styles/tokens.css`.
  - Primary Background: Obsidian Slate (`#0B0F19`)
  - Surface: Titanium Charcoal (`#131B2E`)
  - Accent: Electric Cyan (`#06B6D4`)
  - Semantic Status: Emerald (`#10B981` Healthy), Amber (`#F59E0B` Degraded), Crimson (`#EF4444` Unhealthy)
- **Component Design**: Functional components with custom hooks. Reusable primitives reside in `src/components/ui/`.
- **Dual-Mode Parity**: Any new UI feature or service call must support both live API mode (`src/services/`) and demo mode (`src/demo/services/`).

---

## 3. How-To Guides

### 3.1 Adding a New Upstream Route to the Gateway
1. Open `backend/src/main/resources/application.yml`.
2. Add the route under the resilience configuration:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      route-payment-service:
        slidingWindowSize: 100
        failureRateThreshold: 50.0
        waitDurationInOpenState: 10000ms
  bulkhead:
    instances:
      route-payment-service:
        maxConcurrentCalls: 30
```
3. Register the route mapping in `com.gatekeeper.gateway.filter.RouteLocatorConfig`.
4. Add the corresponding frontend route configuration in `frontend/src/services/resilienceService.ts` and `frontend/src/demo/data/demoResilienceData.ts`.

### 3.2 Adding a New Policy Condition Rule
1. Open `backend/src/main/java/com/gatekeeper/policy/engine/RuleConditionEvaluator.java`.
2. Add your AST predicate handler (e.g. evaluating request query parameters or payload body).
3. Add a unit test in `RuleConditionEvaluatorTest.java`.
4. Update Policy Studio schema in `frontend/src/pages/PolicyStudio.tsx`.

---

## 4. Local Testing & Verification

### 4.1 Frontend Test Suite
From `frontend/`:
```bash
# Run all 24 test suites (147 tests)
npm test -- --run

# Run TypeScript type check
npx tsc -b

# Run ESLint validation
npm run lint

# Run production build
npm run build

# Run standalone demo build
npm run build:demo
```

### 4.2 Backend Test Suite
From `backend/`:
```bash
# Run unit and integration tests
mvn test
```

---

## 5. Git Commit & Pull Request Conventions

We follow the **Conventional Commits** specification:
- `feat(scope)`: New user-facing or architectural capability.
- `fix(scope)`: Bug fix or regression resolution.
- `docs(scope)`: Documentation updates or additions.
- `test(scope)`: Adding or refining test suites.
- `chore(scope)`: Build scripts, package updates, or repository maintenance.

### Pre-PR Checklist:
- [ ] All 147 Vitest tests pass cleanly.
- [ ] TypeScript (`tsc -b`) compiles without errors.
- [ ] ESLint passes with zero warnings.
- [ ] Both `build` and `build:demo` succeed.
- [ ] No secrets or unmasked tokens committed (verified by Gitleaks).
