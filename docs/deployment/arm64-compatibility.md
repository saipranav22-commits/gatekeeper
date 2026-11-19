# Gatekeeper ARM64 (aarch64) Container Compatibility Matrix

## 1. Overview

Oracle Cloud Infrastructure (OCI) Always Free tier provides **Ampere A1 (ARM64)** processors. This document details the architectural compatibility audit conducted across all Gatekeeper container images, runtime base layers, and application dependencies to guarantee native execution without emulation.

---

## 2. Container Image Architecture Audit

Every container image utilized in Gatekeeper production deployment provides native, official multi-architecture manifests supporting `linux/arm64`:

| Image Reference | Source / Provider | Multi-Architecture Manifests | ARM64 Support Status |
|---|---|---|:---:|
| `maven:3.9.9-eclipse-temurin-21-alpine` | Official Docker Hub | `linux/amd64`, `linux/arm64/v8` | **NATIVE PASS** |
| `eclipse-temurin:21-jre-alpine` | Eclipse Adoptium / Temurin | `linux/amd64`, `linux/arm64/v8` | **NATIVE PASS** |
| `node:22-alpine` | Official Node.js Alpine | `linux/amd64`, `linux/arm64/v8`, `linux/arm/v7` | **NATIVE PASS** |
| `nginx:1.27-alpine` | Official Nginx Alpine | `linux/amd64`, `linux/arm64/v8`, `linux/s390x` | **NATIVE PASS** |
| `postgres:16.8-alpine` | Official PostgreSQL Alpine | `linux/amd64`, `linux/arm64/v8`, `linux/ppc64le` | **NATIVE PASS** |
| `redis:7.4-alpine` | Official Redis Alpine | `linux/amd64`, `linux/arm64/v8`, `linux/arm/v6` | **NATIVE PASS** |

---

## 3. Four-Tier Verification Hierarchy

To ensure strict engineering precision, ARM64 readiness is evaluated across four distinct tiers:

| Verification Tier | Scope & Methodology | Current Status | Notes |
|---|---|:---:|---|
| **Tier 1: Manifest Compatibility** | Verification that base images in Docker Hub publish official `linux/arm64` manifests. | **VERIFIED** | All 6 upstream base images provide official multi-arch manifests. |
| **Tier 2: Dockerfile Build Success** | Validation of Dockerfile build process and syntax across services. | **VERIFIED (x86_64) / PENDING (ARM64)** | Successfully builds on `amd64` in CI. ARM64 build readiness prepared via Buildx syntax linting. |
| **Tier 3: Actual Image Runtime** | Container startup and process execution on a native ARM64 Linux kernel. | **PENDING ARM64 HOST** | Requires execution on native ARM64 hardware (e.g. OCI Ampere A1 VM). Cannot be claimed prior to host provisioning. |
| **Tier 4: End-to-End Application Startup** | Full multi-service lifecycle: Spring Boot boots, Flyway applies migrations, Redis authenticates, routes initialize, and health checks pass. | **PENDING ARM64 HOST** | Verified in x86_64 CI; requires live validation on the target OCI VM during first deployment. |

---

## 4. Native Binary & Dependency Verification

### Java 21 Backend & Microservices
- **Bytecode Portability**: Java `.class` files compiled with `javac` run identically across `amd64` and `arm64` architectures.
- **JVM JIT Engine**: Eclipse Temurin 21 provides a native HotSpot JIT compiler optimized for 64-bit ARM (Neoverse N1 cores used in Ampere A1).
- **Native Extensions**: No proprietary x86 JNI (Java Native Interface) libraries or platform-dependent C extensions exist in Gatekeeper dependencies (PostgreSQL driver, Lettuce Redis, Spring Boot Web, Flyway, and Resilience4j are pure Java).

### React / TypeScript Frontend
- **Bundling**: Static Vite/React bundling produces static HTML, CSS, and ECMAScript modules that execute entirely in the user's browser, completely independent of the server CPU architecture.
- **Nginx Serving**: Nginx 1.27 compiled natively for `aarch64` serves static files and reverse proxies HTTP requests.

---

## 5. Build & CI Strategy

1. **Native On-Host Build (Primary Deployment Strategy)**:
   - When the repository is cloned onto the Ampere A1 instance, `docker compose -f docker-compose.deploy.yml build` builds native `linux/arm64` images using the VM's native ARM64 cores and 12 GB RAM.
   - **Advantage**: Fast, native compilation without QEMU emulation overhead.
2. **GitHub Actions CI Build Validation**:
   - Standard CI builds and validates on `ubuntu-latest` (x86_64).
   - Multi-arch Dockerfile syntax and manifest resolution are checked via Docker Buildx (`--check`).
   - Image publishing is deliberately disabled; no images are pushed to public or private registries.
