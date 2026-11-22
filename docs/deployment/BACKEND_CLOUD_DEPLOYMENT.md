# Gatekeeper — Production Backend Cloud Deployment Architecture & Runbook

This guide specifies the authoritative architecture and deployment runbook for hosting Gatekeeper's genuine **Java 21 / Spring Boot 3 edge gateway**, **downstream microservices (Orders & Inventory)**, **PostgreSQL 16**, and **Redis 7** in a production cloud environment at **₹0 cost**.

---

## 1. Architectural Blueprint & Provider Selection

### 1.1 The Zero-Cost Infrastructure Reality
Gatekeeper's backend is not a toy CRUD script; it is a full distributed microservices ecosystem requiring:
- 3 independent Spring Boot 3 Java 21 runtimes (Gateway, Orders Service, Inventory Service).
- Native Redis 7 supporting custom Lua scripts (`token_bucket.lua`) and blocking stream consumers (`XREADGROUP ... BLOCK`).
- PostgreSQL 16 executing 9 versioned Flyway migrations (`V1`–`V9`) and storing immutable audit logs.
- Minimum sustained baseline memory: **1,152 MB (1.15 GB)**.
- Peak operational memory under concurrent proxy traffic: **2,304 MB (2.3 GB)**.

### 1.2 Provider Comparison Matrix

| Provider / Tier | Compute Capacity | Memory Limit | Sleep / Inactivity | Redis Streams Support | PostgreSQL Persistence | Total Cost | Card Required? |
|:---|:---|:---|:---|:---|:---|:---|:---|
| **Oracle Cloud Always Free (Ampere A1)** | **4 OCPU (ARM64)** | **24 GB RAM** | **Never sleeps (24/7)** | **Full native Redis 7** | **Permanent (200 GB SSD)** | **₹0.00** | **YES (Verification only)** |
| **Render Free Tier** | 0.1 vCPU | 512 MB RAM | Sleeps after 15m | No free Redis | Deleted after 30 days | Exceeds 750h limit | No |
| **Koyeb Free Tier** | 0.1 vCPU | 512 MB RAM | Sleeps after 15m | None | None | Paid add-ons | No |
| **Neon Free (Postgres only)** | Serverless | Shared | Cold start (3–5s) | N/A | 0.5 GB permanent | ₹0.00 | No |
| **Upstash Free (Redis only)** | Serverless | Shared | REST Proxy | **FATAL: Drops `XREADGROUP BLOCK`** | N/A | Burns 10k daily quota in 2.8h | No |

### 1.3 Authoritative Architecture Decision: Single Free ARM64 VM (Oracle Cloud)
**Oracle Cloud Infrastructure (OCI) Always Free** provides the only genuinely viable infrastructure for the complete Gatekeeper distributed stack at ₹0 cost:
- **VM Shape**: `VM.Standard.A1.Flex` (Ampere Altra ARM64 processor).
- **Allocated Resources**: 2 to 4 OCPUs, 12 to 24 GB of RAM, and 100 GB NVMe boot storage.
- **Cost**: **Permanently ₹0.00 / month**.
- **Card Requirement**: A credit or debit card is required during OCI registration for identity verification (a temporary ~$1 / ₹80 authorization hold is placed and reversed). **No recurring charges or hidden fees occur as long as resources remain within Always Free limits.**

---

## 2. Target Container Topology

```mermaid
flowchart TD
    subgraph Internet["Public Ingress Layer"]
        CF_Frontend["Cloudflare Pages Frontend\n(https://gatekeeper-ayh.pages.dev)"]
        Public_Client["External API Clients / Recruiters\n(X-API-Key: gk_live_...)"]
    end

    subgraph Tunnel["Zero-Port Ingress Tunnel"]
        Cloudflared["Cloudflare Tunnel (cloudflared)\nEncrypted Outbound Tunnel\n(Zero Open Inbound Ports)"]
    end

    subgraph Host["Oracle Cloud Always Free ARM64 VM (Ubuntu 24.04 LTS)"]
        subgraph Net["Docker Bridge Network (gatekeeper-prod-net)"]
            Gateway["Gatekeeper Edge Gateway\n(Spring Boot 3 / Java 21)\nPort 8080 (127.0.0.1 only)\nRAM: 768M Max"]
            Orders["Orders Microservice\n(Spring Boot 3 / Java 21)\nPort 8081 (Internal only)\nRAM: 384M Max"]
            Inventory["Inventory Microservice\n(Spring Boot 3 / Java 21)\nPort 8082 (Internal only)\nRAM: 384M Max"]
            Redis[("Redis 7.4 Alpine\nToken Bucket Lua & Streams\nPort 6379 (Internal only)\nRAM: 256M Max")]
            Postgres[("PostgreSQL 16.8 Alpine\nFlyway V1-V9 Migrations\nPort 5432 (Internal only)\nRAM: 512M Max")]
        end
        Volumes[("Named Volumes:\ngatekeeper_prod_pgdata\ngatekeeper_prod_redisdata")]
    end

    CF_Frontend -->|HTTPS API Requests| Cloudflared
    Public_Client -->|HTTPS Proxy Calls| Cloudflared
    Cloudflared -->|Local Proxy| Gateway
    Gateway -->|Atomic Rate Limiting & Streams| Redis
    Gateway -->|JPA & Audit Logging| Postgres
    Gateway -->|Resilient Proxy (CB & Bulkhead)| Orders
    Gateway -->|Resilient Proxy (CB & Bulkhead)| Inventory
    Postgres -.-> Volumes
    Redis -.-> Volumes
```

---

## 3. Step-by-Step Provisioning Runbook

### Step 1: Oracle Cloud Account Setup
1. Sign up at [https://www.oracle.com/cloud/free/](https://www.oracle.com/cloud/free/).
2. Select your home region (e.g., `ap-mumbai-1` or `ap-hyderabad-1` for lowest latency in India).
3. Complete account identity verification with a valid card (₹0 spent).

### Step 2: Create Ampere A1 Compute Instance
1. In the OCI Console, navigate to **Compute > Instances > Create Instance**.
2. **Placement**: Select an Available Domain containing Ampere A1 capacity.
3. **Image**: `Ubuntu 24.04 LTS (aarch64)`.
4. **Shape**: `VM.Standard.A1.Flex`:
   - OCPU: `2` (or up to `4`).
   - Memory: `12 GB` (or up to `24 GB`).
5. **Networking**: Create new VCN with Public Subnet and assign a free public IPv4 address.
6. **SSH Keys**: Download and save your private SSH key.
7. Click **Create** (instantiation takes ~60 seconds).

### Step 3: Configure Ingress Security List (Zero-Open-Port Architecture)
When using Cloudflare Tunnel, **no inbound ports (not even port 80 or 443) need to be open in the OCI Security List**. All communication flows over an outbound encrypted TLS tunnel established by the `cloudflared` container to Cloudflare's edge network.
- **Port 22 (SSH)**: Restrict to your management IP address.
- **Ports 5432, 6379, 8081, 8082**: Closed to internet; accessible only inside the Docker bridge network.

### Step 4: Host Initialization & Docker Installation
Connect via SSH:
```bash
ssh -i /path/to/ssh-key.key ubuntu@<YOUR_VM_PUBLIC_IP>
```
Install Docker Engine and Docker Compose:
```bash
# Update package indices
sudo apt-get update && sudo apt-get upgrade -y

# Install Docker prerequisites
sudo apt-get install -y ca-certificates curl gnupg

# Add Docker GPG key
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# Add Docker repository (ARM64)
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Install Docker and Compose plugin
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Allow non-root docker execution
sudo usermod -aG docker $USER
newgrp docker
```

### Step 5: Deploy Gatekeeper Backend
```bash
# Clone the repository
git clone https://github.com/saipranav22-commits/gatekeeper.git /opt/gatekeeper
cd /opt/gatekeeper

# Create production environment file from template
cp .env.production.example .env.production

# Generate secure CSPRNG secrets
DB_PASS=$(openssl rand -hex 24)
REDIS_PASS=$(openssl rand -hex 24)
JWT_SECRET=$(openssl rand -hex 32)

sed -i "s/replace_with_cprng_generated_db_password_min_32_chars/$DB_PASS/" .env.production
sed -i "s/replace_with_cprng_generated_redis_password_min_32_chars/$REDIS_PASS/" .env.production
sed -i "s/replace_with_cprng_generated_jwt_secret_min_64_hex_chars/$JWT_SECRET/" .env.production

# Launch the complete backend stack
docker compose --env-file .env.production -f docker-compose.prod.yml up -d --build
```

### Step 6: Verify Service Health & Readiness
```bash
# Check container status
docker compose -f docker-compose.prod.yml ps

# Verify Gateway public health endpoint
curl -s http://127.0.0.1:8080/api/v1/health | jq .

# Verify Orders microservice health
curl -s http://127.0.0.1:8081/health | jq .

# Verify Inventory microservice health
curl -s http://127.0.0.1:8082/health | jq .
```

---

## 4. Zero-Port HTTPS Ingress via Cloudflare Tunnel

To expose the Gateway securely over public HTTPS without exposing VM ports:
1. In [Cloudflare Zero Trust Dashboard](https://one.dash.cloudflare.com/), navigate to **Networks > Tunnels > Add a Tunnel**.
2. Name the tunnel: `gatekeeper-backend`.
3. Copy the generated `TUNNEL_TOKEN`.
4. In `.env.production`, set:
   ```env
   CLOUDFLARE_TUNNEL_TOKEN=eyJh...
   ```
5. In Cloudflare Dashboard Public Hostnames, route your desired hostname (e.g. `api.yourdomain.com` or custom Cloudflare subpath) to:
   - Service: `HTTP`
   - URL: `gateway:8080`
6. Start the tunnel container:
   ```bash
   docker compose --env-file .env.production -f docker-compose.prod.yml --profile tunnel up -d cloudflared
   ```

---

## 5. Security & DevSecOps Guarantees

1. **Least-Privilege Networking**: Microservices (`orders`, `inventory`), PostgreSQL, and Redis expose **zero host ports**. They communicate strictly across the isolated internal Docker bridge `gatekeeper-prod-net`.
2. **CORS Governance**: Gateway permits requests strictly from `https://gatekeeper-ayh.pages.dev` and local development origins via `gatekeeper.security.cors.allowed-origins`.
3. **Container Non-Root Users**: All Java Dockerfiles run as non-root `appuser:appgroup` (UID 10001).
4. **Zero Hardcoded Secrets**: Secrets are injected strictly via runtime environment variables and validated at container startup.
5. **Fail-Closed Protection**: In the event of a Redis or database outage, authentication and rate limiting fail closed to prevent unauthorized proxy bypass.
