# Cloud Deployment Strategy & Provider Comparison Matrix

This document provides a comparative analysis of three production deployment strategies for hosting the Gatekeeper distributed system as a live demonstration portfolio project.

---

## Deployment Strategies Overview

1. **Option A: Single Cloud VM with Docker Compose** (e.g., Hetzner, DigitalOcean Droplet, AWS EC2, Linode)
2. **Option B: Managed Container Service** (e.g., AWS ECS Fargate, GCP Cloud Run, Azure Container Apps)
3. **Option C: Split Hosting** (Static Frontend on Vercel/Netlify + Gateway & Services on Railway/Render + Managed DB/Redis on Supabase/Upstash)

---

## Detailed Comparison Matrix

| Evaluation Criteria | Option A: Single Cloud VM (Docker Compose) | Option B: Managed Containers (ECS / Cloud Run) | Option C: Split Hosting (Vercel + Railway/Render) |
|---|---|---|---|
| **Monthly Cost** | **Lowest** ($4 - $12 / mo)<br/>(e.g., Hetzner CX22 4GB RAM: ~$4.50/mo, DigitalOcean 4GB: $24/mo) | **High** ($30 - $70+ / mo)<br/>(6 continuously running container tasks + NAT + ALB) | **Moderate to Low** ($5 - $20 / mo)<br/>(Free frontend tier; paid backend container tiers) |
| **Operational Complexity** | **Low to Moderate**<br/>Simple SSH setup, single `docker compose` file, standard Linux sysadmin. | **High**<br/>VPC configuration, task definitions, service discovery, IAM roles, load balancers. | **Lowest**<br/>Git push-to-deploy, web dashboards, zero infrastructure maintenance. |
| **Architectural Fidelity** | **Highest (100% Identical)**<br/>Runs the exact verified multi-tier Docker topology developed locally. | **High**<br/>Microservices and storage run independently, but complex inter-service discovery. | **Low to Moderate**<br/>Split domains, public egress between services, cross-origin CORS overhead. |
| **Resource Efficiency** | **Excellent**<br/>Full 4GB-8GB RAM shared dynamically among containers via cgroups. | **Moderate**<br/>Each container requires fixed vCPU/RAM allocation, leading to over-provisioning. | **Good**<br/>PaaS scales compute, but cold starts can degrade microservice response latency. |
| **Network Latency** | **Sub-millisecond (< 0.5 ms)**<br/>Gateway, Redis, Postgres, and Microservices communicate on local virtual bridge. | **Low (1 - 3 ms)**<br/>Within same AWS/GCP region VPC. | **Higher (20 - 100 ms)**<br/>Inter-service network hops traverse public internet APIs. |
| **Chaos Testing Demonstration** | **Fully Capable**<br/>Allows live execution and observation of circuit breakers, rate limits, and outages. | **Complex**<br/>Managed services automatically replace or restart instances unpredictably. | **Limited**<br/>PaaS ingress proxies often mask or retry 5xx upstream failures automatically. |
| **Portfolio Impact** | **Strong Professional Impression**<br/>Demonstrates mastery of Linux, Docker networking, Nginx reverse proxying, and system design. | **Enterprise DevOps Signal**<br/>Shows cloud-native AWS/GCP infrastructure-as-code competency. | **Good Web App Signal**<br/>Shows modern frontend deployment, but less distributed systems depth. |

---

## Recommended Strategy for Developer Portfolio: Option A (Single Cloud VM)

### Primary Recommendation: **Option A (Hetzner Cloud CX22 or DigitalOcean Droplet)**

**Why this is the optimal choice:**
1. **Cost Predictability**: A single 4 GB RAM / 2 vCPU instance on Hetzner costs ~€4.50/month ($5 USD), or $24/month on DigitalOcean. There are no surprise billing spikes from egress data transfer or API gateway calls.
2. **Exact Topology Reproduction**: The entire 6-container stack (`gateway`, `orders`, `inventory`, `postgres`, `redis`, `frontend`) runs identically to local validation without altering network bridges, SSRF configurations, or service discovery logic.
3. **Sub-millisecond Performance**: The high-throughput Redis token-bucket rate limiter and resilient HTTP proxy routing operate with near-zero latency, avoiding public internet routing penalties between tiers.
4. **Resilience & Chaos Showcase**: You can demonstrate live circuit breaker transitions, rate limit throttling, and failover behavior during technical interviews without cloud provider load balancers intercepting the telemetry.

---

## Implementation Blueprints

### Blueprint for Option A (Single VM + Nginx / Traefik / Caddy)
1. Provision Ubuntu 24.04 LTS instance (2 vCPU, 4GB RAM).
2. Install Docker Engine and Docker Compose plugin.
3. Clone repository and copy `.env.example` to `.env`.
4. Configure Caddy or Certbot for automated Let's Encrypt TLS:
   ```
   gatekeeper.yourdomain.com {
       reverse_proxy localhost:3000
   }
   api.gatekeeper.yourdomain.com {
       reverse_proxy localhost:8080
   }
   ```
5. Run `docker compose -f docker-compose.deploy.yml up -d`.

### Blueprint for Option C (Alternative Quick Demo)
1. **Frontend**: Deploy `frontend/` to Vercel. Set `VITE_API_BASE_URL=https://api.your-railway-app.up.railway.app`.
2. **Gateway & Microservices**: Deploy `backend/` and `services/` to Railway as Docker services within a private Railway network.
3. **Database**: Use Railway Managed Postgres or Supabase.
4. **Redis**: Use Upstash Redis or Railway Redis.
