# Gatekeeper — Phase 7F: Frontend Performance Audit & Bundle Truth

**Build Tooling:** Vite 8.3.8 + Rolldown / ES2022  
**Production Artifact Directory:** `frontend/dist/`  
**Measurement Method:** Direct filesystem payload inspection and zlib gzip compression measurement  

---

## 1. Investigation of the "10–12 KB Initial Download" Claim

In the previous Phase 7E report, it was claimed that:
> *"An unauthenticated visitor loading /login downloads only index.html (0.36 kB), index.css (6.5 kB), and Login.js (1.8 kB), yielding a first-load critical path of less than 15 kB gzipped!"*

### Audit Finding: **INCORRECT CALCULATION**
The previous assessment isolated only the leaf chunk (`Login-*.js`) and CSS file, while completely ignoring the preloaded shared application core (`index-*.js`, 94.90 kB gzip), the HTTP API client module (`apiClient-*.js`, 21.24 kB gzip), and utility helpers (`utils-*.js`, 12.84 kB gzip).

In reality, `dist/index.html` explicitly preloads and imports these dependencies:
```html
<script type="module" crossorigin src="/assets/index-D0kMVY3f.js"></script>
<link rel="modulepreload" crossorigin href="/assets/rolldown-runtime-XKyaHtl9.js">
<link rel="modulepreload" crossorigin href="/assets/utils-j3PgvVVi.js">
<link rel="modulepreload" crossorigin href="/assets/apiClient-Dqg8KsyO.js">
<link rel="stylesheet" crossorigin href="/assets/index-BMmD1e0L.css">
```

---

## 2. Actual Production Network Transfer Numbers

### Initial Cold Load (Unauthenticated `/login` Route)
| Resource File | Raw Uncompressed Size | Gzip Compressed Network Transfer | Role in Initial Bootstrap |
| :--- | :--- | :--- | :--- |
| `index.html` | 0.68 kB | **0.35 kB** | Single-page application HTML entrypoint |
| `index-BMmD1e0L.css` | 28.35 kB | **6.29 kB** | Full Obsidian Titanium styling and utility classes |
| `index-D0kMVY3f.js` | 306.60 kB | **94.90 kB** | React 19, React DOM, React Router 6, TanStack Query |
| `apiClient-Dqg8KsyO.js` | 57.52 kB | **21.24 kB** | Axios HTTP client, interceptors, error normalizers |
| `utils-j3PgvVVi.js` | 38.51 kB | **12.84 kB** | Formatting utilities, date helpers, cn class merge |
| `Login-mY7UOfKH.js` | 4.65 kB | **1.82 kB** | Login form component and validation logic |
| `rolldown-runtime-*.js` | 0.71 kB | **0.42 kB** | Module loader and dynamic import runtime |
| UI Primitives (`Input`, `Card`, etc.)| ~3.50 kB | **~1.50 kB** | Form inputs and card containers |
| **TOTAL INITIAL COLD LOAD** | **440.52 kB** | **~139.36 kB** | **Actual initial transfer to render `/login`** |

**Conclusion:** The actual initial cold-load network payload is **~139.4 kB gzipped** (~440 kB uncompressed). While this represents a fast, high-performance developer console (comfortably loading in <250 ms over standard 4G/broadband), it should never be described as "10–12 kB".

---

## 3. Dynamic Chunking & Code-Splitting Efficiency

The largest library in the application is **Recharts** (used for interactive telemetry). It is strictly isolated and code-split so that it is never fetched by users browsing the login screen or non-chart management pages:

| Deferred Dynamic Chunk | Raw Size | Gzip Size | When Fetched by the Browser |
| :--- | :--- | :--- | :--- |
| `AreaChart-CZfn9FU4.js` (Recharts) | 385.53 kB | **109.18 kB** | Fetched on-demand upon navigating to `/analytics` or `/` |
| `schemas-BOlzhDSO.js` (Zod schemas)| 114.35 kB | **34.57 kB** | Fetched when validating complex forms or policy schemas |
| `ReliabilityWorkbench-*.js` | 47.39 kB | **11.13 kB** | Fetched when opening `/reliability` |
| `PolicyStudio-*.js` | 42.73 kB | **9.17 kB** | Fetched when opening `/policies` |
| `Analytics-*.js` | 53.78 kB | **14.26 kB** | Fetched when opening `/analytics` |
| `ConsumerDetail-*.js` | 25.04 kB | **6.00 kB** | Fetched when inspecting a consumer tenant |
| `Overview-*.js` | 23.08 kB | **5.66 kB** | Fetched when entering the Command Center |
| `ChaosLab-*.js` | 16.05 kB | **3.99 kB** | Fetched when opening `/chaos` |
| `Consumers-*.js` | 15.23 kB | **4.08 kB** | Fetched when opening `/consumers` |
| `ApiKeys-*.js` | 13.18 kB | **4.07 kB** | Fetched when opening `/api-keys` |
| `Plans-*.js` | 9.69 kB | **2.83 kB** | Fetched when opening `/plans` |
| `Security-*.js` | 6.68 kB | **2.14 kB** | Fetched when opening `/security` |

### Key Optimizations Verified
1. **Zero Unused Script Downloads:** An unauthenticated visitor is never burdened with the 109 kB Recharts bundle.
2. **TanStack Query Caching:** `staleTime: 30000` (30 seconds) eliminates aggressive background re-fetching and network spam on window focus.
3. **Memory & Timer Teardowns:** Polling intervals in `GatewayHealthContext` and `Analytics` clean up via React `useEffect` returns, preventing memory leaks during prolonged sessions.
