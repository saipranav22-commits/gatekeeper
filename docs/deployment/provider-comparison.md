# Cloud Deployment Strategy & Architectural Trade-Off Analysis

This document analyzes deployment architectural options for hosting the Gatekeeper distributed system for demonstration and portfolio review.

---

## Deployment Strategies Overview

1. **Option A: Dedicated Cloud Virtual Machine with Docker Compose** (e.g., Hetzner, DigitalOcean Droplet, AWS EC2, Linode)
2. **Option B: Managed Container Orchestration** (e.g., AWS ECS Fargate, GCP Cloud Run, Azure Container Apps)
3. **Option C: Split Hosting** (Static Frontend on Vercel/Netlify + Gateway & Microservices on Railway/Render + Managed DB/Redis)

---

## Architectural Comparison Matrix

| Evaluation Dimension | Option A: Dedicated Cloud VM (Docker Compose) | Option B: Managed Containers (ECS / Cloud Run) | Option C: Split Hosting (Vercel + Railway/Render) |
|---|---|---|---|
| **Cost Predictability** | Fixed monthly cost based on VM instance tier; no per-request billing surprises. | Variable consumption-based billing; network egress, task runtimes, and load balancers incur separate fees. | Free/low-cost frontend static tier; separate compute fees for backend containers and database add-ons. |
| **Operational Complexity** | Standard Linux system administration (SSH, Docker Engine, systemd, local UFW firewall). | High infrastructure overhead (VPC peering, task definitions, CloudWatch/IAM roles, target groups). | Minimal infrastructure overhead (Git push-to-deploy, web management dashboard). |
| **Architectural Fidelity** | **100% Identical** to the verified local container topology and bridge networking. | High, but requires replacing Docker network DNS with managed service discovery (e.g. AWS Cloud Map). | Moderate; introduces public egress between frontend, gateway, and upstream services. |
| **Network Latency** | **Sub-millisecond (< 0.5 ms)**; Gateway, Redis, PostgreSQL, and services communicate over local bridge. | Low (1 - 3 ms) within the same cloud availability zone and VPC. | Higher (20 - 100 ms); requests traverse public internet backbones between providers. |
| **Resilience Demonstration** | Full control to demonstrate circuit breaker transitions and rate limit throttling live. | Cloud load balancers and auto-healing healthchecks can obscure or replace faulted instances. | PaaS edge proxies frequently retry or mask upstream 5xx errors automatically. |
| **Portfolio Impression** | Demonstrates distributed systems engineering, Nginx reverse proxying, and container networking. | Demonstrates enterprise cloud-native DevOps and Infrastructure-as-Code skills. | Demonstrates full-stack product shipping speed. |

---

## Strategy Recommendation for Developer Portfolio

### Primary Recommendation: **Option A (Dedicated Cloud VM with Docker Compose)**

**Architectural Rationale:**
1. **Identical Network Topology**: The entire 6-container stack (`gateway`, `orders`, `inventory`, `postgres`, `redis`, `frontend`) executes without requiring alterations to internal DNS, bridge networks, or SSRF validation rules.
2. **Deterministic Low-Latency Performance**: The Redis token-bucket rate limiter and Gateway proxy filters operate within the same virtual network bridge, avoiding the 30-80ms latency penalty associated with cross-cloud API round trips.
3. **Hardware Capacity Consideration**: The estimated baseline memory budget for the 6-container stack is ~2.4 GB. A cloud VM with 4 GB to 8 GB of RAM and 2 vCPUs provides comfortable headroom for the JVM heaps, OS page cache, and concurrent demonstration traffic. *(Empirical load testing should validate actual capacity before high-volume demonstrations).*

---

## Deployment Blueprint (Dedicated VM)

1. Provision an Ubuntu 24.04 LTS instance (recommended 4GB - 8GB RAM, 2 vCPUs).
2. Install Docker Engine and the Docker Compose plugin via official Docker repositories.
3. Clone the repository and configure `.env` based on `.env.example`.
4. Configure an edge reverse proxy (such as Caddy, Nginx with Certbot, or Cloudflare Tunnel) to handle TLS termination on ports 80/443:
   ```
   # Example Caddyfile:
   gatekeeper.yourdomain.com {
       reverse_proxy localhost:3000
   }
   ```
5. Launch the stack:
   ```bash
   docker compose -f docker-compose.deploy.yml up -d
   ```
6. Verify deployment with the non-destructive smoke test script:
   ```powershell
   ./verify-deployment.ps1 -FrontendUrl "https://gatekeeper.yourdomain.com" -SkipDirectGatewayCheck
   ```
