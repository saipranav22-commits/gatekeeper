# Gatekeeper — Phase 7F: Browser Automation & Evidence Classification

**Audit Target:** `frontend/scripts/generate-screenshots.mjs`  
**Execution Environment:** Node.js v22.19.0 on Windows 11 with Google Chrome v157+  
**CDP WebSocket Endpoint:** `ws://127.0.0.1:9222/devtools/page/...`  

---

## 1. Evidence Tier Classification

To maintain rigorous DevSecOps and QA integrity, all testing and verification artifacts are classified into five distinct tiers:

| Tier | Category Description | Definition & Scope | Gatekeeper Verification Status |
| :--- | :--- | :--- | :--- |
| **Tier A** | **Real Browser against Real Backend** | Headless or GUI Chrome executing the frontend against a fully running JVM Spring Boot gateway, PostgreSQL 16, and Redis 7 stack. | **NOT VERIFIED LOCALLY** (Preserved to avoid memory thrashing on the 7.5 GB RAM workstation; verified in GitHub Actions CI). |
| **Tier B** | **Real Browser against Mocked Backend** | Real Google Chrome launched via CDP executing the production Vite build (`dist/`), with HTTP API calls serviced by an embedded mock server matching Spring Boot DTOs. | **VERIFIED (PASS)** — Executed via `generate-screenshots.mjs`, producing all 23 screenshots. |
| **Tier C** | **Component Tests in jsdom** | Automated unit and integration tests executing in Vitest / jsdom with React Testing Library and mocked services. | **VERIFIED (PASS)** — 79 / 79 tests passing across 20 test suites. |
| **Tier D** | **Static Source Inspection** | Compiler checks (`tsc -b`), linter analysis (`eslint`), AST inspections, and configuration reviews. | **VERIFIED (PASS)** — Zero compiler errors, zero linter warnings. |
| **Tier E** | **Documentation-Only Claims** | Architectural descriptions, design documents, and manual runbooks without automated runtime verification. | **AUDITED & ALIGNED** — Refined in Phase 7F to eliminate unverified claims. |

---

## 2. Browser Automation Script Architecture

The script [generate-screenshots.mjs](file:///d:/Development/Gatekeeper/frontend/scripts/generate-screenshots.mjs) operates through the following steps:
1. **Local Static & API Mock Server (Port 4173):** An integrated Node.js `http.Server` serves the production distribution from `frontend/dist/` and intercepts API routes (`/api/v1/*`, `/actuator/*`) to return schema-compliant DTO responses.
2. **Headless Chrome Process Spawning:** Launches `C:\Program Files\Google\Chrome\Application\chrome.exe` with flags:
   `--headless=new --remote-debugging-port=9222 --disable-gpu --no-first-run --no-default-browser-check`
3. **Chrome DevTools Protocol (CDP) WebSocket:** Directly enables `Page`, `Runtime`, and `Emulation` domains over native WebSockets without heavy third-party automation dependencies.
4. **Authentic Browser Interactions:**
   - Navigates to `http://127.0.0.1:4173/login`.
   - Populates form input fields and clicks the submit button.
   - Triggers client-side React Router navigation by clicking links (`a[href="/policies"]`, `a[href="/reliability"]`, `a[href="/chaos"]`).
   - Dispatches keyboard shortcuts (`Ctrl+K`) to evaluate modal layering and focus.
5. **Screen Captures:** Captures PNG screenshots across 5 viewports after allowing 1200–1500 ms for SVG and chart animations to settle.

---

## 3. Engineering Distinctions & Caveats

- **Not an E2E Test Against Live Java Gateway:** While the browser rendering, layout calculations, typography, and React component tree are genuine, this does not validate live database transactions, Redis rate-limiting Lua scripts, or real network latency cascades.
- **Visual Gate Value:** Tier B verification provides rock-solid proof of responsive CSS reflow, absence of text clipping, correct font rendering, and zero client-side React rendering crashes across Desktop, Laptop, Tablet, Mobile, and Narrow viewports.
