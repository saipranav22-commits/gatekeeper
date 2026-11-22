# LinkedIn Project Showcase — Gatekeeper

*Copy and adapt the post below for LinkedIn, Twitter/X, or your engineering blog.*

---

🚀 **Proud to share my latest engineering project: Gatekeeper — an Enterprise API Gateway & Intelligent Traffic Control Plane built from scratch with Java 21, Spring Boot 3, Redis, PostgreSQL, and React 18 / TypeScript.**

When building high-throughput microservice ecosystems, the API gateway is your first and last line of defense. Without robust edge intelligence, a single misbehaving tenant or downstream latency spike can trigger cascading failures across your entire platform.

I engineered **Gatekeeper** to solve these exact distributed systems challenges from first principles:

⚡ **Distributed Atomic Rate Limiting (Redis Lua)**
Instead of relying on JVM-local memory or slow multi-step database locks, I authored custom Redis Lua scripts that atomically calculate token replenishment on-the-fly. The result? Zero race conditions, seamless multi-tenant quota enforcement, and sub-2ms p99 latency under heavy concurrent load.

🛡️ **Cascading Failure Protection & Fault Isolation (Resilience4j)**
Integrated sliding-window Circuit Breakers and Semaphore Bulkheads. When an upstream service fails or times out, Gatekeeper short-circuits calls within milliseconds, protects thread pools from starvation, and serves graceful fallbacks while healthy downstream routes continue running with 0% degradation.

🧠 **Explainable Policy Studio (AST Rule Engine)**
Designed a versioned traffic governance engine. Instead of opaque code execution, it compiles policies into an Abstract Syntax Tree (AST), enabling dry-run simulations, explainable decision traces (why an IP or header was allowed/denied), and instant one-click rollbacks.

🔒 **Zero-Trust Security & DevSecOps**
Engineered a cryptographic API key lifecycle (256-bit CSPRNG entropy, SHA-256 hashed storage, single-reveal plaintext modals), sliding-window login brute-force mitigation, and zero-defect Gitleaks CI scanning.

🎨 **Obsidian Titanium Control Plane & Standalone Recruiter Demo**
Built a 9-dashboard operational cockpit in React 18 & TypeScript with a custom dark theme, dynamic incident severity hierarchy, universal command palette (`Ctrl + K`), and an interactive standalone demo mode that lets anyone explore the system locally in 60 seconds with zero cloud or backend setup!

📊 **Engineering Rigor by the Numbers:**
- **147/147 passing tests** across 24 Vitest suites
- **0 console errors** verified across 5 viewport breakpoints (320px to 1440px)
- **100% clean Gitleaks CI scan** with zero leaked credentials
- Complete end-to-end documentation including REST API specs, architecture blueprints, and a 22-question deep technical interview guide!

💻 **Explore the Codebase & Live Recruiter Demo on GitHub:**
https://github.com/saipranav22-commits/gatekeeper

Special thanks to everyone passionate about distributed systems, resilient architectures, and clean engineering craftsmanship. Would love your feedback! 👇

#Java #SpringBoot #DistributedSystems #Redis #React #TypeScript #DevOps #SoftwareEngineering #Architecture #Microservices #SystemDesign
