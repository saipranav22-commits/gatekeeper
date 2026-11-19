import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { ChaosLab } from '@/pages/ChaosLab'
import * as AuthContextModule from '@/context/AuthContext'
import { ToastProvider } from '@/context/ToastContext'
import { chaosService, HISTORICAL_CHAOS_RESULTS } from '@/services/chaosService'
import { ChaosExperiment } from '@/types/chaos'
import { AdminProfileDto } from '@/types/auth'

vi.mock('@/services/chaosService', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/services/chaosService')>()
  return {
    ...actual,
    chaosService: {
      getLiveExperiments: vi.fn(),
      getHistoricalExperiments: vi.fn().mockImplementation(() => actual.HISTORICAL_CHAOS_RESULTS),
      runExperiment: vi.fn(),
      getAllExperimentsCombined: vi.fn(),
    },
  }
})

const mockAdminUser = {
  username: 'sre@gatekeeper.local',
  roles: ['ROLE_ADMIN'],
  principal: {
    id: 'admin-1',
    email: 'sre@gatekeeper.local',
    roles: ['ROLE_ADMIN'],
  },
}

describe('Chaos Engineering Laboratory Screen', () => {
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

    vi.mocked(chaosService.getLiveExperiments).mockResolvedValue([])
    vi.mocked(chaosService.getHistoricalExperiments).mockReturnValue(HISTORICAL_CHAOS_RESULTS)
    vi.mocked(chaosService.runExperiment).mockResolvedValue({
      id: 'live-exp-1',
      scenarioType: 'ORDERS_OUTAGE',
      target: 'ORDERS_SERVICE',
      faultType: 'HTTP_ERROR',
      parameters: {},
      state: 'COMPLETED',
      createdAt: '2026-11-20T00:00:00Z',
      cleanupStatus: 'SUCCESS',
      provenance: 'LIVE_LOCAL_EXPERIMENT',
      assertions: [
        {
          name: 'CircuitBreakerTrip',
          description: 'Circuit breaker transitions from CLOSED to OPEN',
          outcome: 'PASS',
          observedDetails: 'Observed CLOSED -> OPEN transition',
        },
      ],
    } as unknown as ChaosExperiment)
  })

  const renderComponent = () => {
    return render(
      <MemoryRouter initialEntries={['/chaos']}>
        <ToastProvider>
          <ChaosLab />
        </ToastProvider>
      </MemoryRouter>
    )
  }

  it('renders Chaos Engineering Laboratory header, safety banner, and scenario ribbon', async () => {
    renderComponent()

    expect(screen.getByText('Controlled Chaos Engineering Laboratory')).toBeInTheDocument()
    expect(screen.getByText('Strict Non-Production Blast Radius Guard')).toBeInTheDocument()
    expect(screen.getByText('PRODUCTION LOCKOUT: SAFE MODE')).toBeInTheDocument()

    // Verify all 6 scenarios in ribbon
    expect(screen.getByText('ORDERS OUTAGE')).toBeInTheDocument()
    expect(screen.getByText('SLOW UPSTREAM')).toBeInTheDocument()
    expect(screen.getByText('BULKHEAD SATURATION')).toBeInTheDocument()
    expect(screen.getByText('REDIS FAILURE')).toBeInTheDocument()
    expect(screen.getByText('POLICY SAFETY')).toBeInTheDocument()
    expect(screen.getByText('RETRY STORM')).toBeInTheDocument()
  })

  it('renders verified empirical assertions and provenance for Scenario A (Orders Outage)', async () => {
    renderComponent()

    await waitFor(() => {
      expect(screen.getByText('Scenario A: Orders Service Outage')).toBeInTheDocument()
      expect(screen.getByText('Circuit Breaker Trip & Automatic Fast-Rejection')).toBeInTheDocument()
      expect(screen.getByText('HISTORICAL TEST ARTIFACT')).toBeInTheDocument()
    })

    // Verify assertions
    expect(screen.getByText('CircuitBreakerTrip')).toBeInTheDocument()
    expect(screen.getByText('FastRejection')).toBeInTheDocument()
    expect(screen.getByText('CrossRouteIsolation')).toBeInTheDocument()
    expect(screen.getByText('AutomaticRecovery')).toBeInTheDocument()

    // Verify observed detail text
    expect(screen.getByText('Observed CLOSED -> OPEN transition')).toBeInTheDocument()
    expect(screen.getByText('Fast rejection response observed (< 25 ms)')).toBeInTheDocument()
  }, 15000)

  it('switches between scenarios to display corresponding telemetry and assertions', async () => {
    renderComponent()

    await waitFor(() => {
      expect(screen.getByText('Scenario A: Orders Service Outage')).toBeInTheDocument()
    })

    // Click Scenario D: REDIS FAILURE
    fireEvent.click(screen.getByText('REDIS FAILURE'))

    await waitFor(() => {
      expect(screen.getByText('Scenario D: Redis Dependency Failure')).toBeInTheDocument()
      expect(screen.getByText('Fail-Closed Rate Limiting & Auth Protection')).toBeInTheDocument()
      expect(screen.getByText('RedisFailClosed')).toBeInTheDocument()
      expect(screen.getByText('Request was blocked with HTTP 503 SERVICE_UNAVAILABLE')).toBeInTheDocument()
    })
  }, 15000)

  it('displays Execution Gated in Prod badge when backend is in production mode', async () => {
    renderComponent()

    await waitFor(() => {
      expect(screen.getByText('Execution Gated in Prod')).toBeInTheDocument()
    })
  })
})
