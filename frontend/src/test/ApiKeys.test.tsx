import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiKeys } from '@/pages/ApiKeys'
import { consumerService } from '@/services/consumerService'
import { apiKeyService } from '@/services/apiKeyService'
import * as AuthContextModule from '@/context/AuthContext'
import { ToastProvider } from '@/context/ToastContext'
import { PageableResponse } from '@/types/api'
import { ConsumerDto } from '@/types/consumer'
import { ApiKeyMetadataDto } from '@/types/apiKey'

vi.mock('@/services/consumerService', () => ({
  consumerService: {
    listConsumers: vi.fn(),
  },
}))

vi.mock('@/services/apiKeyService', () => ({
  apiKeyService: {
    listKeys: vi.fn(),
    createKey: vi.fn(),
    revokeKey: vi.fn(),
  },
}))

const mockConsumerList: PageableResponse<ConsumerDto> = {
  content: [
    {
      id: 'consumer-1-uuid',
      name: 'Apex Financial',
      email: 'admin@apex.com',
      status: 'ACTIVE',
      plan: {
        id: 'plan-1',
        name: 'ENTERPRISE',
        description: 'Tier',
        bucketCapacity: 1000,
        refillTokens: 100,
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
  totalPages: 1,
  totalElements: 1,
  last: true,
  size: 100,
  number: 0,
  numberOfElements: 1,
  first: true,
  empty: false,
}

const mockKeys: ApiKeyMetadataDto[] = [
  {
    id: 'key-1-uuid',
    consumerId: 'consumer-1-uuid',
    name: 'Production Ingress Key',
    keyPrefix: 'gk_live_apexprod',
    status: 'ACTIVE',
    scopes: ['gateway:invoke', 'read:orders'],
    expiresAt: null,
    revokedAt: null,
    revocationReason: null,
    lastUsedAt: null,
    version: 1,
    isDeleted: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
]

describe('API Key Management Screen', () => {
  let queryClient: QueryClient
  let mockCanMutate = true

  beforeEach(() => {
    vi.clearAllMocks()
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0 },
      },
    })

    mockCanMutate = true

    vi.spyOn(AuthContextModule, 'useAuth').mockImplementation(() => ({
      isAuthenticated: true,
      admin: {
        id: 'admin-1',
        username: 'admin',
        email: 'admin@gatekeeper.local',
        role: 'ROLE_ADMIN',
        mustChangePassword: false,
      },
      token: 'jwt-token',
      isLoading: false,
      login: vi.fn(),
      logout: vi.fn(),
      changePassword: vi.fn(),
      hasRole: vi.fn().mockReturnValue(true),
      canMutate: vi.fn().mockImplementation(() => mockCanMutate),
      isSuperAdmin: vi.fn().mockReturnValue(true),
    }))

    vi.mocked(consumerService.listConsumers).mockResolvedValue(mockConsumerList)
    vi.mocked(apiKeyService.listKeys).mockResolvedValue(mockKeys)
  })

  it('renders API key directory with key prefix, status and scopes', async () => {
    render(
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <MemoryRouter>
            <ApiKeys />
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByText('API Key Management')).toBeInTheDocument()
      expect(screen.getByText('Production Ingress Key')).toBeInTheDocument()
      expect(screen.getByText('gk_live_apexprod')).toBeInTheDocument()
      expect(screen.getByText('Active')).toBeInTheDocument()
      expect(screen.getByText('gateway:invoke')).toBeInTheDocument()
    })
  })

  it('triggers provision key workflow and reveals one-time plaintext key', async () => {
    vi.mocked(apiKeyService.createKey).mockResolvedValueOnce({
      plaintextKey: 'gk_live_apexprod_secretkey1234567890abcdef',
      metadata: {
        id: 'key-new-uuid',
        consumerId: 'consumer-1-uuid',
        name: 'New Staging Key',
        keyPrefix: 'gk_live_apexstage',
        status: 'ACTIVE',
        scopes: ['gateway:invoke'],
        expiresAt: null,
        revokedAt: null,
        revocationReason: null,
        lastUsedAt: null,
        version: 1,
        isDeleted: false,
        createdAt: '2026-11-20T00:00:00Z',
        updatedAt: '2026-11-20T00:00:00Z',
      },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <MemoryRouter>
            <ApiKeys />
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByText('Provision API Key')).toBeInTheDocument()
      expect(screen.getAllByText(/Apex Financial/i).length).toBeGreaterThanOrEqual(1)
    })

    fireEvent.click(screen.getByText('Provision API Key'))

    await waitFor(() => {
      expect(screen.getByText('Provision Cryptographic API Key')).toBeInTheDocument()
    })

    // Fill consumer and key form
    const selects = screen.getAllByRole('combobox')
    fireEvent.change(selects[selects.length - 1], {
      target: { value: 'consumer-1-uuid' },
    })

    fireEvent.change(screen.getByLabelText(/Key Identifier/i), {
      target: { value: 'New Staging Key' },
    })

    fireEvent.click(screen.getByRole('button', { name: /Generate API Key/i }))

    await waitFor(() => {
      expect(apiKeyService.createKey).toHaveBeenCalled()
      // One-time secret modal should be rendered
      expect(screen.getByText(/Save Your API Key/i)).toBeInTheDocument()
      expect(screen.getByDisplayValue('gk_live_apexprod_secretkey1234567890abcdef')).toBeInTheDocument()
    })
  })

  it('hides provision and revoke actions when canMutate is false', async () => {
    mockCanMutate = false

    render(
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <MemoryRouter>
            <ApiKeys />
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByText('Production Ingress Key')).toBeInTheDocument()
    })

    expect(screen.queryByText('Provision API Key')).not.toBeInTheDocument()
    expect(screen.queryByText('Revoke')).not.toBeInTheDocument()
  })
})
