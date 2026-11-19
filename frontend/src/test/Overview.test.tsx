import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Overview } from '@/pages/Overview'
import { analyticsService } from '@/services/analyticsService'
import { consumerService } from '@/services/consumerService'
import { PageableResponse } from '@/types/api'
import { ConsumerDto } from '@/types/consumer'

vi.mock('@/services/analyticsService', () => ({
  analyticsService: {
    getOverview: vi.fn(),
    getTimeSeries: vi.fn(),
    getTopConsumers: vi.fn(),
  },
}))

vi.mock('@/services/consumerService', () => ({
  consumerService: {
    listConsumers: vi.fn(),
  },
}))

let mockHealthStatus = 'HEALTHY'
const mockResilienceData: unknown[] = [
  {
    routeId: 'orders-service',
    circuitBreakerState: 'CLOSED',
    failureRate: 0.0,
    slowCallRate: 0.0,
    totalCalls: 120,
    failedCalls: 0,
    slowCalls: 0,
    rejectedCalls: 0,
    bulkheadMaxConcurrent: 50,
    bulkheadAvailablePermits: 50,
    bulkheadInFlightCalls: 0,
    bulkheadRejectedCalls: 0,
    retryMaxAttempts: 3,
  },
  {
    routeId: 'inventory-service',
    circuitBreakerState: 'CLOSED',
    failureRate: 2.5,
    slowCallRate: 1.0,
    totalCalls: 85,
    failedCalls: 2,
    slowCalls: 1,
    rejectedCalls: 0,
    bulkheadMaxConcurrent: 50,
    bulkheadAvailablePermits: 48,
    bulkheadInFlightCalls: 2,
    bulkheadRejectedCalls: 0,
    retryMaxAttempts: 3,
  },
]

vi.mock('@/context/GatewayHealthContext', () => ({
  useGatewayHealth: () => ({
    status: mockHealthStatus,
    resilienceData: mockResilienceData,
    lastChecked: new Date('2026-11-19T22:00:00Z'),
    isChecking: false,
    pollingInterval: 30000,
    setPollingInterval: vi.fn(),
    refetch: vi.fn(),
  }),
}))

const mockOverviewFixture = {
  totalRequests: 15420,
  allowedRequests: 14200,
  rateLimitedRequests: 1120,
  scopeDeniedRequests: 80,
  errorRequests: 20,
  rateLimitDenialPercentage: 7.26,
  usageByPlan: {
    ENTERPRISE: 9500,
    PREMIUM: 4100,
    STANDARD: 1500,
    FREE: 320,
  },
  windowStart: '2026-11-18T22:00:00Z',
  windowEnd: '2026-11-19T22:00:00Z',
}

const mockTimeSeriesFixture = [
  {
    bucket: '2026-11-19T10:00:00Z',
    totalRequests: 500,
    allowedRequests: 480,
    rateLimitedRequests: 15,
    scopeDeniedRequests: 5,
    errorRequests: 0,
  },
  {
    bucket: '2026-11-19T11:00:00Z',
    totalRequests: 750,
    allowedRequests: 700,
    rateLimitedRequests: 45,
    scopeDeniedRequests: 5,
    errorRequests: 0,
  },
]

const mockTopConsumersFixture = [
  {
    consumerId: 'c1111111-1111-1111-1111-111111111111',
    consumerName: 'Acme Payments Corp',
    consumerEmail: 'api@acmepayments.com',
    plan: 'ENTERPRISE',
    totalRequests: 8200,
    allowedRequests: 8000,
    rateLimitedRequests: 200,
    rateLimitDenialPercentage: 2.44,
  },
  {
    consumerId: 'c2222222-2222-2222-2222-222222222222',
    consumerName: 'Nova Logistics Ltd',
    consumerEmail: 'dev@novalogistics.io',
    plan: 'PREMIUM',
    totalRequests: 4100,
    allowedRequests: 3600,
    rateLimitedRequests: 500,
    rateLimitDenialPercentage: 12.2,
  },
]

describe('Command Center (Overview)', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    mockHealthStatus = 'HEALTHY'
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0 },
      },
    })

    vi.mocked(analyticsService.getOverview).mockResolvedValue(mockOverviewFixture)
    vi.mocked(analyticsService.getTimeSeries).mockResolvedValue(mockTimeSeriesFixture)
    vi.mocked(analyticsService.getTopConsumers).mockResolvedValue(mockTopConsumersFixture)
    vi.mocked(consumerService.listConsumers).mockResolvedValue({
      content: [],
      totalElements: 12,
      totalPages: 1,
      size: 1,
      number: 0,
      last: true,
      numberOfElements: 0,
      first: true,
      empty: true,
    } as unknown as PageableResponse<ConsumerDto>)
  })

  const renderOverview = () => {
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <Overview />
        </MemoryRouter>
      </QueryClientProvider>
    )
  }

  it('renders Command Center headings, health badge, and real KPI metrics', async () => {
    renderOverview()

    expect(screen.getByRole('heading', { name: /Command Center/i })).toBeInTheDocument()
    expect(screen.getByText('HEALTHY')).toBeInTheDocument()

    await waitFor(() => {
      expect(screen.getByText('15,420')).toBeInTheDocument() // Total requests
      expect(screen.getByText('14,200')).toBeInTheDocument() // Allowed
      expect(screen.getByText('1,120')).toBeInTheDocument()  // Throttled
      expect(screen.getByText('80')).toBeInTheDocument()     // Denied
      expect(screen.getByText('20')).toBeInTheDocument()     // Errors
      expect(screen.getByText('7.3%')).toBeInTheDocument()   // Throttle rate (7.26% rounded to 1 decimal)
    })
  })

  it('renders upstream microservice resilience cards with circuit breaker statuses', async () => {
    renderOverview()

    await waitFor(() => {
      expect(screen.getByText('orders-service')).toBeInTheDocument()
      expect(screen.getByText('inventory-service')).toBeInTheDocument()
    })
  })

  it('renders top consumer organization rankings with links to drill down', async () => {
    renderOverview()

    await waitFor(() => {
      expect(screen.getByText('Acme Payments Corp')).toBeInTheDocument()
      expect(screen.getByText('Nova Logistics Ltd')).toBeInTheDocument()
      expect(screen.getByText('api@acmepayments.com')).toBeInTheDocument()
      expect(screen.getAllByText('Drilldown')[0]).toBeInTheDocument()
    })
  })

  it('renders empty dataset message when no traffic exists in window', async () => {
    vi.mocked(analyticsService.getOverview).mockResolvedValueOnce({
      totalRequests: 0,
      allowedRequests: 0,
      rateLimitedRequests: 0,
      scopeDeniedRequests: 0,
      errorRequests: 0,
      rateLimitDenialPercentage: 0.0,
      usageByPlan: {},
      windowStart: '2026-11-18T22:00:00Z',
      windowEnd: '2026-11-19T22:00:00Z',
    })
    vi.mocked(analyticsService.getTimeSeries).mockResolvedValueOnce([])
    vi.mocked(analyticsService.getTopConsumers).mockResolvedValueOnce([])

    renderOverview()

    await waitFor(() => {
      expect(screen.getByText(/No gateway traffic logged in this time window/i)).toBeInTheDocument()
      expect(screen.getByText(/No consumer traffic recorded in this period/i)).toBeInTheDocument()
    })
  })

  it('displays offline alert when gateway status is OFFLINE', async () => {
    mockHealthStatus = 'OFFLINE'
    renderOverview()

    await waitFor(() => {
      expect(screen.getByText(/Gateway Backend Offline or Unreachable/i)).toBeInTheDocument()
    })
  })
})
