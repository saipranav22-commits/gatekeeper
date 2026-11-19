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

## 3. Native Binary & Dependency Verification

### Java 21 Backend & Microservices
- **Bytecode Portability**: Java `.class` files compiled with `javac` run identically across `amd64` and `arm64` architectures.
- **JVM JIT Engine**: Eclipse Temurin 21 provides a native HotSpot JIT compiler optimized for 64-bit ARM (Neoverse N1 cores used in Ampere A1).
- **Native Extensions**: No proprietary x86 JNI (Java Native Interface) libraries or platform-dependent C extensions exist in Gatekeeper dependencies (PostgreSQL driver, Lettuce Redis, Spring Boot Web, Flyway, and Resilience4j are pure Java).

### React / TypeScript Frontend
- **Bundling**: Static Vite/React bundling produces static HTML, CSS, and ECMAScript modules that execute entirely in the user's browser, completely independent of the server CPU architecture.
- **Nginx Serving**: Nginx 1.27 compiled natively for `aarch64` serves static files and reverse proxies HTTP requests.

---

## 4. GitHub Actions CI vs Cloud VM Build Strategy

To build and run Gatekeeper on ARM64:

1. **Option A: Build on the Oracle ARM64 VM (Recommended)**:
   - When the user clones the repository onto the Ampere A1 instance, `docker compose -f docker-compose.deploy.yml build` automatically builds native `linux/arm64` images using the VM's native 2–4 ARM64 cores and 12 GB RAM.
   - **Advantage**: Zero QEMU emulation overhead; 100% native build speed.
2. **Option B: Multi-Platform Buildx in GitHub Actions**:
   - Uses `docker/setup-qemu-action@v3` and `docker/setup-buildx-action@v3` on standard `ubuntu-latest` x86 runners to produce multi-platform images (`linux/amd64,linux/arm64`).
   - **Trade-off**: Java and Maven compilation under QEMU emulation can be 3x to 5x slower, potentially extending CI run times.
   - **Recommendation**: Reserve CI for `amd64` unit/integration test validation, and let the 12 GB OCI instance build images natively upon deployment.
