# Gatekeeper Phase 7B — Command Center & Observability Architecture

## 1. System Overview & Philosophy

The Gatekeeper Command Center and Usage Analytics workspaces serve as the primary operational console for enterprise API infrastructure. Rather than presenting static or vanity charts, every element is designed for immediate operational diagnosis, traffic anomaly detection, and deep forensic auditing.

The visual system strictly adheres to the **Obsidian Titanium** design language:
- Obsidian Void (`#070A09`): Deepest canvas foundation.
- Deep Charcoal (`#131A18`): Layered surface elevated cards.
- Titanium Slate (`#899693`): Low-contrast secondary metadata and labels.
- Subdued Moss (`#375B48`): Border accents and structural grid delimiters.
- Titanium Mint (`#31E99A`): Primary active telemetry accents, nominal status.
- Pale Mint Frost (`#B8F3D6`): High-clarity primary typography and emphasis.
- Amber Alert (`#E9B35A`): Throttle warnings and half-open circuit states.
- Crimson Warning (`#F05460`): Circuit trips, scope denials, and 5xx upstream failures.

---

## 2. Command Center Architecture (`Overview.tsx`)

### 2.1 Operational Banner & Composite Health
The header of the Command Center features an operational status banner binding directly to `GatewayHealthContext`. It features:
- **Composite Health Badge**: Live indicator displaying `HEALTHY` (green pulse), `DEGRADED` (amber), `OFFLINE` (crimson), `CHECKING` (cyan), or `STALE` (slate).
- **Manual Verification Trigger**: Allows SREs to instantly re-check all upstream health and route resilience states with a single click.
- **Route Resilience Cards**: Real-time circuit breaker status cards for registered upstream microservices (`orders-service` and `inventory-service`).
  - Route ID and Circuit Breaker State tag (`CLOSED`, `OPEN`, `HALF_OPEN`).
  - Active failure rate (%) and slow call rate (%).
  - In-flight bulkhead call allocation versus capacity.

### 2.2 Operational Insights Engine
A deterministic rule engine evaluates the loaded telemetry snapshot and produces real-time operational alerts:
- **Circuit Trip Warning**: Triggers when any upstream circuit breaker is in `OPEN` or `HALF_OPEN` state.
- **Aggressive Throttling Advisory**: Triggers when the rate limit denial rate exceeds 20% over the selected window.
- **Upstream Errors Warning**: Triggers when 5xx upstream server errors exceed 10 requests.
- **Nominal Telemetry Notice**: Confirms healthy, stable gateway throughput when all metrics are within nominal operational bounds.

### 2.3 Global KPI Dashboard
A 4-column metric matrix displaying:
1. **Total Invocations**: Formatted aggregate request count.
2. **Nominal Traffic**: Total allowed requests with percentage of total volume.
3. **Throttled Invocations**: Rate-limited requests with real throttle percentage.
4. **Active Consumers**: Total onboarded API consumers with active plan allocations.

### 2.4 Interactive Throughput Timeline
An interactive time-series chart powered by Recharts:
- Toggleable granularity (`Hourly` vs `Daily`).
- Multi-series visualization displaying allowed traffic (Mint) versus throttled traffic (Amber).
- Obsidian Titanium tooltip revealing exact counts and UTC timestamp formatting on hover.

### 2.5 Plan Distribution & Top Consumers Leaderboard
- **Plan Usage Bar**: Visualizes invocation share across subscription tiers (Enterprise, Growth, Starter).
- **Top Consumers Table**: Leaderboard ranking the top 5 consumers by request volume, complete with organization name, plan tier, allowed volume, throttle denial rate, and a direct drill-down link into `/analytics?consumerId={id}`.

---

## 3. Usage Analytics Workspace (`Analytics.tsx`)

### 3.1 Global Investigation Toolbar
- **DateRangePicker**: Quick presets for `Last 1 Hour`, `Last 24 Hours`, `Last 7 Days`, `Last 30 Days`, and `Last 90 Days`, plus custom date range inputs.
- **Auto-Refresh Selector**: Options for `Off`, `15s`, `30s`, and `60s`.
- **Scope Filters**:
  - Tenant filter: Dropdown populated with all active consumers for targeted isolation.
  - Outcome filter: Filter by `All Traffic`, `Allowed Only`, `Rate Limited Only`, or `Scope Denied Only`.
- **Asynchronous Ingestion Notice**: Transparently communicates that audit event streams are ingested asynchronously through Redis Streams and batched to PostgreSQL.

### 3.2 Dual Chart Visualization Matrix
- **Throughput Area Chart**: Visualizes traffic velocity over time with gradient fill.
- **Outcome Donut Chart**: Proportional breakdown of Allowed vs Throttled vs Denied vs Error traffic with an interactive legend.

### 3.3 Consumer Drill-Down & Audit Event Log
When a specific tenant is selected from the scope filter:
- **Endpoint Invocation Distribution**: Horizontal bar chart showing invocation counts per gateway route (`/api/v1/orders`, `/api/v1/inventory`).
- **Paginated Event Audit Table**: High-resolution tabular audit trail displaying:
  - Timestamp (UTC).
  - Key Prefix (e.g. `gk_live_apex...`).
  - HTTP Method (`POST`, `GET`, etc.) with distinctive color badges.
  - Endpoint Path.
  - HTTP Status Code (200, 429, 403, 500).
  - Upstream Latency in milliseconds with threshold coloring (<50ms green, <200ms yellow, >200ms crimson).
  - Outcome category tag.
  - Correlation ID for distributed tracing lookups.

---

## 4. Gateway Health & Resilience Context (`GatewayHealthContext.tsx`)

The application-level `GatewayHealthProvider` coordinates gateway availability:
1. **Multi-Source Synthesis**: Combines `/api/v1/health` (process liveness) with `/api/v1/resilience/routes` (circuit breakers). If any upstream circuit breaker is `OPEN`, the gateway composite health is designated `DEGRADED`.
2. **Smart Tab Visibility**: Listens to browser `document.visibilitychange`. Polling intervals are automatically suspended when the user minimizes or switches tabs, preventing wasted laptop resources and unnecessary backend load.
3. **Cancellation & Cleanup**: All asynchronous fetch operations instantiate an `AbortController`. If a new check is triggered before the prior response settles, the dangling request is cleanly aborted.
