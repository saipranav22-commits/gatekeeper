# Cloudflare Pages Free Deployment Guide — Gatekeeper Recruiter Demo

This guide provides the authoritative, step-by-step procedure to deploy Gatekeeper's standalone interactive recruiter demonstration to **Cloudflare Pages Free** at **strictly ₹0 cost**, requiring **zero credit/debit card commitments**.

---

## 1. Architectural Overview & Free Tier Guarantees

- **Hosting Platform**: Cloudflare Pages (Free Plan).
- **Target Audience**: Technical Recruiters, Engineering Hiring Managers, and Architecture Interviewers.
- **Monthly Cost**: **₹0.00 / month** (Free tier: Unlimited bandwidth, 500 builds/month, 1 concurrent build).
- **Billing Precautions**: Do NOT add payment details or enable paid add-ons (Workers Paid, KV Paid, R2, or Custom Domains with paid SSL). The default `*.pages.dev` subdomain is 100% free with automated global CDN caching and free HTTPS.
- **Backend Independence**: The deployed application runs in **Interactive Demo Mode**, utilizing browser-side simulated data stores, deterministic health state modeling, policy dry-run simulation, and chaos testing scenarios without requiring an active Java/Spring Boot backend or live PostgreSQL/Redis database.

---

## 2. Pre-Deployment Configuration Matrix

Confirm these exact build parameters before creating the Cloudflare Pages project:

| Configuration Setting | Authoritative Value | Rationale |
|:---|:---|:---|
| **Framework Preset** | `Vite` (or `None`) | Standard Vite/React 19 build configuration |
| **Repository** | `saipranav22-commits/gatekeeper` | Official Gatekeeper GitHub repository |
| **Production Branch** | `master` | Current verified production branch |
| **Root Directory** | `frontend` | Gatekeeper React control plane root |
| **Build Command** | `npm run build:demo` | Executes `tsc -b && vite build --mode demo`, loading `.env.demo` |
| **Build Output Directory** | `dist` | Vite build output containing static HTML, CSS, JS, and `_redirects` |
| **Node.js Version** | `20` or `22` | Modern LTS runtime for React 19 / Vite build tools |

---

## 3. Required Environment Variables

Configure the following environment variables in the Cloudflare Pages Dashboard under **Settings > Environment variables**:

| Variable Name | Production Value | Description |
|:---|:---|:---|
| `VITE_APP_MODE` | `demo` | Inlines demo execution mode into the frontend bundle |
| `NODE_VERSION` | `20` | Pins the Cloudflare build container to Node.js 20 LTS |

> **Note on Security**: No credentials, database connection strings, JWT secrets, or private tokens are required or permitted in the frontend environment variables.

---

## 4. Step-by-Step Cloudflare Dashboard Deployment Procedure

### Step 1: Sign In to Cloudflare
1. Navigate to [https://dash.cloudflare.com/](https://dash.cloudflare.com/).
2. Sign in with your free Cloudflare account (no credit or debit card required).

### Step 2: Create a Cloudflare Pages Project
1. In the left navigation sidebar, click **Compute (Workers & Pages)** > **Pages**.
2. Click **Create an application** > **Pages** > **Connect to Git**.
3. Select your GitHub account and authorize Cloudflare to access `saipranav22-commits/gatekeeper`.
4. Click **Begin setup**.

### Step 3: Configure Build & Deployment Settings
Fill in the deployment configuration form exactly as follows:
- **Project Name**: `gatekeeper-demo` (generates `https://gatekeeper-demo.pages.dev`).
- **Production Branch**: `master`.
- **Framework Preset**: Select `Vite` from the dropdown.
- **Root Directory**: `frontend`.
- **Build Command**: `npm run build:demo`.
- **Build Output Directory**: `dist`.

### Step 4: Add Environment Variables
Expand the **Environment variables** section and add:
- Variable 1: `VITE_APP_MODE` = `demo`
- Variable 2: `NODE_VERSION` = `20`

### Step 5: Save and Deploy
1. Click **Save and Deploy**.
2. Cloudflare Pages will spin up an isolated build container, execute `npm ci`, run `npm run build:demo`, and deploy the resulting `dist/` directory across Cloudflare's global edge network.
3. Typical build duration: ~45–60 seconds.

---

## 5. Post-Deployment Verification Procedure

Once Cloudflare displays the green deployment success checkmark, navigate to the assigned URL (e.g., `https://gatekeeper-demo.pages.dev`):

### Verification Checklist:
1. **Root URL (`/`)**: Directly loads the Obsidian Titanium dark-mode Command Center with zero backend errors.
2. **Login Page (`/login`)**: Direct navigation displays the **INTERACTIVE DEMO MODE** callout banner with pre-filled demo credentials and an **Enter Demo Console →** button.
3. **One-Click Console Entry**: Clicking **Enter Demo Console →** transitions seamlessly into the Command Center without requiring network authentication.
4. **Nested Route Direct Navigation & Refresh**:
   - Navigate to `/policies`, `/analytics`, `/reliability`, and `/chaos`.
   - Refresh the browser on nested routes (e.g., `F5` on `https://gatekeeper-demo.pages.dev/policies`).
   - **Expected Result**: Page reloads directly with HTTP 200 without a 404 error (guaranteed by `public/_redirects`).
5. **Interactive Controls**:
   - **Health Scenario Selector**: Switch between `HEALTHY`, `DEGRADED`, `UNHEALTHY`, and `RECOVERING`. Observe dynamic KPI tile color transitions and badge updates.
   - **Command Palette**: Press `Ctrl + K` (or `Cmd + K`) to open the search modal and navigate across sections.
   - **Policy Studio**: Run dry-run policy simulations and inspect explainable decision traces.
   - **Reliability Workbench**: Toggle between Topology, Resilience Workbench, and Operational Timeline tabs. Trigger a manual circuit breaker reset modal.
   - **Chaos Laboratory**: Switch between Latency Spike, Upstream Outage, and Downstream Timeout scenarios to inspect localized provenance telemetry.
6. **Network Hygiene**: Open Browser Developer Tools (`F12`) > **Console** & **Network**.
   - Verify **0 critical console errors**.
   - Verify **0 failed network requests** and **0 leaked `localhost:8080` calls**.

---

## 6. Safe Rollback Instructions

Cloudflare Pages maintains an immutable deployment history for every build:
1. In Cloudflare Dashboard, navigate to **Workers & Pages** > `gatekeeper-demo` > **Deployments**.
2. Locate the previous successful deployment.
3. Click the three dots (`...`) next to the deployment and select **Rollback to this deployment**.
4. The rollback takes effect globally within seconds.

---

## 7. Zero-Cost Maintenance & Precautions

- **Bandwidth**: 100% free with unlimited egress.
- **Build Quota**: 500 builds per month (Gatekeeper typically consumes 1–5 builds per release).
- **Domain**: Keep the free `*.pages.dev` subdomain. Do not purchase a custom domain unless desired.
- **Workers / Functions**: Gatekeeper's frontend is completely static and uses zero serverless Workers or KV storage, guaranteeing ₹0 recurring cost indefinitely.
