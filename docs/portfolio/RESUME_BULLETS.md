# Gatekeeper — Impact-Driven Resume Bullet Points

Tailor your resume with these action-oriented, quantified bullet points categorized by role.

---

## 1. Senior Backend / Distributed Systems Engineer (Java / Spring Boot / Redis)

- **Architected and implemented Gatekeeper**, an enterprise API Gateway and intelligent traffic control plane in **Java 21 and Spring Boot 3**, supporting multi-tenant rate limiting and fault tolerance.
- **Engineered an atomic distributed token-bucket rate limiter** using custom **Redis Lua scripts**, eliminating distributed locking and race conditions while achieving sub-2ms p99 evaluation latency under high concurrency.
- **Integrated Resilience4j Circuit Breakers and Semaphore Bulkheads**, achieving complete fault isolation across downstream microservices and preventing cascading failures during 100% upstream outage simulations.
- **Developed an explainable policy governance engine** utilizing an Abstract Syntax Tree (AST) evaluator that provides dry-run simulation, immutable versioning, instant rollback, and full decision audit traces.
- **Designed a high-throughput asynchronous usage analytics pipeline** with micro-batching and dead-letter queue (DLQ) recovery, offloading logging from the critical proxy path and reducing database IOPS by 95%.
- **Implemented zero-trust API key lifecycle management** using cryptographically secure random entropy (CSPRNG), SHA-256 hashed storage, and prefix indexing with single-reveal plaintext provisioning.

---

## 2. Senior Full-Stack Engineer (React / TypeScript / Spring Boot)

- **Engineered an industrial-grade API management control plane** in **React 18, TypeScript, and Tailwind CSS**, featuring 9 real-time operational dashboards adhering to the custom Obsidian Titanium design system.
- **Developed a dual-mode service architecture** enabling instant toggling between live Spring Boot REST APIs and an isolated, in-memory recruiter demonstration mode with zero cloud or backend dependencies.
- **Implemented an interactive distributed health-state simulation engine** that dynamically reflects system degradation, circuit breaker state transitions, and localized visual severity hierarchies across the entire UI.
- **Architected a universal keyboard-driven navigation palette (`Ctrl + K`)**, breadcrumb navigation, and responsive layouts tested across 5 viewport breakpoints (320px mobile to 1440px desktop).
- **Authored 147 comprehensive Vitest and React Testing Library tests** across 24 test suites, achieving 100% component stability, regression prevention, and contract normalization.
- **Built explainable Policy Studio and Chaos Engineering interfaces**, allowing operators to visualize upstream dependencies, run dry-run simulations, and trigger manual circuit breaker resets.

---

## 3. Senior DevOps / Platform / Site Reliability Engineer (SRE)

- **Constructed fully automated CI/CD pipelines** using GitHub Actions, enforcing multi-stage Docker builds, TypeScript typechecking, ESLint validation, automated unit/integration tests, and Gitleaks secret scanning.
- **Implemented a controlled chaos testing harness** that simulates downstream network latency, HTTP 500 fault injection, and connection timeouts, empirically verifying automated circuit breaker recovery loops.
- **Designed a containerized local development ecosystem** via Docker Compose orchestrating PostgreSQL 16, Redis 7, Spring Boot Gateway, and mock microservices with host bind mounts.
- **Established zero-tolerance DevSecOps policies**, integrating pre-commit hooks and CI Gitleaks scanning to guarantee 0 credential leaks across all Git history while supporting safe synthetic test data.
- **Configured enterprise web security headers** including Content Security Policy (CSP), HTTP Strict Transport Security (HSTS), X-Frame-Options DENY, and IP-based sliding window brute-force mitigation.
