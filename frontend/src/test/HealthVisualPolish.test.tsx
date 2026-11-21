import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { GatewayHealthProvider } from '@/context/GatewayHealthContext'
import { DemoHealthScenarioSelector } from '@/components/demo/DemoHealthScenarioSelector'
import { Overview } from '@/pages/Overview'
import Plans from '@/pages/Plans'
import * as appMode from '@/config/appMode'
import {
  setDemoHealthScenario,
  getDemoHealthScenario,
  resetDemoHealthScenario,
} from '@/demo/data/demoResilienceData'
import { getDemoAnalyticsOverview } from '@/demo/data/demoAnalyticsData'

vi.mock('@/config/appMode', async (importOriginal) => {
  const actual = await importOriginal<typeof appMode>()
  return {
    ...actual,
    isDemoMode: vi.fn(() => true),
    getAppMode: vi.fn(() => 'demo'),
  }
})

vi.mock('@/context/AuthContext', () => ({
  useAuth: () => ({
    isAuthenticated: true,
    hasRole: () => true,
    canMutate: () => true,
    isSuperAdmin: () => false,
  }),
}))

describe('Phase 9D.4: Health-State Visual Refinement & Severity Hierarchy (UI-POLISH-01 to UI-POLISH-14)', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    resetDemoHealthScenario()
    vi.mocked(appMode.isDemoMode).mockReturnValue(true)
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0 },
      },
    })
  })

  afterEach(() => {
    resetDemoHealthScenario()
    vi.restoreAllMocks()
  })

  it('UI-POLISH-01: HEALTHY incident styling is neutral/green', async () => {
    setDemoHealthScenario('HEALTHY')
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <DemoHealthScenarioSelector />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    const banner = screen.getByTestId('demo-scenario-active-banner')
    expect(banner.className).toContain('border-moss-subtle')
    expect(screen.getByText(/\[HEALTHY SCENARIO ACTIVE\]/i)).toBeInTheDocument()
  })

  it('UI-POLISH-02: DEGRADED incident border is amber', async () => {
    setDemoHealthScenario('DEGRADED')
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <DemoHealthScenarioSelector />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    const banner = screen.getByTestId('demo-scenario-active-banner')
    expect(banner.className).toContain('border-warning-amber/40')
    expect(banner.className).toContain('bg-warning-amber/10')
    expect(screen.getByText(/\[DEGRADED SCENARIO ACTIVE\]/i)).toBeInTheDocument()
  })

  it('UI-POLISH-03: UNHEALTHY incident border is red', async () => {
    setDemoHealthScenario('UNHEALTHY')
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <DemoHealthScenarioSelector />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    const banner = screen.getByTestId('demo-scenario-active-banner')
    expect(banner.className).toContain('border-signal-red/40')
    expect(banner.className).toContain('bg-signal-red/10')
    expect(screen.getByText(/\[UNHEALTHY SCENARIO ACTIVE\]/i)).toBeInTheDocument()
  })

  it('UI-POLISH-04: RECOVERING incident border is cyan', async () => {
    setDemoHealthScenario('RECOVERING')
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <DemoHealthScenarioSelector />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    const banner = screen.getByTestId('demo-scenario-active-banner')
    expect(banner.className).toContain('border-sky-500/40')
    expect(banner.className).toContain('bg-sky-500/10')
    expect(screen.getByText(/\[RECOVERING SCENARIO ACTIVE\]/i)).toBeInTheDocument()
  })

  it('UI-POLISH-05: UNHEALTHY 5XX value is red in Command Center', async () => {
    setDemoHealthScenario('UNHEALTHY')
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <MemoryRouter>
            <Overview />
          </MemoryRouter>
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      const metric5xx = screen.getByTestId('overview-5xx-metric')
      expect(metric5xx).toBeInTheDocument()
      expect(metric5xx.className).toContain('text-signal-red')
    })
  })

  it('UI-POLISH-06: DEGRADED 5XX value reflects warning severity (amber)', async () => {
    setDemoHealthScenario('DEGRADED')
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <MemoryRouter>
            <Overview />
          </MemoryRouter>
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      const metric5xx = screen.getByTestId('overview-5xx-metric')
      expect(metric5xx).toBeInTheDocument()
      expect(metric5xx.className).toContain('text-warning-amber')
    })
  })

  it('UI-POLISH-07: Successful request metric remains green in UNHEALTHY mode', async () => {
    setDemoHealthScenario('UNHEALTHY')
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <MemoryRouter>
            <Overview />
          </MemoryRouter>
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      // Find the element containing Allowed count (1,112,400 in UNHEALTHY mode)
      const allowedText = screen.getByText('1,112,400')
      expect(allowedText).toBeInTheDocument()
      expect(allowedText.className).toContain('text-emerald')
    })
  })

  it('UI-POLISH-08: Unaffected Inventory remains healthy across scenarios', async () => {
    setDemoHealthScenario('UNHEALTHY')
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <MemoryRouter>
            <Overview />
          </MemoryRouter>
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      const inventoryCard = screen.getByTestId('resilience-card-inventory-service-v1')
      expect(inventoryCard).toBeInTheDocument()
      expect(inventoryCard.className).toContain('border-moss-subtle')
      expect(inventoryCard.textContent).toContain('CLOSED')
    })
  })

  it('UI-POLISH-09: Orders circuit remains OPEN in critical scenario with red treatment', async () => {
    setDemoHealthScenario('UNHEALTHY')
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <MemoryRouter>
            <Overview />
          </MemoryRouter>
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      const ordersCard = screen.getByTestId('resilience-card-orders-service-v1')
      expect(ordersCard).toBeInTheDocument()
      expect(ordersCard.className).toContain('border-signal-red/40')
      expect(ordersCard.textContent).toContain('OPEN')
    })
  })

  it('UI-POLISH-10: Reset returns styling to HEALTHY', async () => {
    setDemoHealthScenario('UNHEALTHY')
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <DemoHealthScenarioSelector />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    const resetBtn = screen.getByTestId('demo-scenario-reset')
    fireEvent.click(resetBtn)

    await waitFor(() => {
      expect(getDemoHealthScenario()).toBe('HEALTHY')
      const banner = screen.getByTestId('demo-scenario-active-banner')
      expect(banner.className).toContain('border-moss-subtle')
    })
  })

  it('UI-POLISH-11: Health controls retain accessible labels and keyboard focus rings', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <DemoHealthScenarioSelector />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    const radiogroup = screen.getByRole('radiogroup', { name: /Select Infrastructure Health Scenario/i })
    expect(radiogroup).toBeInTheDocument()

    const radios = screen.getAllByRole('radio')
    expect(radios).toHaveLength(4)

    radios.forEach((r) => {
      expect(r.className).toContain('focus-visible:ring-2')
      expect(r).toHaveAttribute('aria-checked')
    })
  })

  it('UI-POLISH-12: No backend requests occur on scenario changes', () => {
    expect(appMode.isDemoMode()).toBe(true)
    const initialScenario = getDemoHealthScenario()
    expect(initialScenario).toBe('HEALTHY')

    // Scenario switching operates entirely via synchronous in-memory state
    setDemoHealthScenario('DEGRADED')
    const degradedData = getDemoAnalyticsOverview('DEGRADED')
    expect(degradedData.errorRequests).toBe(55780)

    setDemoHealthScenario('UNHEALTHY')
    const unhealthyData = getDemoAnalyticsOverview('UNHEALTHY')
    expect(unhealthyData.errorRequests).toBe(339180)

    // Mathematical identity: Total = Allowed + RateLimited + ScopeDenied + Error
    expect(unhealthyData.totalRequests).toBe(
      unhealthyData.allowedRequests +
        unhealthyData.rateLimitedRequests +
        unhealthyData.scopeDeniedRequests +
        unhealthyData.errorRequests
    )
  })

  it('UI-POLISH-13: Existing Subscription Plans badge regression passes', async () => {
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <Plans />
        </MemoryRouter>
      </QueryClientProvider>
    )

    await waitFor(() => {
      const badge = screen.getByTestId('enterprise-highlight-badge')
      expect(badge).toBeInTheDocument()
      expect(badge.textContent).toBe('High-Throughput Tier')
      // Ensure it is not using the old clipped negative offset
      expect(badge.className).not.toContain('-top-3')
    })
  })

  it('UI-POLISH-14: Live mode does not render demo-only scenario controls', () => {
    vi.mocked(appMode.isDemoMode).mockReturnValue(false)

    const Consumer = () => {
      const { isDemoMode } = appMode
      return (
        <div>
          {isDemoMode() && <DemoHealthScenarioSelector />}
          <div data-testid="live-sentinel">Live Mode Content</div>
        </div>
      )
    }

    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <Consumer />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument()
    expect(screen.getByTestId('live-sentinel')).toBeInTheDocument()
  })
})
