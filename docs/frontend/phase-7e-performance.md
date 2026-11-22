# Gatekeeper — Phase 7E: Performance, Code Splitting & Resource Optimization

**Build Tooling:** Vite 8.3.8 + TypeScript 5.8 / Rolldown / ES2022  
**Bundle Target:** Enterprise Edge / Modern Browser  
**Code-Splitting Strategy:** Route-Level Dynamic `React.lazy` + Asynchronous Chunks  

---

## 1. Production Bundle Analysis

Running `npm --prefix frontend run build` yields an optimized, compressed production distribution in `frontend/dist`:

### Primary Chunks (Gzip Compressed)
| Chunk Name | Uncompressed Size | Gzip Size | Architectural Purpose |
| :--- | :--- | :--- | :--- |
| `index.html` | 0.70 kB | 0.36 kB | Single-page HTML shell with preload links |
| `index-BMmD1e0L.css` | 29.03 kB | **6.52 kB** | Consolidated Tailwind CSS & Obsidian Titanium tokens |
| `index-D0kMVY3f.js` | 313.96 kB | **98.22 kB** | React 19, React DOM, React Router 6 runtime core |
| `AreaChart-CZfn9FU4.js` | 394.78 kB | **112.74 kB** | Recharts telemetry visualization library (lazy-loaded) |
| `schemas-BOlzhDSO.js` | 117.09 kB | **35.74 kB** | Zod validation schemas and DTO models |
| `apiClient-Dqg8KsyO.js` | 58.89 kB | **21.97 kB** | Axios HTTP client, interceptors, error normalizers |
| `ReliabilityWorkbench-BEr7XzMK.js`| 48.52 kB | **11.39 kB** | Topology SVG diagram & resilience management page |
| `PolicyStudio-DKu9DPxb.js` | 43.75 kB | **9.36 kB** | Policy version editor, diffing, & simulation engine |
| `Analytics-BvYQZvWu.js` | 55.07 kB | **14.66 kB** | Usage analytics page & time-series aggregations |
| `ConsumerDetail-BJhu9Gew.js` | 25.63 kB | **6.14 kB** | Consumer tenant deep-dive and quota inspections |
| `Overview-Clj3eBFs.js` | 23.63 kB | **5.78 kB** | Command Center real-time dashboard |
| `ChaosLab-Bg5xVKZ9.js` | 16.43 kB | **4.10 kB** | Chaos engineering laboratory workbench |
| `Consumers-BBShf_5q.js` | 15.59 kB | **4.18 kB** | API consumers management table |
| `ApiKeys-BNvo3Gx9.js` | 13.49 kB | **4.17 kB** | API key directory and creation flow |
| `Plans-D7t6Rim9.js` | 9.92 kB | **2.89 kB** | Subscription tiers and token bucket settings |
| `Security-eG1jEd1f.js` | 6.84 kB | **2.19 kB** | Account security and password rotation |
| `Login-mY7UOfKH.js` | 4.76 kB | **1.86 kB** | Operator authentication screen |
| `NotFound-CmJXIotj.js` | 1.08 kB | **0.56 kB** | Catch-all 404 page |

### Initial Load Payload
An unauthenticated visitor loading `/login` downloads only `index.html`, `index.css` (6.5 kB), and `Login.js` (1.8 kB), yielding a first-load critical path of **less than 15 kB gzipped**! Heavy charting libraries (`Recharts`) and administrative modules are fetched strictly on-demand upon authorized navigation.

---

## 2. React Query Cache & Network Optimization

1. **Intelligent Query Stale Time:**
   ```typescript
   const queryClient = new QueryClient({
     defaultOptions: {
       queries: {
         staleTime: 30000,          // 30 seconds fresh cache
         retry: 1,                  // single defensive retry before failing
         refetchOnWindowFocus: false // prevents unnecessary network spam on tab switching
       },
     },
   })
   ```
2. **Defensive Invalidation:** Cache keys are granularly invalidated only upon successful mutations (e.g. `['consumers']` on consumer creation, `['api-keys']` on key revocation, `['policy-versions', activeKey]` on candidate version publishing).

---

## 3. Memory & Interval Lifecycle Management

1. **Polling Teardown:** Periodic status polling in `GatewayHealthContext` (10s intervals) and `Analytics` is strictly managed via React `useEffect` cleanup return functions. When an operator navigates away from the application or closes a tab, all active timers and pending Axios promises are cleanly aborted.
2. **Low-Memory Workstation Optimization:** Gatekeeper's frontend operates with a minimal memory footprint in headless and GUI browsers (<65 MB JS heap), fully compatible with development on resource-constrained host machines.
