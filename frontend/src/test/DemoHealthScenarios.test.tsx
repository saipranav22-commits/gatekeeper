import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { GatewayHealthProvider, useGatewayHealth } from '@/context/GatewayHealthContext'
import { DemoHealthScenarioSelector } from '@/components/demo/DemoHealthScenarioSelector'
import {
  setDemoHealthScenario,
  getDemoHealthScenario,
  resetDemoHealthScenario,
  getDemoRouteResilience,
  getDemoRouteResilienceDetail,
} from '@/demo/data/demoResilienceData'
import { getDemoAnalyticsOverview } from '@/demo/data/demoAnalyticsData'
import { topologyService } from '@/services/topologyService'
import { apiClient } from '@/lib/apiClient'
import axios from 'axios'
import * as appMode from '@/config/appMode'

// Mock appMode to demo mode by default
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
  }),
}))

describe('Phase 9D.3: Interactive Health States & Reliability Simulation (HEALTH-01 to HEALTH-20)', () => {
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

  it('HEALTH-01: Default state is HEALTHY', () => {
    expect(getDemoHealthScenario()).toBe('HEALTHY')
    const routes = getDemoRouteResilience()
    expect(routes.find((r) => r.routeId === 'orders-service-v1')?.circuitBreakerState).toBe('CLOSED')
  })

  it('HEALTH-02: HEALTHY uses green indicator', () => {
    setDemoHealthScenario('HEALTHY')
    const topology = topologyService.buildTopology('HEALTHY', getDemoRouteResilience(), null)
    const ordersNode = topology.nodes.find((n) => n.id === 'orders-service')
    const gatewayNode = topology.nodes.find((n) => n.id === 'gateway-core')

    expect(ordersNode?.status).toBe('HEALTHY')
    expect(gatewayNode?.status).toBe('HEALTHY')
  })

  it('HEALTH-03: DEGRADED uses amber indicator', () => {
    setDemoHealthScenario('DEGRADED')
    const routes = getDemoRouteResilience()
    const orders = routes.find((r) => r.routeId === 'orders-service-v1')
    expect(orders?.circuitBreakerState).toBe('HALF_OPEN')
    expect(orders?.failureRate).toBeGreaterThan(20)

    const topology = topologyService.buildTopology('DEGRADED', routes, null)
    const ordersNode = topology.nodes.find((n) => n.id === 'orders-service')
    expect(ordersNode?.status).toBe('DEGRADED')
  })

  it('HEALTH-04: UNHEALTHY uses red indicator', () => {
    setDemoHealthScenario('UNHEALTHY')
    const routes = getDemoRouteResilience()
    const orders = routes.find((r) => r.routeId === 'orders-service-v1')
    expect(orders?.circuitBreakerState).toBe('OPEN')
    expect(orders?.failureRate).toBeGreaterThan(50)

    const topology = topologyService.buildTopology('UNHEALTHY', routes, null)
    const ordersNode = topology.nodes.find((n) => n.id === 'orders-service')
    const gatewayNode = topology.nodes.find((n) => n.id === 'gateway-core')

    expect(ordersNode?.status).toBe('DOWN')
    expect(gatewayNode?.status).toBe('DOWN')
  })

  it('HEALTH-05: RECOVERING uses blue indicator', () => {
    setDemoHealthScenario('RECOVERING')
    const routes = getDemoRouteResilience()
    const orders = routes.find((r) => r.routeId === 'orders-service-v1')
    expect(orders?.circuitBreakerState).toBe('HALF_OPEN')
    expect(orders?.failureRate).toBeLessThanOrEqual(10)

    const topology = topologyService.buildTopology('RECOVERING', routes, null)
    const ordersNode = topology.nodes.find((n) => n.id === 'orders-service')
    const gatewayNode = topology.nodes.find((n) => n.id === 'gateway-core')

    expect(ordersNode?.status).toBe('RECOVERING')
    expect(gatewayNode?.status).toBe('RECOVERING')
  })

  it('HEALTH-06: Changing scenario updates Command Center state via selector', async () => {
    const TestComponent = () => {
      const { status, demoScenario } = useGatewayHealth()
      return (
        <div>
          <div data-testid="status-indicator">{status}</div>
          <div data-testid="active-scenario">{demoScenario}</div>
          <DemoHealthScenarioSelector />
        </div>
      )
    }

    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <TestComponent />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByTestId('status-indicator').textContent).toBe('HEALTHY')
    })

    // Click UNHEALTHY
    fireEvent.click(screen.getByTestId('demo-scenario-unhealthy'))

    await waitFor(() => {
      expect(screen.getByTestId('active-scenario').textContent).toBe('UNHEALTHY')
      expect(screen.getByTestId('status-indicator').textContent).toBe('UNHEALTHY')
    })
  })

  it('HEALTH-07: Changing scenario updates Reliability Workbench route summaries', () => {
    setDemoHealthScenario('UNHEALTHY')
    const detail = getDemoRouteResilienceDetail('orders-service-v1')
    expect(detail.circuitBreaker.state).toBe('OPEN')
    expect(detail.circuitBreaker.failureRatePercent).toBe(74.2)
    expect(detail.circuitBreaker.totalRejectedCalls).toBe(342)

    setDemoHealthScenario('RECOVERING')
    const recoveringDetail = getDemoRouteResilienceDetail('orders-service-v1')
    expect(recoveringDetail.circuitBreaker.state).toBe('HALF_OPEN')
    expect(recoveringDetail.circuitBreaker.failureRatePercent).toBe(4.8)
    expect(recoveringDetail.circuitBreaker.totalRejectedCalls).toBe(0)
  })

  it('HEALTH-08: Scenario selection persists across client-side navigation in-memory', async () => {
    setDemoHealthScenario('DEGRADED')
    expect(getDemoHealthScenario()).toBe('DEGRADED')

    // Simulate navigation by mounting a new component reading the shared context
    const ComponentA = () => {
      const { demoScenario } = useGatewayHealth()
      return <div data-testid="comp-a-scenario">{demoScenario}</div>
    }

    const { unmount } = render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <ComponentA />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    expect(screen.getByTestId('comp-a-scenario').textContent).toBe('DEGRADED')
    unmount()

    const ComponentB = () => {
      const { demoScenario } = useGatewayHealth()
      return <div data-testid="comp-b-scenario">{demoScenario}</div>
    }

    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <ComponentB />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    expect(screen.getByTestId('comp-b-scenario').textContent).toBe('DEGRADED')
  })

  it('HEALTH-09: Scenario change does not contact backend', async () => {
    const axiosGetSpy = vi.spyOn(axios, 'get')
    const axiosPostSpy = vi.spyOn(axios, 'post')
    const apiClientGetSpy = vi.spyOn(apiClient, 'get')
    const apiClientPostSpy = vi.spyOn(apiClient, 'post')

    const TestComponent = () => {
      const { setDemoScenario } = useGatewayHealth()
      return (
        <button data-testid="trigger-unhealthy" onClick={() => setDemoScenario('UNHEALTHY')}>
          Set Unhealthy
        </button>
      )
    }

    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <TestComponent />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    fireEvent.click(screen.getByTestId('trigger-unhealthy'))

    await waitFor(() => {
      expect(getDemoHealthScenario()).toBe('UNHEALTHY')
    })

    expect(axiosGetSpy).not.toHaveBeenCalled()
    expect(axiosPostSpy).not.toHaveBeenCalled()
    expect(apiClientGetSpy).not.toHaveBeenCalled()
    expect(apiClientPostSpy).not.toHaveBeenCalled()
  })

  it('HEALTH-10: Live mode ignores demo scenario state', async () => {
    vi.mocked(appMode.isDemoMode).mockReturnValue(false)

    const TestComponent = () => {
      const { setDemoScenario, demoScenario } = useGatewayHealth()
      return (
        <div>
          <span data-testid="live-scenario">{demoScenario}</span>
          <button data-testid="try-set-degraded" onClick={() => setDemoScenario('DEGRADED')}>
            Try Set
          </button>
        </div>
      )
    }

    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <TestComponent />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    // Attempting to set demo scenario in live mode must be a no-op
    fireEvent.click(screen.getByTestId('try-set-degraded'))
    expect(screen.getByTestId('live-scenario').textContent).toBe('HEALTHY')
  })

  it('HEALTH-11: Service topology reflects affected upstream (orders-service)', () => {
    setDemoHealthScenario('UNHEALTHY')
    const topology = topologyService.buildTopology('UNHEALTHY', getDemoRouteResilience(), null)
    const orders = topology.nodes.find((n) => n.id === 'orders-service')
    expect(orders?.status).toBe('DOWN')
    expect(orders?.circuitBreakerState).toBe('OPEN')
    expect(orders?.failureRate).toBe(74.2)
  })

  it('HEALTH-12: Unrelated services remain healthy', () => {
    setDemoHealthScenario('UNHEALTHY')
    const topology = topologyService.buildTopology('UNHEALTHY', getDemoRouteResilience(), {
      status: 'UP',
      components: {
        db: { status: 'UP' },
        redis: { status: 'UP' },
      },
    })

    const inventory = topology.nodes.find((n) => n.id === 'inventory-service')
    const db = topology.nodes.find((n) => n.id === 'postgres-db')
    const redis = topology.nodes.find((n) => n.id === 'redis-cache')

    expect(inventory?.status).toBe('HEALTHY')
    expect(db?.status).toBe('HEALTHY')
    expect(redis?.status).toBe('HEALTHY')
  })

  it('HEALTH-13: Circuit breaker state matches scenario', () => {
    setDemoHealthScenario('HEALTHY')
    expect(getDemoRouteResilience()[0].circuitBreakerState).toBe('CLOSED')

    setDemoHealthScenario('DEGRADED')
    expect(getDemoRouteResilience()[0].circuitBreakerState).toBe('HALF_OPEN')

    setDemoHealthScenario('UNHEALTHY')
    expect(getDemoRouteResilience()[0].circuitBreakerState).toBe('OPEN')

    setDemoHealthScenario('RECOVERING')
    expect(getDemoRouteResilience()[0].circuitBreakerState).toBe('HALF_OPEN')
  })

  it('HEALTH-14: Metrics change coherently with scenario', () => {
    const healthyOverview = getDemoAnalyticsOverview('HEALTHY')
    const unhealthyOverview = getDemoAnalyticsOverview('UNHEALTHY')
    const degradedOverview = getDemoAnalyticsOverview('DEGRADED')
    const recoveringOverview = getDemoAnalyticsOverview('RECOVERING')

    // Error requests spike in UNHEALTHY
    expect(unhealthyOverview.errorRequests).toBeGreaterThan(healthyOverview.errorRequests)
    expect(unhealthyOverview.allowedRequests).toBeLessThan(healthyOverview.allowedRequests)

    // DEGRADED has moderate error increase
    expect(degradedOverview.errorRequests).toBeGreaterThan(healthyOverview.errorRequests)
    expect(degradedOverview.errorRequests).toBeLessThan(unhealthyOverview.errorRequests)

    // RECOVERING improves upon DEGRADED
    expect(recoveringOverview.errorRequests).toBeLessThan(degradedOverview.errorRequests)
    expect(recoveringOverview.allowedRequests).toBeGreaterThan(degradedOverview.allowedRequests)
  })

  it('HEALTH-15: Reset restores healthy demo state', async () => {
    setDemoHealthScenario('UNHEALTHY')
    expect(getDemoHealthScenario()).toBe('UNHEALTHY')

    resetDemoHealthScenario()
    expect(getDemoHealthScenario()).toBe('HEALTHY')

    const routes = getDemoRouteResilience()
    expect(routes[0].circuitBreakerState).toBe('CLOSED')
  })

  it('HEALTH-16: Responsive selector remains usable on mobile', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <DemoHealthScenarioSelector />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    const radioGroup = screen.getByRole('radiogroup', { name: /select infrastructure health scenario/i })
    expect(radioGroup).toBeInTheDocument()

    const radios = screen.getAllByRole('radio')
    expect(radios.length).toBe(4)

    // Accessible labels for all 4 scenarios
    expect(screen.getByTestId('demo-scenario-healthy')).toBeInTheDocument()
    expect(screen.getByTestId('demo-scenario-degraded')).toBeInTheDocument()
    expect(screen.getByTestId('demo-scenario-unhealthy')).toBeInTheDocument()
    expect(screen.getByTestId('demo-scenario-recovering')).toBeInTheDocument()
  })

  it('HEALTH-17: Demo disclosure remains visible', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <GatewayHealthProvider>
          <DemoHealthScenarioSelector />
        </GatewayHealthProvider>
      </QueryClientProvider>
    )

    expect(
      screen.getByText(/simulated frontend telemetry only\. no real microservices or cloud resources affected\./i)
    ).toBeInTheDocument()
  })

  it('HEALTH-18: Existing resilience DTO contract tests pass', () => {
    const routes = getDemoRouteResilience()
    routes.forEach((r) => {
      expect(r).toHaveProperty('routeId')
      expect(r).toHaveProperty('circuitBreakerState')
      expect(r).toHaveProperty('failureRate')
      expect(r).toHaveProperty('slowCallRate')
      expect(r).toHaveProperty('bulkheadMaxConcurrent')
      expect(r).toHaveProperty('bulkheadAvailablePermits')
    })
  })

  it('HEALTH-19: Existing Subscription Plans badge regression passes', async () => {
    // Import Plans page and assert High-Throughput Tier badge remains
    const { Plans } = await import('@/pages/Plans')
    render(
      <QueryClientProvider client={queryClient}>
        <Plans />
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByText('High-Throughput Tier')).toBeInTheDocument()
    })
  })

  it('HEALTH-20: Scenario changes do not generate real chaos-injection requests', async () => {
    const axiosPostSpy = vi.spyOn(axios, 'post')

    setDemoHealthScenario('UNHEALTHY')
    const routes = getDemoRouteResilience()
    expect(routes[0].circuitBreakerState).toBe('OPEN')

    expect(axiosPostSpy).not.toHaveBeenCalled()
  })
})
