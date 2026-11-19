# Free Hostname & Automatic TLS Architecture (sslip.io + Caddy)

## 1. Overview

To satisfy the **₹0 domain requirement**, Gatekeeper utilizes **sslip.io**, a free wildcard DNS service, paired with **Caddy Web Server** on the host for automatic Let's Encrypt TLS certificate generation and renewal.

---

## 2. sslip.io Mechanism & Compatibility

### How it Works
`sslip.io` maps any hostname containing an IP address directly to that IP address:
```text
gatekeeper.<PUBLIC-IP>.sslip.io  -->  Resolves to <PUBLIC-IP>
```
For example, if the OCI Ampere instance receives public IPv4 `129.153.45.67`:
```text
gatekeeper.129.153.45.67.sslip.io  -->  Resolves to 129.153.45.67
```

### Let's Encrypt ACME HTTP-01 Validation
1. Caddy requests a TLS certificate for `gatekeeper.<PUBLIC-IP>.sslip.io`.
2. Let's Encrypt resolves `gatekeeper.<PUBLIC-IP>.sslip.io` -> `<PUBLIC-IP>`.
3. Let's Encrypt sends an HTTP challenge request to `http://<PUBLIC-IP>/.well-known/acme-challenge/<TOKEN>`.
4. Caddy (listening on host port 80) responds with the challenge key.
5. Let's Encrypt validates the challenge and issues a trusted, publicly recognized X.509 TLS certificate.
6. Caddy automatically renews the certificate every 60 days.

### Public Suffix List (PSL) Guarantee
`sslip.io` is registered on the Mozilla **Public Suffix List (PSL)**. This guarantees that Let's Encrypt rate limits (e.g. 50 certificates per registered domain per week) apply per user IP prefix, completely preventing rate-limiting collisions with other `sslip.io` users.

---

## 3. Host Caddy to Docker Nginx Trusted Proxy Arrangement

### The Peer Address Discovery
When host-level Caddy connects to a Docker port published on loopback (`127.0.0.1:3000:80`):
- Docker Linux bridge networking routes packets from the host to the container through the bridge interface.
- Inside the container, the connection arrives with the source IP of the **bridge gateway**, NOT `127.0.0.1`.
- In `docker-compose.deploy.yml`, the network is deterministically assigned:
  ```yaml
  networks:
    gatekeeper-deploy-net:
      name: gatekeeper-deploy-network
      driver: bridge
      ipam:
        config:
          - subnet: 172.28.0.0/24
            gateway: 172.28.0.1
  ```
- The host gateway IP is **`172.28.0.1`**.

### Nginx Trusted Proxy Configuration
In `frontend/nginx.conf`:
```nginx
geo $remote_addr $trusted_edge_proxy {
    default 0;
    127.0.0.1 1;
    ::1 1;
    172.28.0.1/32 1; # Deterministic Docker bridge host gateway IP
}
```
- When host Caddy forwards requests with `X-Forwarded-Proto: https`, Nginx detects `$remote_addr = 172.28.0.1`, marks the connection as **trusted** (`1`), and safely passes `https` through to the Gateway backend.
- Untrusted direct callers cannot spoof headers because any IP outside loopback and `172.28.0.1/32` has its headers stripped.

---

## 4. Host Caddyfile Specification

Stored in `/etc/caddy/Caddyfile` on the Oracle Cloud instance:

```caddyfile
gatekeeper.{$PUBLIC_IP}.sslip.io {
    # Automatic HTTP to HTTPS redirection is built-in
    
    # Reverse proxy to Gatekeeper Frontend Nginx
    reverse_proxy 127.0.0.1:3000 {
        header_up Host {host}
        header_up X-Real-IP {remote_host}
        header_up X-Forwarded-For {remote_host}
        header_up X-Forwarded-Proto {scheme}
    }

    # Access logging
    log {
        output file /var/log/caddy/gatekeeper-access.log {
            roll_size 50mb
            roll_keep 3
        }
    }
}
```

If the VM's public IPv4 changes upon instance reboot (if ephemeral):
Simply update `$PUBLIC_IP` in `/etc/environment` and execute:
```bash
sudo systemctl reload caddy
```
Caddy automatically provisions a new TLS certificate for the updated IP hostname within 5 seconds.
