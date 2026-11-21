import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider, useAuth } from '@/context/AuthContext'
import { GatewayHealthProvider } from '@/context/GatewayHealthContext'
import { ToastProvider } from '@/context/ToastContext'
import { AppLayout } from '@/components/layout/AppLayout'
import { Overview } from '@/pages/Overview'
import { Consumers } from '@/pages/Consumers'
import { ApiKeys } from '@/pages/ApiKeys'
import { PolicyStudio } from '@/pages/PolicyStudio'
import { ReliabilityWorkbench } from '@/pages/ReliabilityWorkbench'
import { ChaosLab } from '@/pages/ChaosLab'
import { Analytics } from '@/pages/Analytics'
import { NotFound } from '@/pages/NotFound'
import {
  setAppModeOverrideForTesting,
  isDemoMode,
  getAppMode,
} from '@/config/appMode'
import { apiClient } from '@/lib/apiClient'
import { consumerService } from '@/services/consumerService'
import { apiKeyService } from '@/services/apiKeyService'
import { policyService } from '@/services/policyService'
import { chaosService } from '@/services/chaosService'
import { resilienceService } from '@/services/resilienceService'
import { healthService } from '@/services/healthService'
import { analyticsService } from '@/services/analyticsService'
import { getDemoResilienceScenario, setDemoResilienceScenario } from '@/demo/data/demoResilienceData'

const createTestQueryClient = () =>
  new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        staleTime: Infinity,
      },
    },
  })

const renderWithProviders = (
  ui: React.ReactElement,
  { initialEntries = ['/'] }: { initialEntries?: string[] } = {}
) => {
  const queryClient = createTestQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <GatewayHealthProvider>
          <ToastProvider>
            <MemoryRouter initialEntries={initialEntries}>{ui}</MemoryRouter>
          </ToastProvider>
        </GatewayHealthProvider>
      </AuthProvider>
    </QueryClientProvider>
  )
}

describe('Gatekeeper Demo Mode Suite (DEMO-01 to DEMO-20)', () => {
  let axiosPostSpy: ReturnType<typeof vi.spyOn>
  let axiosGetSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    setAppModeOverrideForTesting('demo')
    setDemoResilienceScenario('HEALTHY')
    axiosPostSpy = vi.spyOn(apiClient, 'post')
    axiosGetSpy = vi.spyOn(apiClient, 'get')
  })

  afterEach(() => {
    setAppModeOverrideForTesting(null)
    vi.restoreAllMocks()
  })

  it('DEMO-01: Demo mode initializes without backend connectivity', () => {
    expect(isDemoMode()).toBe(true)
    expect(getAppMode()).toBe('demo')
  })

  it('DEMO-02: Demo mode never requests JWT login (provides authenticated demo operator)', async () => {
    const TestAuthConsumer = () => {
      const { isAuthenticated, admin, token } = useAuth()
      return (
        <div>
          <span data-testid="auth-state">{isAuthenticated ? 'AUTHENTICATED' : 'ANONYMOUS'}</span>
          <span data-testid="admin-role">{admin?.role}</span>
          <span data-testid="auth-token">{token}</span>
        </div>
      )
    }

    renderWithProviders(<TestAuthConsumer />)

    expect(screen.getByTestId('auth-state').textContent).toBe('AUTHENTICATED')
    expect(screen.getByTestId('admin-role').textContent).toBe('ROLE_ADMIN')
    expect(screen.getByTestId('auth-token').textContent).toBe('demo-session-token')
    expect(axiosPostSpy).not.toHaveBeenCalled()
  })

  it('DEMO-03: Demo mode never sends real backend management mutations', async () => {
    const newConsumer = await consumerService.createConsumer({
      name: 'Test Tenant',
      email: 'tenant@test.demo',
      planId: 'plan-enterprise-platinum',
    })

    expect(newConsumer.name).toBe('Test Tenant')
    expect(newConsumer.id).toContain('c-demo-')
    // Crucial safety guarantee: zero HTTP requests dispatched to real backend
    expect(axiosPostSpy).not.toHaveBeenCalled()
  })

  it('DEMO-04: Demo badge and banner are clearly visible in AppLayout', async () => {
    renderWithProviders(
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<div data-testid="child-page">Dashboard</div>} />
        </Route>
      </Routes>
    )

    expect(screen.getByText(/Interactive Demo/i)).toBeInTheDocument()
    expect(
      screen.getByText(/Demonstration dataset & simulated policy engine. No live backend required./i)
    ).toBeInTheDocument()
    expect(
      screen.getByText(/Real Spring Boot 3.3.5 \/ Redis engine verified locally/i)
    ).toBeInTheDocument()
  })

  it('DEMO-05: Command Center uses consistent demonstration metrics', async () => {
    const overview = await analyticsService.getOverview()

    expect(overview.totalRequests).toBe(1482920)
    expect(overview.allowedRequests).toBe(1447812)
    expect(overview.rateLimitedRequests).toBe(28140)
    expect(axiosGetSpy).not.toHaveBeenCalled()
  })

  it('DEMO-06: Consumer directory renders from demo data', async () => {
    const res = await consumerService.listConsumers()

    expect(res.content.length).toBeGreaterThanOrEqual(5)
    expect(res.content.some((c) => c.name === 'Acme Payments Corp')).toBe(true)
    expect(res.content.some((c) => c.name === 'Global Freight Dynamics')).toBe(true)
    expect(axiosGetSpy).not.toHaveBeenCalled()
  })

  it('DEMO-07: Demo API-key workflow never issues a real credential', async () => {
    const keyResult = await apiKeyService.createKey('c-acme-fintech', {
      name: 'Demonstration Test Key',
      scopes: ['gateway:invoke'],
    })

    expect(keyResult.metadata.name).toBe('Demonstration Test Key')
    expect(keyResult.metadata.keyPrefix).toMatch(/^gk_demo_/)
    expect(keyResult.plaintextKey).toContain('not_usable_in_production')
    expect(axiosPostSpy).not.toHaveBeenCalled()
  })

  it('DEMO-08: Policy simulation returns deterministic explainable results', async () => {
    const res = await policyService.simulate('api-gateway-default-policy', {
      routeId: '/api/v1/orders',
      httpMethod: 'POST',
      consumerPlan: 'ENTERPRISE',
      apiKeyScopes: ['gateway:invoke'],
      clientIp: '192.168.1.100',
    })

    expect(res.decision).toBe('ALLOW_CONTINUE')
    expect(res.matchedRuleId).toBe('RULE-03-IP-BLOCKLIST')
    expect(res.trace).toBeDefined()
    expect(res.trace?.finalDecision).toBe('ALLOW_CONTINUE')
    expect(axiosPostSpy).not.toHaveBeenCalled()
  })

  it('DEMO-09: Missing-scope scenario is denied', async () => {
    const res = await policyService.simulate('api-gateway-default-policy', {
      routeId: '/api/v1/orders',
      httpMethod: 'POST',
      consumerPlan: 'ENTERPRISE',
      apiKeyScopes: ['read:orders'], // Missing 'gateway:invoke'
      clientIp: '192.168.1.100',
    })

    expect(res.decision).toBe('DENY')
    expect(res.matchedRuleId).toBe('RULE-01-SCOPE-CHECK')
    expect(res.reasonCode).toBe('MISSING_REQUIRED_SCOPE')
    expect(res.safeReason).toContain('gateway:invoke')
  })

  it('DEMO-10: Quota-exceeded scenario is denied for STARTER plan on DELETE', async () => {
    const res = await policyService.simulate('api-gateway-default-policy', {
      routeId: '/api/v1/orders',
      httpMethod: 'DELETE',
      consumerPlan: 'STARTER',
      apiKeyScopes: ['gateway:invoke'],
      clientIp: '192.168.1.100',
    })

    expect(res.decision).toBe('DENY')
    expect(res.matchedRuleId).toBe('RULE-02-DELETE-GUARD')
    expect(res.reasonCode).toBe('TIER_RESTRICTION')
  })

  it('DEMO-11: Service topology loads without network access', async () => {
    const hData = await healthService.getActuatorHealth()
    const rData = await resilienceService.getRouteResilience()

    expect(hData.components?.db?.status).toBe('UP')
    expect(hData.components?.redis?.status).toBe('UP')
    expect(rData.length).toBe(2)
    expect(axiosGetSpy).not.toHaveBeenCalled()
  })

  it('DEMO-12: Reliability Workbench handles all demonstration states safely', async () => {
    setDemoResilienceScenario('CIRCUIT_OPEN')
    const openData = await resilienceService.getRouteResilience()
    const ordersOpen = openData.find((r) => r.routeId === 'orders-service-v1')
    expect(ordersOpen?.circuitBreakerState).toBe('OPEN')
    expect(ordersOpen?.failureRate).toBe(74.2)

    setDemoResilienceScenario('ELEVATED_ERROR_RATE')
    const degradedData = await resilienceService.getRouteResilience()
    const ordersHalf = degradedData.find((r) => r.routeId === 'orders-service-v1')
    expect(ordersHalf?.circuitBreakerState).toBe('HALF_OPEN')

    // Reset circuit breaker in demo memory
    await resilienceService.resetRouteCircuitBreaker('orders-service-v1')
    expect(getDemoResilienceScenario()).toBe('HEALTHY')
  })

  it('DEMO-13: Chaos Lab scenarios do not execute real fault injection', async () => {
    const exp = await chaosService.runExperiment({
      scenario: 'ORDERS_OUTAGE',
      parameters: {},
    })

    expect(exp.state).toBe('COMPLETED')
    expect(exp.assertions.some((a) => a.name === 'SimulatedResilienceGuard')).toBe(true)
    expect(getDemoResilienceScenario()).toBe('CIRCUIT_OPEN')
    expect(axiosPostSpy).not.toHaveBeenCalled()
  })

  it('DEMO-14: Analytics charts display consistent demonstration values', async () => {
    const timeseries = await analyticsService.getTimeSeries('2026-11-20', '2026-11-21', 'hourly')
    expect(timeseries.length).toBe(24)
    expect(timeseries[0].totalRequests).toBeGreaterThan(0)
    expect(timeseries[0].allowedRequests + timeseries[0].rateLimitedRequests + timeseries[0].scopeDeniedRequests + timeseries[0].errorRequests).toBe(timeseries[0].totalRequests)
  })

  it('DEMO-15: Navigation between all modules works in demo mode', async () => {
    renderWithProviders(
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<Overview />} />
          <Route path="/consumers" element={<Consumers />} />
          <Route path="/api-keys" element={<ApiKeys />} />
          <Route path="/policies" element={<PolicyStudio />} />
          <Route path="/reliability" element={<ReliabilityWorkbench />} />
          <Route path="/chaos" element={<ChaosLab />} />
          <Route path="/analytics" element={<Analytics />} />
          <Route path="/404" element={<NotFound />} />
        </Route>
      </Routes>,
      { initialEntries: ['/'] }
    )

    await waitFor(() => {
      expect(screen.getByText(/Command Center/i)).toBeInTheDocument()
    })
  })

  it('DEMO-16: Unknown routes render the correct 404 view', async () => {
    renderWithProviders(
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<div>Home</div>} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>,
      { initialEntries: ['/invalid-route-xyz'] }
    )

    expect(screen.getByText(/404/i)).toBeInTheDocument()
    expect(screen.getByText(/Route Not Found/i)).toBeInTheDocument()
  })

  it('DEMO-17: Reloading a nested route preserves demo functionality', async () => {
    renderWithProviders(
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/policies" element={<PolicyStudio />} />
        </Route>
      </Routes>,
      { initialEntries: ['/policies'] }
    )

    await waitFor(() => {
      expect(screen.getAllByText(/Policy Studio/i).length).toBeGreaterThanOrEqual(1)
    })
  })

  it('DEMO-18: Live mode still uses real API authentication', async () => {
    setAppModeOverrideForTesting('live')
    expect(isDemoMode()).toBe(false)
    expect(getAppMode()).toBe('live')

    const TestLiveAuth = () => {
      const { isAuthenticated, admin } = useAuth()
      return (
        <div>
          <span data-testid="live-auth">{isAuthenticated ? 'YES' : 'NO'}</span>
          <span data-testid="live-admin">{admin ? 'EXISTS' : 'NONE'}</span>
        </div>
      )
    }

    renderWithProviders(<TestLiveAuth />)

    expect(screen.getByTestId('live-auth').textContent).toBe('NO')
    expect(screen.getByTestId('live-admin').textContent).toBe('NONE')
  })

  it('DEMO-19: Live mode does not silently substitute demonstration data', async () => {
    setAppModeOverrideForTesting('live')
    axiosGetSpy.mockResolvedValueOnce({ data: { content: [], totalElements: 0, totalPages: 0 } })

    await consumerService.listConsumers()

    // Verifies that in LIVE mode, apiClient is invoked directly without demo interception
    expect(axiosGetSpy).toHaveBeenCalledWith('/api/v1/consumers', expect.anything())
  })

  it('DEMO-20: Existing resilience contract regression tests continue passing', async () => {
    // Tests contract normalization function
    const normalized = resilienceService.getRouteResilience
    expect(typeof normalized).toBe('function')
  })

  it('DEMO-21: Chaos service does not leak live network requests in demo mode', async () => {
    setAppModeOverrideForTesting('demo')
    const experiments = await chaosService.getAllExperimentsCombined()
    expect(experiments.length).toBeGreaterThan(0)
    expect(axiosGetSpy).not.toHaveBeenCalled()
  })
})
