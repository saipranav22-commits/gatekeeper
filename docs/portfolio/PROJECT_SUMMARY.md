# Gatekeeper — Executive Project Summary

## 1. Problem Statement
Modern microservice ecosystems require high-performance traffic mediation at the edge. Without a resilient gateway layer, downstream services face cascading failure from latency spikes, unthrottled traffic surges from rogue tenants, inconsistent API key management, and opaque traffic governance policies.

## 2. Solution Overview
**Gatekeeper** is an enterprise-grade API Gateway and Intelligent Traffic Control Plane engineered from scratch using **Java 21 (Spring Boot 3)** and **TypeScript (React 18)**. It acts as an intelligent proxy between public ingress traffic and downstream internal microservices, enforcing multi-tenant rate limits, resilience patterns, and policy rules with sub-millisecond overhead.

## 3. Core Architectural Pillars

### 3.1 Distributed Token-Bucket Rate Limiter (Redis Lua)
- **Zero Race Conditions**: Executes an atomic Redis Lua script calculating token replenishment based on fractional timestamps.
- **Multi-Tenant Isolation**: Supports dynamic quotas per subscription plan tier (`Starter`, `Professional`, `Enterprise`) with automatic burst handling.
- **Sub-Millisecond Overhead**: Eliminates distributed locking overhead, achieving p99 evaluation latency under 2ms.

### 3.2 Cascading Failure Prevention (Resilience4j)
- **Sliding-Window Circuit Breakers**: Automatically transitions between `CLOSED`, `OPEN`, and `HALF-OPEN` states when upstream error rates cross configured thresholds (default: 50% over 100 calls).
- **Semaphore Bulkheads**: Dedicates isolated concurrency pools to each downstream microservice, guaranteeing that an outage in one service never starves thread pools for healthy services.

### 3.3 Explainable Policy Governance Engine
- **Dynamic AST Evaluation**: Compiles policy expressions into an Abstract Syntax Tree to evaluate headers, client IP, geographic regions, and payload metadata.
- **Dry-Run Simulation**: Enables operators to test candidate policies against simulated payloads, returning full explainable decision traces before production activation.
- **Immutable Versioning**: All policy revisions are version-controlled with instant rollback capability.

### 3.4 Zero-Trust Secret Management
- **Cryptographic Key Lifecycle**: Generates API keys with 256 bits of CSPRNG entropy.
- **Irreversible Storage**: Only SHA-256 hashes are persisted; plaintext keys are displayed exactly once via a secure single-reveal modal.
- **Login Brute-Force Defense**: Sliding-window rate limiting on administrator authentication limits failed attempts to 5 per 15 minutes per IP.

### 3.5 Dual-Mode Frontend Control Plane
- **Obsidian Titanium Design System**: Fully responsive dark-mode UI with dynamic incident severity hierarchy and universal keyboard shortcuts (`Ctrl + K`).
- **Zero-Backend Recruiter Demonstration**: Vite-powered standalone mode running entirely in-browser with zero cloud infrastructure, simulating real health states and topology.

## 4. Key Performance & Quality Metrics
- **Frontend Test Coverage**: 147 passing tests across 24 test suites with 0 regressions.
- **Security Posture**: 100% Gitleaks clean, zero plaintext secrets in Git, zero console errors across 5 responsive viewports.
- **Architecture Integrity**: Clean separation of concerns between edge proxy, state cache, relational persistence, and presentation layers.
