import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Analytics } from '@/pages/Analytics'
import { analyticsService } from '@/services/analyticsService'
import { consumerService } from '@/services/consumerService'
import { PageableResponse } from '@/types/api'
import { ConsumerDto } from '@/types/consumer'
import { UsageEventDetailDto } from '@/types/analytics'

vi.mock('@/services/analyticsService', () => ({
  analyticsService: {
    getOverview: vi.fn(),
    getTimeSeries: vi.fn(),
    getTopConsumers: vi.fn(),
    getConsumerAnalytics: vi.fn(),
    getConsumerUsageEvents: vi.fn(),
  },
}))

vi.mock('@/services/consumerService', () => ({
  consumerService: {
    listConsumers: vi.fn(),
  },
}))

const mockGlobalOverview = {
  totalRequests: 25000,
  allowedRequests: 23500,
  rateLimitedRequests: 1400,
  scopeDeniedRequests: 80,
  errorRequests: 20,
  rateLimitDenialPercentage: 5.6,
  usageByPlan: { ENTERPRISE: 15000, PREMIUM: 8000, FREE: 2000 },
  windowStart: '2026-11-12T00:00:00Z',
  windowEnd: '2026-11-19T00:00:00Z',
}

const mockTimeSeries = [
  {
    bucket: '2026-11-19T10:00:00Z',
    totalRequests: 1200,
    allowedRequests: 1150,
    rateLimitedRequests: 40,
    scopeDeniedRequests: 10,
    errorRequests: 0,
  },
]

const mockTopConsumers = [
  {
    consumerId: 'c1111111-1111-1111-1111-111111111111',
    consumerName: 'Apex Financial',
    consumerEmail: 'api@apex.com',
    plan: 'ENTERPRISE',
    totalRequests: 12000,
    allowedRequests: 11800,
    rateLimitedRequests: 200,
    rateLimitDenialPercentage: 1.67,
  },
]

const mockConsumerList = [
  {
    id: 'c1111111-1111-1111-1111-111111111111',
    name: 'Apex Financial',
    email: 'api@apex.com',
    status: 'ACTIVE',
    planId: 'plan-1',
    createdAt: '2026-01-01T00:00:00Z',
  },
]

describe('Usage Analytics Workspace', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0 },
      },
    })

    vi.mocked(analyticsService.getOverview).mockResolvedValue(mockGlobalOverview)
    vi.mocked(analyticsService.getTimeSeries).mockResolvedValue(mockTimeSeries)
    vi.mocked(analyticsService.getTopConsumers).mockResolvedValue(mockTopConsumers)
    vi.mocked(consumerService.listConsumers).mockResolvedValue({
      content: mockConsumerList as unknown as ConsumerDto[],
      totalElements: 1,
      totalPages: 1,
      size: 50,
      number: 0,
      last: true,
      numberOfElements: 1,
      first: true,
      empty: false,
    })
  })

  const renderAnalytics = () => {
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <Analytics />
        </MemoryRouter>
      </QueryClientProvider>
    )
  }

  it('renders Usage Analytics title, toolbar, and KPI metrics', async () => {
    renderAnalytics()

    expect(screen.getByRole('heading', { name: /Telemetry & Usage Analytics/i })).toBeInTheDocument()

    await waitFor(() => {
      expect(screen.getByText('25,000')).toBeInTheDocument() // Total ingress
      expect(screen.getAllByText('23,500')[0]).toBeInTheDocument() // Allowed ingress
      expect(screen.getAllByText('1,400')[0]).toBeInTheDocument()  // Throttled
      expect(screen.getByText('5.6%')).toBeInTheDocument()   // Denial rate
    })
  })

  it('renders consumer filter dropdown populated with registered tenants', async () => {
    renderAnalytics()

    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /Filter by Consumer/i })).toBeInTheDocument()
      expect(screen.getByText('Apex Financial (api@apex.com)')).toBeInTheDocument()
    })
  })

  it('filters scope to a specific consumer and triggers targeted audit events query', async () => {
    vi.mocked(analyticsService.getConsumerAnalytics).mockResolvedValueOnce({
      consumerId: 'c1111111-1111-1111-1111-111111111111',
      consumerName: 'Apex Financial',
      consumerEmail: 'api@apex.com',
      currentPlan: 'ENTERPRISE',
      totalRequests: 8000,
      allowedRequests: 7900,
      rateLimitedRequests: 100,
      scopeDeniedRequests: 0,
      firstSeen: '2026-01-01T00:00:00Z',
      lastSeen: '2026-11-19T12:00:00Z',
      requestsByEndpoint: { '/api/v1/orders': 5000, '/api/v1/inventory': 3000 },
      requestsByKeyPrefix: { 'gk_live_apex': 8000 },
    })

    vi.mocked(analyticsService.getConsumerUsageEvents).mockResolvedValueOnce({
      content: [
        {
          id: 'evt-1',
          timestamp: '2026-11-19T11:45:00Z',
          apiKeyId: 'key-1',
          keyPrefixSnapshot: 'gk_live_apex',
          endpointPath: '/api/v1/orders',
          httpMethod: 'POST',
          httpStatus: 200,
          latencyMs: 14,
          outcomeCategory: 'ALLOWED',
          rateLimitPlan: 'ENTERPRISE',
          correlationId: 'req-corr-999',
        },
      ],
      totalElements: 1,
      totalPages: 1,
      size: 10,
      number: 0,
      last: true,
      numberOfElements: 1,
      first: true,
      empty: false,
    } as unknown as PageableResponse<UsageEventDetailDto>)

    renderAnalytics()

    await screen.findByText('Apex Financial (api@apex.com)')

    const select = screen.getByRole('combobox', { name: /Filter by Consumer/i })
    fireEvent.change(select, { target: { value: 'c1111111-1111-1111-1111-111111111111' } })

    await waitFor(() => {
      expect(analyticsService.getConsumerAnalytics).toHaveBeenCalledWith(
        'c1111111-1111-1111-1111-111111111111',
        expect.any(String),
        expect.any(String)
      )
      expect(screen.getByText('Consumer Usage Event Audit Log')).toBeInTheDocument()
      expect(screen.getByText('/api/v1/orders')).toBeInTheDocument()
      expect(screen.getByText('14 ms')).toBeInTheDocument()
    })
  })

  it('renders Redis Streams asynchronous pipeline telemetry note', () => {
    renderAnalytics()
    expect(screen.getByText(/Asynchronous Pipeline Telemetry:/i)).toBeInTheDocument()
    expect(screen.getByText(/stream:gateway:events/i)).toBeInTheDocument()
  })
})
