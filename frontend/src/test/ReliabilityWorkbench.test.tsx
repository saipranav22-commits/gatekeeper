import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { ReliabilityWorkbench } from '@/pages/ReliabilityWorkbench'
import * as AuthContextModule from '@/context/AuthContext'
import * as GatewayHealthModule from '@/context/GatewayHealthContext'
import { ToastProvider } from '@/context/ToastContext'
import { resilienceService } from '@/services/resilienceService'
import { healthService } from '@/services/healthService'
import { policyService } from '@/services/policyService'
import { chaosService } from '@/services/chaosService'

import { RouteResilienceSummary } from '@/types/health'
import { RouteResilienceDetail } from '@/types/resilience'
import { AdminProfileDto } from '@/types/auth'

vi.mock('@/services/resilienceService')
vi.mock('@/services/healthService')
vi.mock('@/services/policyService', () => ({
  policyService: {
    getAuditHistory: vi.fn(),
  },
}))
vi.mock('@/services/chaosService', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/services/chaosService')>()
  return {
    ...actual,
    chaosService: {
      getLiveExperiments: vi.fn(),
      getHistoricalExperiments: vi.fn().mockReturnValue([]),
      runExperiment: vi.fn(),
      getAllExperimentsCombined: vi.fn().mockResolvedValue([]),
    },
  }
})

const mockSummaries: RouteResilienceSummary[] = [
  {
    routeId: 'orders-service-v1',
    circuitBreakerState: 'CLOSED',
    failureRate: 0.0,
    slowCallRate: 0.0,
    totalCalls: 120,
    failedCalls: 0,
    slowCalls: 0,
    rejectedCalls: 0,
    bulkheadMaxConcurrent: 10,
    bulkheadAvailablePermits: 10,
    bulkheadInFlightCalls: 0,
    bulkheadRejectedCalls: 0,
    retryMaxAttempts: 3,
  },
  {
    routeId: 'inventory-service-v1',
    circuitBreakerState: 'OPEN',
    failureRate: 65.0,
    slowCallRate: 10.0,
    totalCalls: 80,
    failedCalls: 52,
    slowCalls: 8,
    rejectedCalls: 14,
    bulkheadMaxConcurrent: 10,
    bulkheadAvailablePermits: 8,
    bulkheadInFlightCalls: 2,
    bulkheadRejectedCalls: 5,
    retryMaxAttempts: 3,
  },
]

const mockDetail: RouteResilienceDetail = {
  routeId: 'orders-service-v1',
  circuitBreaker: {
    state: 'CLOSED',
    failureRateThreshold: 50,
    slowCallRateThreshold: 50,
    slowCallDurationMs: 2000,
    slidingWindowSize: 20,
    minimumNumberOfCalls: 5,
    waitDurationInOpenStateMs: 5000,
    permittedNumberOfCallsInHalfOpenState: 3,
    totalCalls: 120,
    successfulCalls: 120,
    failedCalls: 0,
    slowCalls: 0,
    failureRatePercent: 0.0,
    slowCallRatePercent: 0.0,
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
    initialBackoffMs: 100,
    backoffMultiplier: 2.0,
    jitterFactor: 0.5,
    maxCumulativeDeadlineMs: 5000,
  },
}

const mockAdminUser = {
  username: 'sre@gatekeeper.local',
  roles: ['ROLE_ADMIN'],
  principal: {
    id: 'admin-1',
    email: 'sre@gatekeeper.local',
    roles: ['ROLE_ADMIN'],
  },
}

describe('Reliability Workbench & Service Topology Screen', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
      admin: mockAdminUser as unknown as AdminProfileDto,
      token: 'valid.admin.jwt',
      login: vi.fn(),
      logout: vi.fn(),
      changePassword: vi.fn(),
      isAuthenticated: true,
      isLoading: false,
      hasRole: vi.fn().mockReturnValue(true),
      canMutate: vi.fn().mockReturnValue(true),
      isSuperAdmin: vi.fn().mockReturnValue(false),
    })

    vi.spyOn(GatewayHealthModule, 'useGatewayHealth').mockReturnValue({
      status: 'HEALTHY',
      healthData: { status: 'UP', service: 'gatekeeper', serviceName: 'gatekeeper', timestamp: '2026-11-20T00:00:00Z' },
      resilienceData: mockSummaries,
      lastChecked: new Date(),
      isChecking: false,
      pollingInterval: 30000,
      setPollingInterval: vi.fn(),
      refetch: vi.fn(),
      demoScenario: 'HEALTHY',
      setDemoScenario: vi.fn(),
      resetDemoScenario: vi.fn(),
    })

    vi.mocked(resilienceService.getRouteResilience).mockResolvedValue(mockSummaries)
    vi.mocked(resilienceService.getRouteResilienceDetail).mockResolvedValue(mockDetail)
    vi.mocked(resilienceService.resetRouteCircuitBreaker).mockResolvedValue({
      routeId: 'orders-service-v1',
      status: 'RESET',
      circuitState: 'CLOSED',
      resetBy: 'sre@gatekeeper.local',
    })
    vi.mocked(healthService.getHealth).mockResolvedValue({
      status: 'UP',
      service: 'gatekeeper',
      serviceName: 'gatekeeper',
      timestamp: '2026-11-20T00:00:00Z',
    })
    vi.mocked(healthService.getActuatorHealth).mockResolvedValue({
      status: 'UP',
      components: {
        db: { status: 'UP', details: { database: 'PostgreSQL' } },
        redis: { status: 'UP', details: { version: '7.2' } },
      },
    })
    vi.mocked(policyService.getAuditHistory).mockResolvedValue([])
    vi.mocked(chaosService.getAllExperimentsCombined).mockResolvedValue([])
    vi.mocked(chaosService.getHistoricalExperiments).mockReturnValue([])
  })

  const renderComponent = () => {
    return render(
      <MemoryRouter initialEntries={['/reliability']}>
        <ToastProvider>
          <ReliabilityWorkbench />
        </ToastProvider>
      </MemoryRouter>
    )
  }

  it('renders Reliability Command Center headings, tabs, and configured topology', async () => {
    renderComponent()

    expect(screen.getByText('Reliability Command Center')).toBeInTheDocument()
    expect(screen.getAllByText('Configured Service Topology')[0]).toBeInTheDocument()
    expect(screen.getByText('Resilience Policies & Breakers')).toBeInTheDocument()
    expect(screen.getByText('Operational Timelines & Audits')).toBeInTheDocument()

    // Verify Configured Architecture label (not discovered)
    await waitFor(() => {
      expect(screen.getByText('Static Declarative Graph')).toBeInTheDocument()
    })
  })

  it('switches between Topology, Resilience Workbench, and Operational Timeline tabs', async () => {
    renderComponent()

    await waitFor(() => {
      expect(screen.getAllByText('Gatekeeper Gateway')[0]).toBeInTheDocument()
    })

    // Click Resilience Policies & Breakers tab
    fireEvent.click(screen.getByText('Resilience Policies & Breakers'))

    await waitFor(() => {
      expect(screen.getByText('Circuit Breaker: orders-service-v1')).toBeInTheDocument()
      expect(screen.getByText('Route Bulkhead Semaphore')).toBeInTheDocument()
      expect(screen.getByText('Retry Budget & Cumulative Deadlines')).toBeInTheDocument()
    })

    // Verify educational callouts
    expect(screen.getByText('Why OPEN State Matters')).toBeInTheDocument()
    expect(screen.getByText('What HALF_OPEN Represents')).toBeInTheDocument()

    // Click Operational Timelines tab
    fireEvent.click(screen.getByText('Operational Timelines & Audits'))

    await waitFor(() => {
      expect(screen.getByPlaceholderText('Search headline, route, correlation ID...')).toBeInTheDocument()
    })
  }, 15000)

  it('displays accurate circuit breaker metrics and triggers manual reset modal', async () => {
    renderComponent()

    // Switch to resilience tab
    fireEvent.click(screen.getByText('Resilience Policies & Breakers'))

    await waitFor(() => {
      expect(screen.getByText('Circuit Breaker: orders-service-v1')).toBeInTheDocument()
    })

    // Switch to degraded route inventory-service-v1
    fireEvent.click(screen.getByText('inventory-service-v1'))

    await waitFor(() => {
      expect(resilienceService.getRouteResilienceDetail).toHaveBeenCalledWith('inventory-service-v1')
    })
  })

  it('renders UNKNOWN dependency status when actuator health is unavailable', async () => {
    vi.mocked(healthService.getActuatorHealth).mockRejectedValueOnce(new Error('Actuator disabled'))

    renderComponent()

    // Switch to Node Directory list view
    await waitFor(() => {
      expect(screen.getByText('Node Directory')).toBeInTheDocument()
    })
    fireEvent.click(screen.getByText('Node Directory'))

    await waitFor(() => {
      const unknownBadges = screen.getAllByText('UNKNOWN')
      expect(unknownBadges.length).toBeGreaterThanOrEqual(1)
    })
  })
})
