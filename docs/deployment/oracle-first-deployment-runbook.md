# Gatekeeper — Oracle Cloud Infrastructure Always Free First Deployment Runbook

> [!IMPORTANT]
> **Strict ₹0 Budget Guarantee**
> This runbook is engineered to run permanently within the **Oracle Cloud Infrastructure (OCI) Always Free Tier**. Follow the exact instance shape, sizing, and volume limits specified below to guarantee **$0.00 / ₹0.00** recurring billing.
> Do NOT click "Upgrade to Paid" or provision shapes without the "Always Free Eligible" badge unless explicitly intended.

---

## Prerequisites & Architecture Summary

- **Target Cloud Provider**: Oracle Cloud Infrastructure (OCI) Always Free Tier
- **Compute Shape**: `VM.Standard.A1.Flex` (Ampere Altra ARM64)
- **Allocation**: 2 OCPUs, 12 GB RAM (Consumes 1,488 OCPU-hours and 8,928 GB-hours per 31-day month; well within monthly allowances)
- **Storage**: 50 GB Boot Volume (Out of 200 GB Always Free block volume allowance)
- **Networking**: 1 Ephemeral or Reserved Public IPv4 (Included free)
- **Ingress Strategy**: Host-level Caddy reverse proxy terminating Let's Encrypt TLS for `gatekeeper.<PUBLIC_IP>.sslip.io`, forwarding HTTP traffic to `127.0.0.1:3000` (Docker Nginx frontend)
- **Edge Security**: `frontend/nginx.conf` trusts Docker bridge gateway `172.28.0.1/32` to accept sanitized `X-Forwarded-*` headers from Caddy while blocking all direct untrusted header spoofing

---

## Section A: Oracle Account & Billing Eligibility Verification

1. **Log in to OCI Console**: Navigate to [cloud.oracle.com](https://cloud.oracle.com).
2. **Verify Limits & Quotas**:
   - In the search bar or hamburger menu, navigate to **Governance & Administration** -> **Limits, Quotas and Usage**.
   - Select Service: **Compute**.
   - Check the limit for **Cores for Standard.A1 Based VM and BM Instances**:
     - Verify available quota is at least **2 OCPUs** (Standard free tenancy provides 4 OCPUs and 24 GB RAM; 3,000 OCPU-hrs / 18,000 GB-hrs per month).
3. **Configure Zero-Cost Cost Tracking & Budget Alerts**:
   - Navigate to **Billing & Cost Management** -> **Budgets**.
   - Click **Create Budget**:
     - Scope: **Tenancy**
     - Target Amount: **$1.00** (or ₹100.00)
     - Alert Rule: Trigger alert when Forecasted Spend reaches **10%** ($0.10).
     - Recipient: Enter your monitoring email address.
   - This ensures immediate notification if any resource inadvertently drifts outside Always Free boundaries.

---

## Section B: Virtual Cloud Network & VM Provisioning

### 1. Configure VCN Ingress Rules
1. Navigate to **Networking** -> **Virtual Cloud Networks**.
2. Select your default VCN -> **Security Lists** -> **Default Security List for <vcn-name>**.
3. Under **Ingress Rules**, ensure only the following ports are open to the public:
   - **Port 22 (SSH)**: Source `0.0.0.0/0`, IP Protocol `TCP`, Destination Port `22`
   - **Port 80 (HTTP)**: Source `0.0.0.0/0`, IP Protocol `TCP`, Destination Port `80` (ACME challenges)
   - **Port 443 (HTTPS)**: Source `0.0.0.0/0`, IP Protocol `TCP`, Destination Port `443`
4. **VERIFY**: Ensure database port `5432`, Redis port `6379`, gateway port `8080`, and frontend port `3000` are **NOT** present in the ingress rules.

### 2. Launch the Compute Instance
1. Navigate to **Compute** -> **Instances** -> **Create Instance**.
2. **Name**: `gatekeeper-prod-01`
3. **Placement**: Select an Availability Domain showing "Always Free Eligible".
4. **Image and Shape**:
   - Image: Click **Change Image** -> Select **Canonical Ubuntu 24.04** (ARM64 / aarch64 build).
   - Shape: Click **Change Shape** -> Select **Ampere** -> **VM.Standard.A1.Flex**.
   - Configure OCPUs: **2**
   - Configure Memory: **12 GB**
   - Confirm the badge displays: **Always Free Eligible**.
5. **Networking**:
   - Select your VCN and Public Subnet.
   - Assign public IPv4 address: **Yes** (Automatically assign public IPv4).
6. **Add SSH Keys**:
   - Select **Upload public key files (.pub)** or generate a new key pair.
   - Save the private key securely on your local development machine (e.g. `~/.ssh/oci_gatekeeper_id_ed25519`).
7. **Boot Volume**:
   - Check **Specify a custom boot volume size**: **50 GB** (Always Free tier allows up to 200 GB total).
8. Click **Create**. Wait 2–3 minutes until the instance state displays **RUNNING**.
9. Record the assigned **Public IP address** (e.g. `140.238.12.34`).

---

## Section C: Operating System Hardening & Host Firewall

1. **Connect via SSH**:
   ```bash
   ssh -i ~/.ssh/oci_gatekeeper_id_ed25519 ubuntu@<PUBLIC_IP>
   ```

2. **Update Base OS Packages**:
   ```bash
   sudo apt-get update && sudo apt-get upgrade -y
   sudo apt-get install -y curl ufw unattended-upgrades git jq ca-certificates
   ```

3. **Configure Host Firewall (UFW & iptables)**:
   > [!NOTE]
   > Oracle Cloud Ubuntu images include default iptables rules in `/etc/iptables/rules.v4` that may block incoming traffic. Synchronize UFW:
   ```bash
   sudo ufw default deny incoming
   sudo ufw default allow outgoing
   sudo ufw allow 22/tcp comment 'SSH'
   sudo ufw allow 80/tcp comment 'HTTP ACME'
   sudo ufw allow 443/tcp comment 'HTTPS'
   sudo ufw enable
   sudo ufw status verbose
   ```

4. **Harden SSH Daemon**:
   ```bash
   sudo sed -i 's/^#*PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config
   sudo sed -i 's/^#*PermitRootLogin .*/PermitRootLogin no/' /etc/ssh/sshd_config
   sudo systemctl restart ssh
   ```

---

## Section D: Docker Engine & Compose Installation on Ubuntu ARM64

Install official Docker packages for Ubuntu `aarch64`:

```bash
# Set up Docker's official GPG key
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

# Add the repository to Apt sources (automatically resolves aarch64 arch)
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Grant non-root user docker permissions
sudo usermod -aG docker ubuntu

# Configure log rotation to protect disk space
sudo tee /etc/docker/daemon.json > /dev/null <<EOF
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "50m",
    "max-file": "3"
  }
}
EOF

sudo systemctl restart docker
docker --version && docker compose version
```

Log out and back in to refresh group permissions:
```bash
exit
ssh -i ~/.ssh/oci_gatekeeper_id_ed25519 ubuntu@<PUBLIC_IP>
```

---

## Section E: Private Git Checkout via Read-Only Deploy Key

Because the repository `https://github.com/saipranav22-commits/gatekeeper` is private, configure a repository-scoped, read-only ED25519 deploy key:

1. **Generate Server Deploy Key**:
   ```bash
   ssh-keygen -t ed25519 -f ~/.ssh/gatekeeper_deploy_key -N "" -C "gatekeeper-oci-readonly"
   ```

2. **Configure SSH Client & Strict Host Checking**:
   ```bash
   cat <<EOF >> ~/.ssh/config
   Host github.com
       HostName github.com
       User git
       IdentityFile ~/.ssh/gatekeeper_deploy_key
       IdentitiesOnly yes
       StrictHostKeyChecking yes
   EOF
   chmod 600 ~/.ssh/config ~/.ssh/gatekeeper_deploy_key
   
   # Populate known_hosts for github.com
   ssh-keyscan -t ed25519 github.com >> ~/.ssh/known_hosts
   chmod 644 ~/.ssh/known_hosts
   ```

3. **Register Deploy Key in GitHub**:
   - Display public key: `cat ~/.ssh/gatekeeper_deploy_key.pub`
   - In GitHub -> `saipranav22-commits/gatekeeper` -> **Settings** -> **Deploy keys** -> **Add deploy key**.
   - Title: `OCI-Ampere-A1-Production`
   - Key: Paste the public key.
   - **Leave "Allow write access" UNCHECKED**.

4. **Clone the Verified Release**:
   ```bash
   sudo mkdir -p /opt/gatekeeper
   sudo chown -R ubuntu:ubuntu /opt/gatekeeper
   git clone git@github.com:saipranav22-commits/gatekeeper.git /opt/gatekeeper
   cd /opt/gatekeeper
   # Checkout the release commit (or latest verified master commit)
   git checkout master
   ```

---

## Section F: Production Environment Secret Generation

Never reuse development credentials or check production secrets into Git:

```bash
cd /opt/gatekeeper

# Create production environment file from template
cp .env.example .env
chmod 600 .env

# Generate cryptographically secure secrets (32 bytes high entropy)
PG_SECRET=$(openssl rand -hex 32)
REDIS_SECRET=$(openssl rand -hex 32)
JWT_SECRET=$(openssl rand -base64 48)

# Obtain host public IP for sslip.io hostname
PUBLIC_IP=$(curl -s https://api.ipify.org || curl -s https://ifconfig.me)
HOSTNAME="gatekeeper.${PUBLIC_IP}.sslip.io"

echo "Configuring Gatekeeper for Hostname: ${HOSTNAME}"

# Inject into .env
sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=${PG_SECRET}|" .env
sed -i "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=${REDIS_SECRET}|" .env
sed -i "s|^GATEKEEPER_JWT_SECRET=.*|GATEKEEPER_JWT_SECRET=${JWT_SECRET}|" .env
sed -i "s|^SPRING_PROFILES_ACTIVE=.*|SPRING_PROFILES_ACTIVE=prod|" .env
```

---

## Section G: Database Initialization & Administrator Bootstrap

1. **Start Core Dependencies (PostgreSQL & Redis)**:
   ```bash
   cd /opt/gatekeeper
   docker compose -f docker-compose.deploy.yml up -d postgres redis
   ```

2. **Wait for Health Checks**:
   ```bash
   # Confirm both services show (healthy)
   docker compose -f docker-compose.deploy.yml ps
   ```

3. **Execute One-Off Administrator Bootstrap**:
   Spring Boot fat JARs cannot be invoked via raw classpaths. The canonical bootstrap mechanism uses a one-off Docker container running the Gateway entrypoint with `--gatekeeper.bootstrap.enabled=true`:

   ```bash
   # Interactive console (prompts securely for username, email, and password):
   docker compose -f docker-compose.deploy.yml run --rm -it gateway --gatekeeper.bootstrap.enabled=true
   ```

   *Non-Interactive Alternative (e.g. passing pre-generated credentials)*:
   ```bash
   docker compose -f docker-compose.deploy.yml run --rm \
     -e BOOTSTRAP_ADMIN_USERNAME="admin" \
     -e BOOTSTRAP_ADMIN_EMAIL="admin@gatekeeper.local" \
     -e BOOTSTRAP_ADMIN_PASSWORD="YourStrongPassword123!" \
     gateway --gatekeeper.bootstrap.enabled=true
   ```

4. **Verify Idempotency**:
   Re-running the command should report:
   `Administrator bootstrap skipped: an administrator already exists in the database.`

---

## Section H: Caddy Edge Ingress & Automated HTTPS Setup

Install Caddy to handle Let's Encrypt TLS certificate procurement and terminate HTTPS on the host:

1. **Install Caddy on Ubuntu ARM64**:
   ```bash
   sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https
   curl -1sLF 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
   curl -1sLF 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
   sudo apt-get update
   sudo apt-get install -y caddy
   ```

2. **Configure Caddyfile**:
   Replace `/etc/caddy/Caddyfile` with the sslip.io proxy configuration:
   ```bash
   PUBLIC_IP=$(curl -s https://api.ipify.org || curl -s https://ifconfig.me)
   
   sudo tee /etc/caddy/Caddyfile > /dev/null <<EOF
   gatekeeper.${PUBLIC_IP}.sslip.io {
       encode gzip zstd
       reverse_proxy 127.0.0.1:3000 {
           header_up Host {host}
           header_up X-Real-IP {remote_host}
           header_up X-Forwarded-For {remote_host}
           header_up X-Forwarded-Proto https
       }
   }
   EOF
   ```

3. **Start and Enable Caddy**:
   ```bash
   sudo systemctl restart caddy
   sudo systemctl enable caddy
   sudo systemctl status caddy
   ```

---

## Section I: Full Container Stack Launch

Now launch the complete 6-service production stack:

```bash
cd /opt/gatekeeper
docker compose -f docker-compose.deploy.yml up -d
```

Verify all 6 containers are running and healthy:
```bash
docker compose -f docker-compose.deploy.yml ps
```

Expected status:
- `gatekeeper-deploy-postgres` (healthy)
- `gatekeeper-deploy-redis` (healthy)
- `gatekeeper-deploy-orders` (healthy)
- `gatekeeper-deploy-inventory` (healthy)
- `gatekeeper-deploy-gateway` (healthy)
- `gatekeeper-deploy-frontend` (healthy, bound to `127.0.0.1:3000`)

---

## Section J: Smoke Testing & Post-Deployment Verification

1. **Verify HTTPS Availability**:
   ```bash
   curl -Iv "https://gatekeeper.${PUBLIC_IP}.sslip.io"
   ```
   Confirm HTTP `200 OK` and a valid Let's Encrypt certificate.

2. **Verify Public Gateway Health Endpoint**:
   ```bash
   curl -s -i "https://gatekeeper.${PUBLIC_IP}.sslip.io/api/v1/health"
   ```
   Confirm HTTP `200 OK` and `{"status":"UP"}`.

3. **Verify Production Chaos Lockout (Strict Security Check)**:
   ```bash
   curl -s -i -X POST "https://gatekeeper.${PUBLIC_IP}.sslip.io/api/v1/chaos/scenarios" \
     -H "Content-Type: application/json" \
     -d '{"name":"latency-test"}'
   ```
   Confirm HTTP `404 Not Found` (chaos controller is disabled by `@Profile("!prod")`).

4. **Verify Admin Authentication**:
   ```bash
   curl -s -X POST "https://gatekeeper.${PUBLIC_IP}.sslip.io/api/v1/auth/login" \
     -H "Content-Type: application/json" \
     -d '{"username":"admin","password":"YourStrongPassword123!"}' | jq .
   ```
   Confirm successful JWT token return (`accessToken`).

5. **Run Automated Verification Script**:
   From your management environment:
   ```powershell
   ./verify-deployment.ps1 -FrontendUrl "https://gatekeeper.<PUBLIC_IP>.sslip.io" -SkipDirectGatewayCheck
   ```

---

## Section K: Backup, Maintenance & Disaster Recovery

### 1. Daily PostgreSQL Backup Script
Create `/usr/local/bin/gatekeeper-backup.sh`:
```bash
sudo tee /usr/local/bin/gatekeeper-backup.sh > /dev/null <<'EOF'
#!/bin/bash
set -euo pipefail

BACKUP_DIR="/var/backups/gatekeeper"
mkdir -p "$BACKUP_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/postgres_${TIMESTAMP}.dump.gz"

docker exec -t gatekeeper-deploy-postgres pg_dump -U gatekeeper_app -Fc gatekeeper | gzip > "$BACKUP_FILE"
chmod 600 "$BACKUP_FILE"

# Retain last 7 days of backups
find "$BACKUP_DIR" -type f -name "postgres_*.dump.gz" -mtime +7 -delete

echo "[$(date)] Backup completed: $BACKUP_FILE"
EOF

sudo chmod +x /usr/local/bin/gatekeeper-backup.sh
```

Add cron entry (`sudo crontab -e`):
```cron
0 2 * * * /usr/local/bin/gatekeeper-backup.sh >> /var/log/gatekeeper-backup.log 2>&1
```

### 2. Database Restore Runbook
To restore a snapshot:
```bash
BACKUP_FILE="/var/backups/gatekeeper/postgres_YYYYMMDD_HHMMSS.dump.gz"
gunzip -c "$BACKUP_FILE" | docker exec -i gatekeeper-deploy-postgres pg_restore -U gatekeeper_app -d gatekeeper --clean --if-exists
```

### 3. Idle Instance Heartbeat (Prevent Oracle Free Reclamation)
Oracle automatically reclaims Always Free instances if 7-day average CPU/memory/network utilization is below 20%. To ensure continuous availability without artificial synthetic load inflation, configure a gentle periodic heartbeat:

```bash
sudo tee /etc/systemd/system/gatekeeper-heartbeat.service > /dev/null <<'EOF'
[Unit]
Description=Gatekeeper Synthetic Self-Health Ping
After=network.target

[Service]
Type=oneshot
ExecStart=/usr/bin/curl -fsS http://127.0.0.1:8080/actuator/health
EOF

sudo tee /etc/systemd/system/gatekeeper-heartbeat.timer > /dev/null <<'EOF'
[Unit]
Description=Run Gatekeeper Health Heartbeat Every 10 Minutes

[Timer]
OnBootSec=5min
OnUnitActiveSec=10min

[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now gatekeeper-heartbeat.timer
```

---

## Conclusion & Gate Verification
Following this runbook establishes a secure, resilient, production-grade deployment of Gatekeeper at **₹0 recurring cost**.
