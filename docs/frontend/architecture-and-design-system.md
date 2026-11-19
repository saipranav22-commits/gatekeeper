# Gatekeeper — Frontend Architecture & Obsidian Titanium Design System

**Phase**: 7A  
**Status**: COMPLETE  
**Primary Tech Stack**: React 19, TypeScript, Vite 8, Tailwind CSS 3.4, React Router 7, TanStack Query 5, Vitest 5  
**Palette Identity**: Obsidian Titanium  

---

## 1. Executive Summary & Design Philosophy

Gatekeeper's frontend is engineered as a high-density, mission-critical infrastructure control plane. Rather than presenting generic light-mode SaaS aesthetics, Gatekeeper adopts the **Obsidian Titanium** design system—a visual architecture calibrated for operations engineers, security architects, and platform operators managing high-throughput distributed gateway traffic.

### Design Principles:
1. **Low Visual Fatigue**: Atmospheric dark surfaces with deep contrast hierarchies prevent operator eye strain during long-shift monitoring.
2. **Intentional Chromatic Ratios**: Color is reserved for state communication, telemetry urgency, and primary action targets. Bright hues are never used purely for decoration.
3. **Information Density without Clutter**: High-density data tables, monospace cryptographic fingerprints, and tabular numerals ensure numbers never jump or jitter during live refreshes.
4. **Keyboard-First Ergonomics**: Instant global access to any screen, command, or tenant action via the `Cmd+K` / `Ctrl+K` command palette.

---

## 2. Obsidian Titanium Token Architecture

The design tokens are declared in [tokens.css](file:///d:/Development/Gatekeeper/frontend/src/styles/tokens.css) and exposed through Tailwind CSS via [tailwind.config.js](file:///d:/Development/Gatekeeper/frontend/tailwind.config.js).

### 2.1 Visual Palette Ratios

| Tone / Token | Hex Value | Ratio | Semantic Purpose |
|---|---|---|---|
| **Obsidian Black** (`canvas`) | `#070A09` | 55% | Core background canvas, viewport body, command layer |
| **Graphite Black** (`surface`) | `#131A18` | 20% | Layered cards, elevated panels, modal dialogs, data tables |
| **Titanium Silver Grey** (`titanium`) | `#899693` | 10% | Technical metadata, secondary copy, inactive controls |
| **Muted Moss** (`moss`) | `#375B48` | 7% | Structural borders, subtle separators, container dividers |
| **Electric Emerald** (`emerald`) | `#31E99A` | 5% | Primary action buttons, active navigation, focus rings, health |
| **Frosted Mint** (`mint`) | `#B8F3D6` | 2% | Soft informational badges, query telemetry highlights |
| **Silver Frost** (`silver-frost`) | `#DCE4E1` | 1% | Primary page titles, metric digits, KPI counters |
| **Signal Red** (`signal-red`) | `#F05460` | Dynamic | Revocations, circuit trips, 403 Forbidden incidents |
| **Warning Amber** (`warning-amber`) | `#E9B35A` | Dynamic | 429 Rate limiting alerts, token bucket exhaustion |

### 2.2 Typography & Tabular Numerals

- **Display & Interface Font**: Inter (`--font-sans`), with font features `rlig`, `calt`, and `tnum` (tabular numbers) globally enabled.
- **Monospace Font**: JetBrains Mono (`--font-mono`), applied to API key prefixes, SHA-256 hashes, UUIDs, and network endpoints.
- **Tabular Numerics**: Applied across all `table`, `code`, and `.tabular-nums` elements to eliminate visual jitter on millisecond latencies, timestamps, and request counters.

---

## 3. Core UI Primitives

All atomic components in [frontend/src/components/ui/](file:///d:/Development/Gatekeeper/frontend/src/components/ui/) have been refactored to consume Obsidian Titanium tokens while preserving full backward compatibility with existing tests:

1. **Button** ([Button.tsx](file:///d:/Development/Gatekeeper/frontend/src/components/ui/Button.tsx)):
   - `primary`: Electric Emerald (`#31E99A`) background with Obsidian Black text and subtle emerald glow.
   - `secondary`: Elevated Graphite with Muted Moss border.
   - `outline`: Dark surface with Muted Moss border and emerald focus ring.
   - `danger`: Signal Red (`#F05460`) with crisp high-contrast text.
   - `ghost`: Transparent with Titanium Silver Grey text, hovering to Silver Frost.

2. **Badge** ([Badge.tsx](file:///d:/Development/Gatekeeper/frontend/src/components/ui/Badge.tsx)):
   - Pill-styled status indicators with semi-transparent tinted backgrounds and matched 30% border rings (`success`, `warning`, `danger`, `info`, `purple`, `default`, `slate`).

3. **Card & Panels** ([Card.tsx](file:///d:/Development/Gatekeeper/frontend/src/components/ui/Card.tsx)):
   - Deep Graphite background (`#131A18`), Muted Moss structural border (`#375B48` / 40%), Silver Frost titles (`#DCE4E1`), and Titanium descriptions.

4. **Input & Select** ([Input.tsx](file:///d:/Development/Gatekeeper/frontend/src/components/ui/Input.tsx), [Select.tsx](file:///d:/Development/Gatekeeper/frontend/src/components/ui/Select.tsx)):
   - Deep input well (`#0B0F0E`), Muted Moss border, and Electric Emerald focus ring (`ring-emerald/40`).

5. **Modal** ([Modal.tsx](file:///d:/Development/Gatekeeper/frontend/src/components/ui/Modal.tsx)):
   - Dark backdrop blur (`bg-black/80 backdrop-blur-sm`), Graphite dialog shell, and Muted Moss header border.

6. **Table** ([Table.tsx](file:///d:/Development/Gatekeeper/frontend/src/components/ui/Table.tsx)):
   - High-density data grid with elevated graphite header, subtle moss row dividers, hover highlights, and tabular numeric alignment.

7. **DateRangePicker** ([DateRangePicker.tsx](file:///d:/Development/Gatekeeper/frontend/src/components/ui/DateRangePicker.tsx)):
   - Segmented control in elevated graphite with active emerald button state, custom UTC-synced datetime inputs, and browser timezone badge.

8. **OneTimeSecretModal** ([OneTimeSecretModal.tsx](file:///d:/Development/Gatekeeper/frontend/src/components/auth/OneTimeSecretModal.tsx)):
   - Security-critical dialog with amber SHA-256 one-time warning, black terminal key readout in electric emerald font, single-click copy, and persistent dismiss confirmation.

---

## 4. Application Shell & Navigation Architecture

### 4.1 Layout Shell (`AppLayout.tsx`)
The root layout component encapsulates:
- Outer Obsidian Black canvas (`bg-canvas text-silver-frost`).
- Global `Cmd+K` / `Ctrl+K` keydown interceptor.
- Responsive desktop drawer and mobile backdrop sheet.

### 4.2 Sidebar (`Sidebar.tsx`)
Structured into 3 distinct operational domains:
1. **Command & Control**: Overview (`/`)
2. **API Management**: Consumers (`/consumers`), API Keys (`/api-keys`), Subscription Plans (`/plans`)
3. **Observability & Trust**: Usage Analytics (`/analytics`), Security & Account (`/security`)

Includes a live **Engine Online (v1.0)** pulsing beacon and operator session identity card with role pill.

### 4.3 Header (`Header.tsx`)
A glassmorphic control bar featuring:
- Live Gateway Telemetry beacon with animated ping dot (`bg-emerald`).
- Quick search bar button triggering the Command Palette (`Ctrl+K`).
- Operator profile and quick Sign Out action.

### 4.4 Command Palette (`CommandPalette.tsx`)
Accessible globally via `Cmd+K` or `Ctrl+K`:
- Instant substring search across screen names, operational capabilities, and keyword tags.
- Keyboard navigation (ArrowUp, ArrowDown, Enter, Escape).
- Dedicated categories: Navigation, Quick Actions (e.g. Register Consumer, Generate API Key), and System (Sign Out).
- Obsidian Titanium modal dialog with dark backdrop blur and emerald selection bar.

---

## 5. Verification & Test Metrics

- **Vitest Unit Suite**: 9/9 test suites passed, 38/38 tests passed.
  - `CommandPalette.test.tsx`: 6/6 tests passed.
  - `Login.test.tsx`: 4/4 tests passed.
  - `Consumers.test.tsx`: 1/1 test passed.
  - `OneTimeSecretModal.test.tsx`: 4/4 tests passed.
  - `AuthContext.test.tsx`: 4/4 tests passed.
  - `ProtectedRoute.test.tsx`: 3/3 tests passed.
  - `utils.test.ts`: 7/7 tests passed.
  - `apiClient.test.ts`: 3/3 tests passed.
  - `nginxSecurityConfig.test.ts`: 6/6 tests passed.
- **TypeScript Strict Compilation**: `npx tsc -b` completed with 0 errors.
- **Vite Production Build**: `vite build` generated production bundle cleanly in 9.58s.
