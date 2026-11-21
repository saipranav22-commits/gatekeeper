import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Plans } from '@/pages/Plans'
import { planService } from '@/services/planService'
import { SubscriptionPlanDto } from '@/types/plan'

vi.mock('@/services/planService', () => ({
  planService: {
    listPlans: vi.fn(),
  },
}))

const mockPlans: SubscriptionPlanDto[] = [
  {
    id: 'plan-enterprise-uuid',
    name: 'ENTERPRISE',
    description: 'High-volume production tier with 10,000 burst capacity',
    bucketCapacity: 10000,
    refillTokens: 1000,
    refillPeriodSeconds: 1,
    isActive: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-11-19T00:00:00Z',
  },
  {
    id: 'plan-starter-uuid',
    name: 'STARTER',
    description: 'Baseline developer evaluation tier',
    bucketCapacity: 100,
    refillTokens: 10,
    refillPeriodSeconds: 1,
    isActive: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-11-19T00:00:00Z',
  },
]

describe('Subscription Plans & Quota Management Screen', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0 },
      },
    })
    vi.mocked(planService.listPlans).mockResolvedValue(mockPlans)
  })

  it('renders plan cards with burst capacities and calculated throughput', async () => {
    render(
      <QueryClientProvider client={queryClient}>
        <Plans />
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByText('Subscription Plans & Rate Limits')).toBeInTheDocument()
      expect(screen.getAllByText('ENTERPRISE').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('STARTER').length).toBeGreaterThanOrEqual(1)
    })

    // Assert burst capacity numbers
    expect(screen.getByText('10,000')).toBeInTheDocument()
    expect(screen.getByText('100')).toBeInTheDocument()

    // Assert calculated sustained throughput
    expect(screen.getByText(/~1000\.0 req\/s/i)).toBeInTheDocument()
    expect(screen.getByText(/~10\.0 req\/s/i)).toBeInTheDocument()
  })

  it('renders subscription comparison matrix table', async () => {
    render(
      <QueryClientProvider client={queryClient}>
        <Plans />
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByText('Subscription Plan Comparison Matrix')).toBeInTheDocument()
      expect(screen.getAllByText('Redis 7 Token Bucket (Lua)').length).toBeGreaterThanOrEqual(1)
    })
  })
})
