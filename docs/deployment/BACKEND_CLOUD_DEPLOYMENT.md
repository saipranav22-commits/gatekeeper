# Gatekeeper — Production Backend Cloud Deployment Architecture & Runbook

This guide specifies the authoritative architecture and deployment runbook for hosting Gatekeeper's genuine **Java 21 / Spring Boot 3 edge gateway**, **downstream microservices (Orders & Inventory)**, **PostgreSQL 16**, and **Redis 7** in a production cloud environment at **₹0 cost**.

---

## 1. Architectural Blueprint & Provider Selection

### 1.1 The Zero-Cost Infrastructure Reality
Gatekeeper's backend is not a toy CRUD script; it is a full distributed microservices ecosystem requiring:
- 3 independent Spring Boot 3 Java 21 runtimes (Gateway, Orders Service, Inventory Service).
- Native Redis 7 supporting custom Lua scripts (`token_bucket.lua`) and blocking stream consumers (`XREADGROUP ... BLOCK`).
- PostgreSQL 16 executing 9 versioned Flyway migrations (`V1`–`V9`) and storing immutable audit logs.
- Estimated sustained baseline memory: **~1,152 MB (1.15 GB)** (*theoretical engineering estimate based on JVM cgroup allocations and container baselines*).
- Peak operational headroom under concurrent proxy traffic: **~2,304 MB (2.3 GB)**.

### 1.2 Provider Comparison Matrix

| Provider / Tier | Compute Capacity | Memory Limit | Sleep / Inactivity | Redis Streams Support | PostgreSQL Persistence | Total Cost | Card Required? |
|:---|:---|:---|:---|:---|:---|:---|:---|
| **Oracle Cloud Always Free (Ampere A1)** | **2 OCPU (ARM64)** *(1,500 OCPU hrs/mo)* | **12 GB RAM** *(9,000 GB hrs/mo)* | **Never sleeps (24/7)** *(subject to idle reclamation)* | **Full native Redis 7** | **Permanent (200 GB SSD quota)** | **₹0.00** | **YES (Identity verification only)** |
| **Render Free Tier** | 0.1 vCPU | 512 MB RAM | Sleeps after 15m | No free Redis | Deleted after 30 days | Exceeds 750h limit | No |
| **Koyeb Free Tier** | 0.1 vCPU | 512 MB RAM | Sleeps after 15m | None | None | Paid add-ons | No |
| **Neon Free (Postgres only)** | Serverless | Shared | Cold start (3–5s) | N/A | 0.5 GB permanent | ₹0.00 | No |
| **Upstash Free (Redis only)** | Serverless | Shared | REST Proxy | **FATAL: Drops `XREADGROUP BLOCK`** | N/A | Burns 10k daily quota in 2.8h | No |

### 1.3 Authoritative Architecture Decision: Free ARM64 VM (Oracle Cloud)
**Oracle Cloud Infrastructure (OCI) Always Free** provides the only viable infrastructure for the complete Gatekeeper distributed stack at ₹0 cost:
- **VM Shape**: `VM.Standard.A1.Flex` (Ampere Altra ARM64 processor).
- **Official Monthly Allowance**: **1,500 OCPU hours** and **9,000 GB hours** per month. Running continuously 24/7 for a 31-day month (744 hours), this provides an allowance equivalent to **2 OCPUs and 12 GB RAM**. (*Note: Prior to mid-2026, the baseline was 4 OCPUs and 24 GB RAM. Never assume 4 OCPUs / 24 GB is continuously free.*)
- **Boot Storage**: Up to 200 GB total block storage free per tenancy. Allocate a 50 GB boot volume for the VM.
- **Cost**: **Permanently ₹0.00 / month**.
- **Card Requirement**: A credit or eligible debit card is required during OCI registration for identity verification (a temporary ~$1 / ₹75–100 authorization hold is placed and immediately reversed).

### 1.4 Critical Account & Availability Risks
1. **Regional Capacity Exhaustion ("Out of host capacity")**:
   - In popular regions (e.g., `ap-mumbai-1`, `ap-hyderabad-1`, `us-ashburn-1`), Always Free Ampere A1 capacity is frequently constrained.
   - *Mitigation*: If provisioning fails with an out-of-capacity error, retry in alternative Availability Domains (AD), select a 1 OCPU / 6 GB RAM shape, or retry during off-peak hours.
2. **Idle Instance Reclamation Policy**:
   - Oracle automatically monitors Always Free compute instances. If over 7 consecutive days:
     - 95th-percentile CPU utilization is < 20%
     - Network utilization is < 20%
     - Memory utilization is < 20%
     OCI may reclaim or stop the compute instance.
   - *Mitigation*: Set up periodic cron health checks, monitoring tasks, or keep active background telemetry to maintain non-idle status.

---

## 2. Target Container Topology

```mermaid
flowchart TD
    subgraph Internet["Public Ingress Layer"]
        CF_Frontend["Cloudflare Pages Frontend\n(https://gatekeeper-ayh.pages.dev)"]
        Public_Client["External API Clients / Recruiters\n(X-API-Key: gk_live_...)"]
    end

    subgraph Ingress["Secure Public Ingress (Two ₹0 Options)"]
        OptionA["Option A: Cloudflare Named Tunnel\n(Zero open inbound ports)\nRequires custom domain"]
        OptionB["Option B: DuckDNS + Caddy Reverse Proxy\n(subdomain.duckdns.org + Let's Encrypt TLS)\n100% Free, No domain needed"]
    end

    subgraph Host["Oracle Cloud Always Free ARM64 VM (2 OCPU / 12 GB RAM, Ubuntu 24.04 LTS)"]
        subgraph Net["Docker Bridge Network (gatekeeper-prod-net)"]
            Gateway["Gatekeeper Edge Gateway\n(Spring Boot 3 / Java 21)\nPort 8080 (127.0.0.1 only)\nRAM: 768M Max"]
            Orders["Orders Microservice\n(Spring Boot 3 / Java 21)\nPort 8081 (Internal only)\nRAM: 384M Max"]
            Inventory["Inventory Microservice\n(Spring Boot 3 / Java 21)\nPort 8082 (Internal only)\nRAM: 384M Max"]
            Redis[("Redis 7.4 Alpine\nToken Bucket Lua & Streams\nPort 6379 (Internal only)\nRAM: 256M Max")]
            Postgres[("PostgreSQL 16.8 Alpine\nFlyway V1-V9 Migrations\nPort 5432 (Internal only)\nRAM: 512M Max")]
        end
        Volumes[("Named Volumes:\ngatekeeper_prod_pgdata\ngatekeeper_prod_redisdata")]
    end

    CF_Frontend -->|HTTPS API Requests| Ingress
    Public_Client -->|HTTPS Proxy Calls| Ingress
    Ingress -->|Local Loopback Proxy| Gateway
    Gateway -->|Atomic Rate Limiting & Streams| Redis
    Gateway -->|JPA & Audit Logging| Postgres
    Gateway -->|Resilient Proxy (CB & Bulkhead)| Orders
    Gateway -->|Resilient Proxy (CB & Bulkhead)| Inventory
    Postgres -.-> Volumes
    Redis -.-> Volumes
```

---

## 3. Step-by-Step Provisioning Runbook

### Step 1: Oracle Cloud Account Setup & Safety Verification
1. Sign up at [https://www.oracle.com/cloud/free/](https://www.oracle.com/cloud/free/).
2. Select your home region carefully (cannot be changed after registration).
3. Complete account identity verification with a valid card (₹0 spent).
4. **Safety Check**: Ensure the tenancy is marked "Free Tier". Do not click "Upgrade" to Pay-As-You-Go unless specifically intended.

### Step 2: Create Ampere A1 Compute Instance
1. In the OCI Console, navigate to **Compute > Instances > Create Instance**.
2. **Placement**: Select an Availability Domain with Ampere A1 capacity.
3. **Image**: `Ubuntu 24.04 LTS (aarch64)`.
4. **Shape**: Click **Change Shape** > **Ampere** > **VM.Standard.A1.Flex**:
   - OCPU: `2` (within the 1,500 OCPU-hr monthly continuous limit).
   - Memory: `12 GB` (within the 9,000 GB-hr monthly continuous limit).
   - Verify the **"Always Free Eligible"** badge is visible.
5. **Networking**: Create new VCN with Public Subnet and assign a free public IPv4 address.
6. **Boot Volume**: Default 47 GB or 50 GB (well within the 200 GB Always Free limit).
7. **SSH Keys**: Download and save your private SSH key.
8. Click **Create** (instantiation takes ~60 seconds).

### Step 3: Host Initialization & Docker Installation
Connect via SSH:
```bash
ssh -i /path/to/ssh-key.key ubuntu@<YOUR_VM_PUBLIC_IP>
```

Install Docker Engine and Docker Compose (ARM64):
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

### Step 4: Deploy Gatekeeper Backend
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

# Safely inject secrets into .env.production
sed -i "s/^POSTGRES_PASSWORD=.*$/POSTGRES_PASSWORD=$DB_PASS/" .env.production
sed -i "s/^REDIS_PASSWORD=.*$/REDIS_PASSWORD=$REDIS_PASS/" .env.production
sed -i "s/^GATEKEEPER_JWT_SECRET=.*$/GATEKEEPER_JWT_SECRET=$JWT_SECRET/" .env.production

# Launch the complete backend stack
docker compose --env-file .env.production -f docker-compose.prod.yml up -d --build
```

### Step 5: Verify Service Health & Readiness
```bash
# Check container status
docker compose -f docker-compose.prod.yml ps

# Verify Gateway public health endpoint (on local loopback)
curl -s http://127.0.0.1:8080/api/v1/health | jq .

# Verify Orders microservice health (internal)
curl -s http://127.0.0.1:8081/health | jq .

# Verify Inventory microservice health (internal)
curl -s http://127.0.0.1:8082/health | jq .
```

---

## 4. Secure HTTPS Ingress Options (Without Paid Domain)

Because the Gateway strictly binds to `127.0.0.1:8080` to prevent unencrypted public exposure, an HTTPS reverse proxy or tunnel is mandatory.

### Option A: Cloudflare Named Tunnel (Requires User-Owned Domain)
- **Requirements**: A custom domain added to Cloudflare (e.g. `yourdomain.com`).
  *(Note: Cloudflare Pages `*.pages.dev` subdomains cannot be bound to Tunnels, and temporary Quick Tunnels `*.trycloudflare.com` rotate URLs on restart and are unsuitable for production).*
- **Setup**:
  1. In Cloudflare Zero Trust Dashboard > Networks > Tunnels, create a Named Tunnel (`gatekeeper-backend`).
  2. Route public hostname `api.yourdomain.com` to `http://gateway:8080`.
  3. Set `CLOUDFLARE_TUNNEL_TOKEN=<token>` in `.env.production`.
  4. Launch tunnel:
     ```bash
     docker compose --env-file .env.production -f docker-compose.prod.yml --profile tunnel up -d cloudflared
     ```
- **Port Exposure**: Zero open inbound ports required on the VM firewall.

### Option B: DuckDNS Free Subdomain + Caddy Reverse Proxy (100% Free, No Domain Needed)
- **Requirements**: Free DuckDNS account (no credit card, no domain purchase).
- **Setup**:
  1. Register a free permanent subdomain at [https://www.duckdns.org](https://www.duckdns.org) (e.g. `gatekeeper-api.duckdns.org`) pointing to your VM's public IPv4 address.
  2. Open port 80 and 443 in the OCI Security List for your VM's subnet.
  3. Install Caddy (automatic Let's Encrypt TLS reverse proxy):
     ```bash
     sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
     curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
     curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
     sudo apt update && sudo apt install caddy -y
     ```
  4. Configure `/etc/caddy/Caddyfile`:
     ```caddyfile
     gatekeeper-api.duckdns.org {
         reverse_proxy 127.0.0.1:8080
     }
     ```
  5. Restart Caddy: `sudo systemctl restart caddy`.
  6. Automatic HTTPS is immediately live with a trusted Let's Encrypt certificate at `https://gatekeeper-api.duckdns.org`.

---

## 5. Billing & Cost Safety Checklist

To guarantee ₹0 expenditure and protect your account:
- [ ] **Always Free Shape**: Confirm shape is `VM.Standard.A1.Flex` with ≤ 2 OCPUs and ≤ 12 GB RAM.
- [ ] **Boot Volume**: Keep total boot and block volumes at or under 200 GB total (e.g., 50 GB).
- [ ] **Always Free Badge**: Verify the blue "Always Free Eligible" badge appears before clicking Create.
- [ ] **No Paid Add-ons**: Do not attach paid block volumes, paid NAT gateways, or premium load balancers.
- [ ] **No Card Details in Chat**: Never paste credit card details or cloud credentials into Antigravity or code repositories.
