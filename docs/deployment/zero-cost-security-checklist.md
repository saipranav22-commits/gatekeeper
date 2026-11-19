# Gatekeeper Zero-Cost Security & Operations Checklist

## 1. Network Boundary & Firewall Controls

### OCI Virtual Cloud Network (VCN) Ingress Rules
In OCI Console -> Networking -> Virtual Cloud Networks -> Default Security List for VCN:

| Ingress Port | Protocol | Source CIDR | Purpose |
|---|---|---|---|
| **22** | TCP | `0.0.0.0/0` (or operator static IP) | Secure SSH Administration (Public key only) |
| **80** | TCP | `0.0.0.0/0` | Let's Encrypt ACME HTTP-01 Challenges & HTTPS redirects |
| **443** | TCP | `0.0.0.0/0` | Encrypted HTTPS Public User & API Traffic |
| **ALL OTHERS** | ANY | N/A | **DROP / REJECT** (PostgreSQL 5432, Redis 6379, etc. completely closed) |

### Host Operating System (UFW) Configuration
```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp comment 'SSH'
sudo ufw allow 80/tcp comment 'HTTP ACME'
sudo ufw allow 443/tcp comment 'HTTPS'
sudo ufw enable
```

---

## 2. Secure Access to Private GitHub Repository

Because `https://github.com/saipranav22-commits/gatekeeper` is private, personal access tokens (PATs) must **never** be stored on the server.

### Repository-Scoped Read-Only Deploy Key Runbook:
1. On the OCI VM, generate an ED25519 deploy key:
   ```bash
   ssh-keygen -t ed25519 -f ~/.ssh/gatekeeper_deploy_key -N "" -C "gatekeeper-oci-readonly"
   ```
2. Configure SSH client:
   ```bash
   cat <<EOF >> ~/.ssh/config
   Host github.com
       HostName github.com
       User git
       IdentityFile ~/.ssh/gatekeeper_deploy_key
   EOF
   chmod 600 ~/.ssh/config ~/.ssh/gatekeeper_deploy_key
   ```
3. Copy the public key output (`cat ~/.ssh/gatekeeper_deploy_key.pub`).
4. In GitHub -> `saipranav22-commits/gatekeeper` -> **Settings** -> **Deploy keys** -> **Add deploy key**:
   - Title: `OCI-Always-Free-Staging`
   - Key: Paste public key
   - **"Allow write access": LEAVE UNCHECKED (Strictly Read-Only)**
5. Clone the verified commit safely:
   ```bash
   git clone git@github.com:saipranav22-commits/gatekeeper.git /opt/gatekeeper
   cd /opt/gatekeeper
   git checkout d4aadd2
   ```

---

## 3. Server-Side Secret Generation & Injection

Never commit or hardcode production passwords. Generate strong, high-entropy secrets directly on the OCI instance:

```bash
# In /opt/gatekeeper on the OCI instance:
cp .env.example .env
chmod 600 .env

# Generate 32-byte cryptographic secrets:
PG_SECRET=$(openssl rand -hex 32)
REDIS_SECRET=$(openssl rand -hex 32)
JWT_SECRET=$(openssl rand -base64 48)

# Inject into .env:
sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=${PG_SECRET}|" .env
sed -i "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=${REDIS_SECRET}|" .env
sed -i "s|^GATEKEEPER_JWT_SECRET=.*|GATEKEEPER_JWT_SECRET=${JWT_SECRET}|" .env
```

---

## 4. First Administrator Bootstrap

When the system boots for the first time, the `admin_users` table is empty.

### Safe Bootstrap Procedure:
1. Ensure the database and redis dependencies are running:
   ```bash
   docker compose -f docker-compose.deploy.yml up -d postgres redis
   ```
2. Verify dependency health:
   ```bash
   docker compose -f docker-compose.deploy.yml ps
   ```
3. Execute one-off administrator bootstrap using Spring Boot's application runner flag (`--gatekeeper.bootstrap.enabled=true`):
   ```bash
   # Interactive console (prompts securely for username, email, and masked password):
   docker compose -f docker-compose.deploy.yml run --rm -it gateway --gatekeeper.bootstrap.enabled=true
   ```
   *Alternative (Non-interactive / CI / Automation using environment variables)*:
   ```bash
   docker compose -f docker-compose.deploy.yml run --rm \
     -e BOOTSTRAP_ADMIN_USERNAME="admin" \
     -e BOOTSTRAP_ADMIN_EMAIL="admin@gatekeeper.local" \
     -e BOOTSTRAP_ADMIN_PASSWORD="YourStrongPassword123!" \
     gateway --gatekeeper.bootstrap.enabled=true
   ```
4. Start the remaining stack services (orders, inventory, gateway, frontend):
   ```bash
   docker compose -f docker-compose.deploy.yml up -d
   ```
5. Verify login token generation over HTTPS:
   ```bash
   curl -s -X POST https://gatekeeper.<PUBLIC-IP>.sslip.io/api/v1/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username":"<BOOTSTRAP-ADMIN>","password":"<BOOTSTRAP-PASSWORD>"}' | jq .
   ```

---

## 5. Persistence, Backups & Disaster Recovery

### A. PostgreSQL Nightly Snapshots
Cron job on the host (`/etc/cron.daily/gatekeeper-backup`):
```bash
#!/bin/bash
BACKUP_DIR="/var/backups/gatekeeper"
mkdir -p "$BACKUP_DIR"
DATE=$(date +%Y%m%d_%H%M%S)
docker exec -t gatekeeper-deploy-postgres pg_dump -U gatekeeper_app -Fc gatekeeper | gzip > "$BACKUP_DIR/gatekeeper_${DATE}.dump.gz"
# Retain last 7 days of backups
find "$BACKUP_DIR" -type f -name "gatekeeper_*.dump.gz" -mtime +7 -delete
```

### B. Redis Append-Only State
Redis persists quotas and stream buffers to named Docker volume `gatekeeper_deploy_redisdata` using `appendonly yes` and `appendfsync everysec`. In the event of catastrophic Redis loss, the Gateway fail-closed policy rejects requests until Redis restarts and re-initializes clean rate-limit buckets.

### C. Log Rotation
To prevent container logs from filling the 50 GB boot volume, configure Docker daemon log rotation in `/etc/docker/daemon.json`:
```json
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "50m",
    "max-file": "3"
  }
}
```
Restart Docker: `sudo systemctl restart docker`.

### D. Off-Host Zero-Cost Backup Sync
To protect against instance reclamation or loss, sync encrypted database snapshots off-host to the developer workstation:
```bash
# On operator workstation (PowerShell or Bash):
scp -i ~/.ssh/oci_gatekeeper_id_ed25519 ubuntu@<PUBLIC-IP>:/var/backups/gatekeeper/postgres_latest.dump.gz ./backups/
```
Note: Recovery is not considered verified until an actual restore succeeds on real infrastructure.
