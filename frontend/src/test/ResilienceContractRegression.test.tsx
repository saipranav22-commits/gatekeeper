import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { normalizeResilienceSummary, resilienceService } from '@/services/resilienceService'
import { topologyService } from '@/services/topologyService'
import { ReliabilityWorkbench } from '@/pages/ReliabilityWorkbench'
import { NotFound } from '@/pages/NotFound'
import { AppLayout } from '@/components/layout/AppLayout'
import { ToastProvider } from '@/context/ToastContext'
import * as AuthContextModule from '@/context/AuthContext'
import * as GatewayHealthModule from '@/context/GatewayHealthContext'
import { healthService } from '@/services/healthService'
import { AdminProfileDto } from '@/types/auth'

// Mock external services for UI component regression tests
vi.mock('@/services/resilienceService', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/services/resilienceService')>()
  return {
    ...actual,
    resilienceService: {
      ...actual.resilienceService,
      getRouteResilience: vi.fn(),
      getRouteResilienceDetail: vi.fn(),
      resetRouteCircuitBreaker: vi.fn(),
    },
  }
})

vi.mock('@/services/healthService')

describe('Phase 9A.2: Resilience Contract Normalization & Routing Regressions', () => {
  const mockAdmin: AdminProfileDto = {
    id: 'adm-001',
    username: 'admin',
    email: 'admin@gatekeeper.local',
    role: 'ROLE_SUPER_ADMIN',
    mustChangePassword: false,
  }

  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
      admin: mockAdmin,
      token: 'mock-jwt-token',
      isAuthenticated: true,
      isLoading: false,
      login: vi.fn(),
      logout: vi.fn(),
      changePassword: vi.fn(),
      hasRole: () => true,
      canMutate: () => true,
      isSuperAdmin: () => true,
    })
    vi.spyOn(GatewayHealthModule, 'useGatewayHealth').mockReturnValue({
      status: 'HEALTHY',
      healthData: null,
      resilienceData: null,
      lastChecked: new Date(),
      isChecking: false,
      pollingInterval: 30000,
      setPollingInterval: vi.fn(),
      refetch: vi.fn(),
      demoScenario: 'HEALTHY',
      setDemoScenario: vi.fn(),
      resetDemoScenario: vi.fn(),
    })
    vi.mocked(healthService.getActuatorHealth).mockResolvedValue({
      status: 'UP',
      components: {
        db: { status: 'UP', details: { database: 'PostgreSQL' } },
        redis: { status: 'UP', details: { version: '7.4' } },
      },
    })
  })

  // =========================================================================
  // TEST-9A2-01: Actual Spring Boot resilience payload is correctly normalized
  // =========================================================================
  it('TEST-9A2-01: normalizes real Spring Boot RouteResilienceSummaryDto payloads', () => {
    const rawBackendPayload = {
      routeId: 'orders-service-v1',
      circuitState: 'CLOSED',
      failureRatePercent: 42.5,
      slowCallRatePercent: 12.0,
      totalCalls: 1000,
      failedCalls: 425,
      slowCalls: 120,
      circuitRejectedCalls: 15,
      bulkheadMaxConcurrent: 25,
      bulkheadAvailablePermits: 20,
      bulkheadInFlightCalls: 5,
      bulkheadRejectedCalls: 2,
      retryMaxAttempts: 3,
    }

    const normalized = normalizeResilienceSummary(rawBackendPayload)

    expect(normalized.routeId).toBe('orders-service-v1')
    expect(normalized.circuitBreakerState).toBe('CLOSED')
    expect(normalized.failureRate).toBe(42.5)
    expect(normalized.slowCallRate).toBe(12.0)
    expect(normalized.totalCalls).toBe(1000)
    expect(normalized.failedCalls).toBe(425)
    expect(normalized.slowCalls).toBe(120)
    expect(normalized.rejectedCalls).toBe(15)
    expect(normalized.bulkheadMaxConcurrent).toBe(25)
    expect(normalized.bulkheadAvailablePermits).toBe(20)
    expect(normalized.bulkheadInFlightCalls).toBe(5)
    expect(normalized.bulkheadRejectedCalls).toBe(2)
    expect(normalized.retryMaxAttempts).toBe(3)
  })

  // =========================================================================
  // TEST-9A2-02: Circuit-breaker state is mapped correctly
  // =========================================================================
  it('TEST-9A2-02: maps circuitState to circuitBreakerState with uppercase normalization', () => {
    const openPayload = { routeId: 'r1', circuitState: 'open' }
    const halfOpenPayload = { routeId: 'r2', circuitState: 'half_open' }
    const legacyPayload = { routeId: 'r3', circuitBreakerState: 'CLOSED' }
    const missingPayload = { routeId: 'r4' }

    expect(normalizeResilienceSummary(openPayload).circuitBreakerState).toBe('OPEN')
    expect(normalizeResilienceSummary(halfOpenPayload).circuitBreakerState).toBe('HALF_OPEN')
    expect(normalizeResilienceSummary(legacyPayload).circuitBreakerState).toBe('CLOSED')
    expect(normalizeResilienceSummary(missingPayload).circuitBreakerState).toBe('UNKNOWN')
  })

  // =========================================================================
  // TEST-9A2-03: Failure-rate percentage renders correctly
  // =========================================================================
  it('TEST-9A2-03: extracts and formats failure-rate percentage from backend field', () => {
    const payload = {
      routeId: 'orders-service-v1',
      circuitState: 'OPEN',
      failureRatePercent: 78.49,
      circuitRejectedCalls: 40,
    }
    const normalized = normalizeResilienceSummary(payload)
    expect(normalized.failureRate).toBe(78.49)

    const topology = topologyService.buildTopology('HEALTHY', [normalized], null)
    const ordersNode = topology.nodes.find((n) => n.id === 'orders-service')
    expect(ordersNode?.statusReason).toContain('78.5% failure rate')
    expect(ordersNode?.status).toBe('DOWN')
  })

  // =========================================================================
  // TEST-9A2-04: Slow-call-rate percentage renders correctly
  // =========================================================================
  it('TEST-9A2-04: maps slowCallRatePercent to slowCallRate and detects degradation', () => {
    const payload = {
      routeId: 'orders-service-v1',
      circuitState: 'CLOSED',
      failureRatePercent: 5.0,
      slowCallRatePercent: 35.5,
      totalCalls: 200,
    }
    const normalized = normalizeResilienceSummary(payload)
    expect(normalized.slowCallRate).toBe(35.5)

    const topology = topologyService.buildTopology('HEALTHY', [normalized], null)
    const ordersNode = topology.nodes.find((n) => n.id === 'orders-service')
    expect(ordersNode?.status).toBe('DEGRADED')
    expect(ordersNode?.statusReason).toContain('35.5% slow')
  })

  // =========================================================================
  // TEST-9A2-05: Circuit rejection counters are mapped correctly
  // =========================================================================
  it('TEST-9A2-05: maps circuitRejectedCalls to rejectedCalls counter', () => {
    const payload = {
      routeId: 'orders-service-v1',
      circuitState: 'OPEN',
      failureRatePercent: 50.0,
      circuitRejectedCalls: 128,
    }
    const normalized = normalizeResilienceSummary(payload)
    expect(normalized.rejectedCalls).toBe(128)

    const topology = topologyService.buildTopology('HEALTHY', [normalized], null)
    const ordersNode = topology.nodes.find((n) => n.id === 'orders-service')
    expect(ordersNode?.statusReason).toContain('128 rejected')
  })

  // =========================================================================
  // TEST-9A2-06: Missing or nullable optional values do not crash the UI
  // =========================================================================
  it('TEST-9A2-06: safely handles null or undefined optional resilience properties', () => {
    const sparsePayload = {
      routeId: 'orders-service-v1',
      circuitState: null,
      failureRatePercent: null,
      slowCallRatePercent: undefined,
      circuitRejectedCalls: null,
    }
    const normalized = normalizeResilienceSummary(sparsePayload)
    expect(normalized.circuitBreakerState).toBe('UNKNOWN')
    expect(normalized.failureRate).toBe(0)
    expect(normalized.slowCallRate).toBe(0)
    expect(normalized.rejectedCalls).toBe(0)

    expect(() => {
      topologyService.buildTopology('HEALTHY', [normalized], null)
    }).not.toThrow()
  })

  // =========================================================================
  // TEST-9A2-07: Zero-valued metrics render correctly
  // =========================================================================
  it('TEST-9A2-07: preserves true 0.0 values without converting to fallback defaults', () => {
    const pristinePayload = {
      routeId: 'orders-service-v1',
      circuitState: 'CLOSED',
      failureRatePercent: 0.0,
      slowCallRatePercent: 0.0,
      totalCalls: 50,
      failedCalls: 0,
      slowCalls: 0,
      circuitRejectedCalls: 0,
    }
    const normalized = normalizeResilienceSummary(pristinePayload)
    expect(normalized.failureRate).toBe(0)
    expect(normalized.slowCallRate).toBe(0)
    expect(normalized.rejectedCalls).toBe(0)

    const topology = topologyService.buildTopology('HEALTHY', [normalized], null)
    const ordersNode = topology.nodes.find((n) => n.id === 'orders-service')
    expect(ordersNode?.status).toBe('HEALTHY')
    expect(ordersNode?.statusReason).toContain('50 calls handled, 0.0% error')
  })

  // =========================================================================
  // TEST-9A2-08: Non-finite or malformed numeric values do not produce NaN/Infinity
  // =========================================================================
  it('TEST-9A2-08: guards against NaN and Infinity without emitting malformed strings', () => {
    const malformedPayload = {
      routeId: 'orders-service-v1',
      circuitState: 'CLOSED',
      failureRatePercent: NaN,
      slowCallRatePercent: Infinity,
      totalCalls: 'invalid-number' as unknown as number,
    }
    const normalized = normalizeResilienceSummary(malformedPayload)
    expect(normalized.failureRate).toBe(0)
    expect(normalized.slowCallRate).toBe(0)
    expect(normalized.totalCalls).toBe(0)

    const topology = topologyService.buildTopology('HEALTHY', [normalized], null)
    const ordersNode = topology.nodes.find((n) => n.id === 'orders-service')
    expect(ordersNode?.statusReason).not.toContain('NaN')
    expect(ordersNode?.statusReason).not.toContain('Infinity')
    expect(ordersNode?.statusReason).toContain('0.0% error')
  })

  // =========================================================================
  // TEST-9A2-09: Service topology renders from an actual-contract fixture
  // =========================================================================
  it('TEST-9A2-09: generates complete topology graph from real Spring Boot routes array', () => {
    const liveBackendSummaries = [
      normalizeResilienceSummary({
        routeId: 'orders-service-v1',
        circuitState: 'CLOSED',
        failureRatePercent: 50.0,
        slowCallRatePercent: 0.0,
        totalCalls: 4,
        failedCalls: 2,
        slowCalls: 0,
        circuitRejectedCalls: 0,
        bulkheadMaxConcurrent: 10,
        bulkheadAvailablePermits: 10,
        bulkheadInFlightCalls: 0,
        bulkheadRejectedCalls: 0,
        retryMaxAttempts: 3,
      }),
      normalizeResilienceSummary({
        routeId: 'inventory-service-v1',
        circuitState: 'CLOSED',
        failureRatePercent: 0.0,
        slowCallRatePercent: 0.0,
        totalCalls: 0,
        failedCalls: 0,
        slowCalls: 0,
        circuitRejectedCalls: 0,
        bulkheadMaxConcurrent: 10,
        bulkheadAvailablePermits: 10,
        bulkheadInFlightCalls: 0,
        bulkheadRejectedCalls: 0,
        retryMaxAttempts: 3,
      }),
    ]

    const topology = topologyService.buildTopology('HEALTHY', liveBackendSummaries, null)
    expect(topology.nodes).toHaveLength(6)
    expect(topology.links).toHaveLength(5)

    const ordersNode = topology.nodes.find((n) => n.id === 'orders-service')
    expect(ordersNode).toBeDefined()
    expect(ordersNode?.status).toBe('DEGRADED') // failure rate 50% > 20%
    expect(ordersNode?.metrics?.errorRatePercent).toBe(50.0)

    const inventoryNode = topology.nodes.find((n) => n.id === 'inventory-service')
    expect(inventoryNode).toBeDefined()
    expect(inventoryNode?.status).toBe('HEALTHY')
  })

  // =========================================================================
  // TEST-9A2-10: Reliability Workbench renders without the previous toFixed exception
  // =========================================================================
  it('TEST-9A2-10: renders Reliability Workbench with live backend contract without crashing', async () => {
    const rawContract = [
      normalizeResilienceSummary({
        routeId: 'orders-service-v1',
        circuitState: 'CLOSED',
        failureRatePercent: 12.5,
        slowCallRatePercent: 2.0,
        totalCalls: 80,
        failedCalls: 10,
        slowCalls: 2,
        circuitRejectedCalls: 0,
        bulkheadMaxConcurrent: 10,
        bulkheadAvailablePermits: 10,
        bulkheadInFlightCalls: 0,
        bulkheadRejectedCalls: 0,
        retryMaxAttempts: 3,
      }),
    ]

    vi.mocked(resilienceService.getRouteResilience).mockResolvedValue(rawContract)
    vi.mocked(resilienceService.getRouteResilienceDetail).mockResolvedValue({
      routeId: 'orders-service-v1',
      circuitBreaker: {
        state: 'CLOSED',
        failureRateThreshold: 50,
        slowCallRateThreshold: 50,
        slowCallDurationMs: 2000,
        slidingWindowSize: 20,
        minimumNumberOfCalls: 10,
        waitDurationInOpenStateMs: 10000,
        permittedNumberOfCallsInHalfOpenState: 5,
        totalCalls: 80,
        successfulCalls: 70,
        failedCalls: 10,
        slowCalls: 2,
        failureRatePercent: 12.5,
        slowCallRatePercent: 2.0,
        totalRejectedCalls: 0,
      },
      bulkhead: {
        maxConcurrentCalls: 10,
        availablePermits: 10,
        inFlightCalls: 0,
        rejectedCalls: 0,
      },
      retry: {
        maxAttempts: 3,
        initialBackoffMs: 200,
        backoffMultiplier: 2.0,
        jitterFactor: 0.1,
        maxCumulativeDeadlineMs: 5000,
      },
    })

    render(
      <MemoryRouter>
        <ToastProvider>
          <ReliabilityWorkbench />
        </ToastProvider>
      </MemoryRouter>
    )

    await waitFor(() => {
      expect(screen.getByText('Reliability Command Center')).toBeInTheDocument()
      expect(screen.getAllByText('Configured Service Topology').length).toBeGreaterThanOrEqual(1)
    })
  })

  // =========================================================================
  // TEST-9A2-11: Protected routes and AppLayout behavior remain correct
  // =========================================================================
  it('TEST-9A2-11: renders AppLayout with authenticated navigation and sidebar', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <AppLayout />
      </MemoryRouter>
    )

    expect(screen.getByText('Gatekeeper')).toBeInTheDocument()
    expect(screen.getByText('Control Plane')).toBeInTheDocument()
    expect(screen.getByText('Overview')).toBeInTheDocument()
    expect(screen.getByText('Reliability & Topology')).toBeInTheDocument()
  })

  // =========================================================================
  // TEST-9A2-12: Unknown routes reach the expected Not Found view
  // =========================================================================
  it('TEST-9A2-12: catches unknown routes and renders Obsidian Titanium NotFound component', () => {
    render(
      <MemoryRouter initialEntries={['/unknown-path-does-not-exist']}>
        <Routes>
          <Route path="/404" element={<NotFound />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </MemoryRouter>
    )

    expect(screen.getByText('404')).toBeInTheDocument()
    expect(screen.getByText('Route Not Found')).toBeInTheDocument()
    expect(screen.getByText(/The route you are trying to access does not exist/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Back to Overview/i })).toBeInTheDocument()
  })
})
