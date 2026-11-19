import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PolicyStudio } from '@/pages/PolicyStudio'
import { policyService } from '@/services/policyService'
import * as AuthContextModule from '@/context/AuthContext'
import { ToastProvider } from '@/context/ToastContext'
import {
  PolicyDefinitionDto,
  PolicyVersionDto,
  PolicySimulationResponse,
  PolicyReplayResponse,
  PolicyActivationAuditDto,
} from '@/types/policy'

vi.mock('@/services/policyService', () => ({
  policyService: {
    listDefinitions: vi.fn(),
    getDefinition: vi.fn(),
    createDefinition: vi.fn(),
    listVersions: vi.fn(),
    getVersion: vi.fn(),
    createVersion: vi.fn(),
    activateVersion: vi.fn(),
    rollback: vi.fn(),
    configureShadow: vi.fn(),
    simulate: vi.fn(),
    replay: vi.fn(),
    getAuditHistory: vi.fn(),
  },
}))

const mockDefinitions: PolicyDefinitionDto[] = [
  {
    id: 'def-1-uuid',
    policyKey: 'orders-policy',
    name: 'Orders Microservice Ingress',
    description: 'Enforces rate limits, enterprise scopes, and business hours on orders',
    activeVersionNumber: 1,
    shadowVersionNumber: null,
    versionLock: 3,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-11-19T00:00:00Z',
  },
]

const mockVersions: PolicyVersionDto[] = [
  {
    id: 'ver-1-uuid',
    policyKey: 'orders-policy',
    versionNumber: 1,
    status: 'ACTIVE',
    description: 'Initial production rule baseline',
    rulesCount: 2,
    rules: [
      {
        ruleId: 'deny-unauthorized-external-ips',
        priority: 10,
        enabled: true,
        conditions: {
          routeId: '/api/v1/orders',
          httpMethod: 'POST',
          consumerPlan: 'STARTER',
        },
        action: 'DENY',
        description: 'Starter tier cannot post orders',
        reasonCode: 'TIER_RESTRICTION',
        safeReason: 'Starter subscription plan does not permit order creation',
      },
      {
        ruleId: 'allow-enterprise-orders',
        priority: 100,
        enabled: true,
        conditions: {
          routeId: '/api/v1/orders',
          consumerPlan: 'ENTERPRISE',
          requiredScopes: ['gateway:invoke', 'write:orders'],
        },
        action: 'ALLOW_CONTINUE',
        description: 'Allows verified enterprise order dispatch',
        reasonCode: 'POLICY_PERMITTED',
        safeReason: 'Enterprise order invocation allowed',
      },
    ],
    createdAt: '2026-01-01T00:00:00Z',
    createdBy: 'sec-admin',
    activatedAt: '2026-01-01T00:00:00Z',
    activatedBy: 'sec-admin',
  },
  {
    id: 'ver-2-uuid',
    policyKey: 'orders-policy',
    versionNumber: 2,
    status: 'DRAFT',
    description: 'Candidate version with expanded scope checks',
    rulesCount: 1,
    rules: [
      {
        ruleId: 'allow-all-enterprise',
        priority: 50,
        enabled: true,
        conditions: {
          routeId: '/api/v1/orders',
          consumerPlan: 'ENTERPRISE',
        },
        action: 'ALLOW_CONTINUE',
        description: 'Candidate test rule',
        reasonCode: 'POLICY_PERMITTED',
        safeReason: 'Candidate allowed',
      },
    ],
    createdAt: '2026-11-19T00:00:00Z',
    createdBy: 'lead-architect',
    activatedAt: null,
    activatedBy: null,
  },
]

const mockSimulationResult: PolicySimulationResponse = {
  policyKey: 'orders-policy',
  versionEvaluated: 1,
  decision: 'DENY',
  matchedRuleId: 'deny-unauthorized-external-ips',
  reasonCode: 'TIER_RESTRICTION',
  safeReason: 'Starter subscription plan does not permit order creation',
  hypotheticalQuotaConsumption: false,
  durationNanos: 42000,
  trace: {
    evaluationId: 'eval-1',
    timestamp: '2026-11-20T00:00:00Z',
    policyKey: 'orders-policy',
    versionNumber: 1,
    routeId: '/api/v1/orders',
    finalDecision: 'DENY',
    matchedRuleId: 'deny-unauthorized-external-ips',
    reasonCode: 'TIER_RESTRICTION',
    safeReason: 'Starter subscription plan does not permit order creation',
    evaluationDurationNanos: 42000,
    candidateRulesCount: 2,
    ruleEvaluations: [
      {
        ruleId: 'deny-unauthorized-external-ips',
        priority: 10,
        matched: true,
        action: 'DENY',
        matchExplanation: 'Matched route /api/v1/orders and consumerPlan STARTER',
        nonMatchReason: null,
      },
    ],
  },
}

const mockReplayResult: PolicyReplayResponse = {
  policyKey: 'orders-policy',
  activeVersionEvaluated: 1,
  candidateVersionEvaluated: 2,
  totalRecordsSampled: 100,
  totalEligibleRecords: 95,
  decisionsUnchanged: 85,
  newlyDenied: 10,
  newlyPermitted: 0,
  notEvaluableCount: 5,
  sampleDifferences: [
    {
      eventId: 'evt-diff-1',
      endpointPath: '/api/v1/orders',
      httpMethod: 'POST',
      consumerPlan: 'STARTER',
      activeDecision: 'DENY',
      candidateDecision: 'ALLOW_CONTINUE',
      differenceReason: 'Candidate v2 removed starter prohibition',
    },
  ],
}

const mockAuditTrail: PolicyActivationAuditDto[] = [
  {
    id: 'audit-1',
    policyKey: 'orders-policy',
    versionNumber: 1,
    previousVersionNumber: null,
    action: 'ACTIVATED',
    actor: 'admin@gatekeeper.local',
    reason: 'Initial production promotion',
    createdAt: '2026-01-01T00:00:00Z',
  },
]

describe('Policy Studio & Rule Engine Screen', () => {
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

    vi.mocked(policyService.listDefinitions).mockResolvedValue(mockDefinitions)
    vi.mocked(policyService.listVersions).mockResolvedValue(mockVersions)
    vi.mocked(policyService.getVersion).mockImplementation((_key, ver) => {
      const match = mockVersions.find((v) => v.versionNumber === ver)
      return Promise.resolve(match || mockVersions[0])
    })
    vi.mocked(policyService.simulate).mockResolvedValue(mockSimulationResult)
    vi.mocked(policyService.replay).mockResolvedValue(mockReplayResult)
    vi.mocked(policyService.getAuditHistory).mockResolvedValue(mockAuditTrail)
  })

  it('renders policy definition overview, version ribbon, and active rules', async () => {
    render(
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <PolicyStudio />
        </ToastProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByText('Policy Studio & Rule Engine')).toBeInTheDocument()
      expect(screen.getByText(/Orders Microservice Ingress/i)).toBeInTheDocument()
      expect(screen.getAllByText('v1').length).toBeGreaterThanOrEqual(1)
      expect(screen.getByText('Enforcing Live')).toBeInTheDocument()
      expect(screen.getByText('deny-unauthorized-external-ips')).toBeInTheDocument()
      expect(screen.getByText('allow-enterprise-orders')).toBeInTheDocument()
      expect(screen.getByText('DENY')).toBeInTheDocument()
      expect(screen.getByText('ALLOW_CONTINUE')).toBeInTheDocument()
    })
  })

  it('runs dry-run simulation and renders explainable decision trace', async () => {
    render(
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <PolicyStudio />
        </ToastProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByText('Dry-Run Simulation')).toBeInTheDocument()
    })

    // Switch to Simulation Tab
    fireEvent.click(screen.getByText('Dry-Run Simulation'))

    await waitFor(() => {
      expect(screen.getByText('Configure Simulation Probe')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByText('Execute Dry-Run Simulation'))

    await waitFor(() => {
      expect(policyService.simulate).toHaveBeenCalled()
      expect(screen.getByText('Evaluation Decision')).toBeInTheDocument()
      expect(screen.getByText('42.0 µs')).toBeInTheDocument()
      expect(screen.getByText('Sequential Rule Evaluation Trace')).toBeInTheDocument()
      expect(screen.getByText('MATCHED')).toBeInTheDocument()
    })
  })

  it('runs historical traffic replay and renders discrepancy statistics', async () => {
    render(
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <PolicyStudio />
        </ToastProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByText('Historical Replay')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByText('Historical Replay'))

    await waitFor(() => {
      expect(screen.getByText('Historical Traffic Replay Impact Analysis')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByText('Run Historical Replay'))

    await waitFor(() => {
      expect(policyService.replay).toHaveBeenCalled()
      expect(screen.getByText('100')).toBeInTheDocument() // Sampled records
      expect(screen.getByText('85')).toBeInTheDocument() // Unchanged
      expect(screen.getByText('Decision Outcome Discrepancies')).toBeInTheDocument()
      expect(screen.getByText('Candidate v2 removed starter prohibition')).toBeInTheDocument()
    })
  })

  it('renders governance audit trail history', async () => {
    render(
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <PolicyStudio />
        </ToastProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByText('Governance Audit Trail')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByText('Governance Audit Trail'))

    await waitFor(() => {
      expect(screen.getByText('Policy Activation & Governance Audit History')).toBeInTheDocument()
      expect(screen.getByText('Initial production promotion')).toBeInTheDocument()
      expect(screen.getByText('admin@gatekeeper.local')).toBeInTheDocument()
    })
  })

  it('hides mutating actions when canMutate is false', async () => {
    mockCanMutate = false

    render(
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <PolicyStudio />
        </ToastProvider>
      </QueryClientProvider>
    )

    await waitFor(() => {
      expect(screen.getByText('Policy Studio & Rule Engine')).toBeInTheDocument()
    })

    expect(screen.queryByText('New Policy Container')).not.toBeInTheDocument()
    expect(screen.queryByText('New Version')).not.toBeInTheDocument()
    expect(screen.queryByText('Rollback')).not.toBeInTheDocument()
  })
})
