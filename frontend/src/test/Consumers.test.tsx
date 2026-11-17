import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Consumers } from '@/pages/Consumers'
import { consumerService } from '@/services/consumerService'
import { planService } from '@/services/planService'
import * as AuthContextModule from '@/context/AuthContext'
import { ToastProvider } from '@/context/ToastContext'

vi.mock('@/services/consumerService', () => ({
  consumerService: {
    listConsumers: vi.fn(),
    createConsumer: vi.fn(),
    changePlan: vi.fn(),
    suspendConsumer: vi.fn(),
    reactivateConsumer: vi.fn(),
    deleteConsumer: vi.fn(),
  },
}))

vi.mock('@/services/planService', () => ({
  planService: {
    listPlans: vi.fn(),
  },
}))

describe('Consumers Management Screen', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    })

    vi.spyOn(AuthContextModule, 'useAuth').mockReturnValue({
      isAuthenticated: true,
      admin: {
        id: 'admin-1',
        username: 'admin',
        email: 'admin@gatekeeper.local',
        role: 'ROLE_ADMIN',
        mustChangePassword: false,
      },
      token: 'token',
      isLoading: false,
      login: vi.fn(),
      logout: vi.fn(),
      changePassword: vi.fn(),
      hasRole: vi.fn().mockReturnValue(true),
      canMutate: vi.fn().mockReturnValue(true),
      isSuperAdmin: vi.fn().mockReturnValue(false),
    })
  })

  it('renders consumers table with data and status badge', async () => {
    const mockConsumers = {
      content: [
        {
          id: 'c1-uuid',
          name: 'Acme Corp',
          email: 'contact@acme.com',
          status: 'ACTIVE' as const,
          plan: {
            id: 'p1-uuid',
            name: 'PRO',
            description: 'Professional tier',
            bucketCapacity: 500,
            refillTokens: 50,
            refillPeriodSeconds: 1,
            isActive: true,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          },
          isDeleted: false,
          deletedAt: null,
          createdAt: '2026-01-01T00:00:00Z',
          updatedAt: '2026-01-01T00:00:00Z',
        },
      ],
      pageable: { pageNumber: 0, pageSize: 20, sort: { sorted: true, unsorted: false, empty: false }, offset: 0, paged: true, unpaged: false },
      totalPages: 1,
      totalElements: 1,
      last: true,
      size: 20,
      number: 0,
      sort: { sorted: true, unsorted: false, empty: false },
      numberOfElements: 1,
      first: true,
      empty: false,
    }

    vi.mocked(consumerService.listConsumers).mockResolvedValueOnce(mockConsumers)
    vi.mocked(planService.listPlans).mockResolvedValueOnce([])

    render(
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <MemoryRouter>
            <Consumers />
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByText('Acme Corp')).toBeInTheDocument()
      expect(screen.getByText('contact@acme.com')).toBeInTheDocument()
      expect(screen.getByText('PRO')).toBeInTheDocument()
      expect(screen.getAllByText('Active').length).toBeGreaterThanOrEqual(1)
    })
  })
})
