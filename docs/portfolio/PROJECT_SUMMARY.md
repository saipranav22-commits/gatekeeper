# Gatekeeper — Executive Project Summary

## 1. Problem Statement
Modern microservice ecosystems require high-performance traffic mediation at the edge. Without a resilient gateway layer, downstream services face cascading failure from latency spikes, unthrottled traffic surges from rogue tenants, inconsistent API key management, and opaque traffic governance policies.

## 2. Solution Overview
**Gatekeeper** is an enterprise-grade API Gateway and Intelligent Traffic Control Plane engineered from scratch using **Java 21 (Spring Boot 3)** and **TypeScript (React 19)**. It acts as an intelligent proxy between public ingress traffic and downstream internal microservices, enforcing multi-tenant rate limits, resilience patterns, and policy rules.

## 3. Core Architectural Pillars

### 3.1 Distributed Token-Bucket Rate Limiter (Redis Lua)
- **Zero Race Conditions**: Executes an atomic Redis Lua script calculating token replenishment based on fractional timestamps.
- **Multi-Tenant Isolation**: Supports dynamic quotas per subscription plan tier (`Starter`, `Professional`, `Enterprise`) with automatic burst handling.
- **Atomic In-Memory Evaluation**: Eliminates distributed locking overhead, evaluating token availability atomically within Redis memory.

### 3.2 Cascading Failure Prevention & Fault Isolation
- **Sliding-Window Circuit Breakers**: Automatically transitions between `CLOSED`, `OPEN`, and `HALF-OPEN` states when upstream error rates cross configured thresholds (default: 50% over sliding window).
- **Semaphore Bulkheads**: Dedicates isolated concurrency pools (configurable, default: 10 permits per route) to each downstream microservice, guaranteeing that an outage in one service never starves thread pools for healthy services.

### 3.3 Explainable Policy Governance Engine
- **Declarative Rule Evaluation**: Evaluates structured rule criteria against request attributes (route ID, HTTP method, tenant plan, OAuth scopes, time windows, and days of week).
- **Dry-Run Simulation**: Enables operators to test candidate policies against simulated payloads, returning full explainable decision traces before production activation.
- **Immutable Versioning**: All policy revisions are version-controlled with instant rollback capability.

### 3.4 Zero-Trust Secret Management
- **Cryptographic Key Lifecycle**: Generates API keys with 256 bits of CSPRNG entropy.
- **Irreversible Storage**: Only SHA-256 hashes are persisted; plaintext keys are displayed exactly once via a secure single-reveal modal.
- **Login Brute-Force Defense**: Sliding-window rate limiting on administrator authentication limits failed attempts to 5 per 15 minutes per IP.

### 3.5 Dual-Mode Frontend Control Plane
- **Obsidian Titanium Design System**: Fully responsive dark-mode UI with dynamic incident severity hierarchy and universal keyboard shortcuts (`Ctrl + K`).
- **Live Cloudflare Interactive Demo**: Hosted on Cloudflare Pages Free at [https://gatekeeper-ayh.pages.dev](https://gatekeeper-ayh.pages.dev), enabling immediate public exploration with zero backend setup.
- **Zero-Backend Recruiter Demonstration**: Vite-powered standalone mode running entirely in-browser with zero cloud infrastructure, simulating real health states and topology.
- **Narrated Video Walkthrough**: Complete 4-minute architectural tour with AI voice narration and captions available on [YouTube (https://youtu.be/c29oX3FUKVc)](https://youtu.be/c29oX3FUKVc).

## 4. Key Performance & Quality Metrics
- **Automated Test Coverage**: 457 passing tests (147 Vitest frontend tests across 24 suites, 310 backend JUnit tests across 48 test classes) verified in GitHub Actions CI.
- **Security Posture**: 100% Gitleaks clean, zero plaintext secrets in Git, zero console errors across 5 responsive viewports.
- **Architecture Integrity**: Clean separation of concerns between edge proxy, state cache, relational persistence, and presentation layers.
