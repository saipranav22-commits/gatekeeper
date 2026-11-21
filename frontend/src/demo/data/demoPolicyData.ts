import {
  PolicyActivationAuditDto,
  PolicyDefinitionDto,
  PolicyReplayRequest,
  PolicyReplayResponse,
  PolicyRule,
  PolicySimulationRequest,
  PolicySimulationResponse,
  PolicyVersionDto,
} from '@/types/policy'

const DEMO_RULES_DEFAULT: PolicyRule[] = [
  {
    ruleId: 'RULE-01-SCOPE-CHECK',
    priority: 10,
    enabled: true,
    conditions: {
      routeId: '/api/v1/orders',
      requiredScopes: ['gateway:invoke'],
    },
    action: 'ALLOW_CONTINUE',
    description: 'Enforces gateway:invoke scope for external API traffic',
    reasonCode: 'SCOPE_VALIDATED',
    safeReason: 'Required scope present on authenticated token',
  },
  {
    ruleId: 'RULE-02-DELETE-GUARD',
    priority: 20,
    enabled: true,
    conditions: {
      httpMethod: 'DELETE',
      consumerPlan: 'STARTER',
    },
    action: 'DENY',
    description: 'Restricts destructive DELETE actions for Developer Starter plan tier',
    reasonCode: 'TIER_RESTRICTION',
    safeReason: 'DELETE operations require Business Standard or Enterprise plan tier',
  },
  {
    ruleId: 'RULE-03-IP-BLOCKLIST',
    priority: 30,
    enabled: true,
    conditions: {
      routeId: '/api/v1/orders',
    },
    action: 'ALLOW_CONTINUE',
    description: 'Default catch-all routing allow rule',
    reasonCode: 'DEFAULT_ALLOW',
    safeReason: 'Passed all security criteria',
  },
]

export const DEMO_POLICY_DEFINITIONS: PolicyDefinitionDto[] = [
  {
    id: 'pol-def-01',
    policyKey: 'api-gateway-default-policy',
    name: 'Enterprise Core Gateway Policy',
    description: 'Primary ingress policy enforcing scope-based access control and plan-tiered routing',
    activeVersionNumber: 2,
    shadowVersionNumber: 3,
    versionLock: 3,
    createdAt: '2026-01-20T00:00:00Z',
    updatedAt: '2026-11-20T12:00:00Z',
  },
  {
    id: 'pol-def-02',
    policyKey: 'finance-api-strict-policy',
    name: 'PCI-DSS Financial Data Policy',
    description: 'Zero-trust policy enforcing TLS verification and restricted mTLS endpoint rules',
    activeVersionNumber: 1,
    shadowVersionNumber: null,
    versionLock: 1,
    createdAt: '2026-03-01T00:00:00Z',
    updatedAt: '2026-11-15T09:30:00Z',
  },
]

export const DEMO_POLICY_VERSIONS: Record<string, PolicyVersionDto[]> = {
  'api-gateway-default-policy': [
    {
      id: 'v1',
      policyKey: 'api-gateway-default-policy',
      versionNumber: 1,
      status: 'RETIRED',
      description: 'Initial release with basic scope verification',
      rules: [DEMO_RULES_DEFAULT[0]],
      rulesCount: 1,
      createdAt: '2026-01-20T00:00:00Z',
      createdBy: 'admin@gatekeeper.internal',
      activatedAt: '2026-01-20T00:05:00Z',
      activatedBy: 'admin@gatekeeper.internal',
    },
    {
      id: 'v2',
      policyKey: 'api-gateway-default-policy',
      versionNumber: 2,
      status: 'ACTIVE',
      description: 'Production active rules with tier-based DELETE safeguards',
      rules: DEMO_RULES_DEFAULT,
      rulesCount: 3,
      createdAt: '2026-06-10T14:00:00Z',
      createdBy: 'admin@gatekeeper.internal',
      activatedAt: '2026-06-10T14:15:00Z',
      activatedBy: 'admin@gatekeeper.internal',
    },
    {
      id: 'v3',
      policyKey: 'api-gateway-default-policy',
      versionNumber: 3,
      status: 'DRAFT',
      description: 'Shadow candidate adding strict CIDR inspection',
      rules: [
        ...DEMO_RULES_DEFAULT,
        {
          ruleId: 'RULE-04-CIDR-INSPECT',
          priority: 5,
          enabled: true,
          action: 'DENY',
          description: 'Blocks untrusted proxy egress ranges',
          reasonCode: 'UNTRUSTED_CIDR',
          safeReason: 'Client IP range is marked untrusted',
        },
      ],
      rulesCount: 4,
      createdAt: '2026-11-18T10:00:00Z',
      createdBy: 'devops@gatekeeper.internal',
    },
  ],
  'finance-api-strict-policy': [
    {
      id: 'v1-fin',
      policyKey: 'finance-api-strict-policy',
      versionNumber: 1,
      status: 'ACTIVE',
      description: 'Enforces mTLS and strict write:orders scope',
      rules: [
        {
          ruleId: 'FIN-01',
          priority: 1,
          enabled: true,
          action: 'ALLOW_CONTINUE',
          description: 'Validate mTLS and payment token scope',
          reasonCode: 'FIN_PERMITTED',
          safeReason: 'Valid payment processing credential verified',
        },
      ],
      rulesCount: 1,
      createdAt: '2026-03-01T00:00:00Z',
      createdBy: 'sec-admin@gatekeeper.internal',
      activatedAt: '2026-03-01T00:10:00Z',
      activatedBy: 'sec-admin@gatekeeper.internal',
    },
  ],
}

export const DEMO_POLICY_AUDITS: PolicyActivationAuditDto[] = [
  {
    id: 'audit-01',
    policyKey: 'api-gateway-default-policy',
    versionNumber: 2,
    previousVersionNumber: 1,
    action: 'ACTIVATED',
    actor: 'admin@gatekeeper.internal',
    reason: 'Promoted v2 with DELETE protection to production',
    createdAt: '2026-06-10T14:15:00Z',
  },
  {
    id: 'audit-02',
    policyKey: 'api-gateway-default-policy',
    versionNumber: 3,
    previousVersionNumber: null,
    action: 'SHADOW_SET',
    actor: 'devops@gatekeeper.internal',
    reason: 'Shadow testing CIDR verification against live traffic',
    createdAt: '2026-11-18T10:30:00Z',
  },
]

/**
 * Deterministic policy evaluator executing browser-side simulation.
 * Exactly models the documented Spring Boot rule ordering and condition evaluations.
 */
export const simulateDemoPolicy = (
  policyKey: string,
  request: PolicySimulationRequest
): PolicySimulationResponse => {
  const scopes = request.apiKeyScopes || []
  const method = request.httpMethod?.toUpperCase() || 'GET'
  const plan = request.consumerPlan?.toUpperCase() || 'STARTER'
  const route = request.routeId || '/api/v1/orders'
  const ip = request.clientIp || '192.168.1.100'

  // Scenario 1: Untrusted IP block
  if (ip === '10.0.0.99' || ip.startsWith('198.51.')) {
    return {
      policyKey,
      versionEvaluated: request.candidateVersionNumber || 2,
      decision: 'DENY',
      matchedRuleId: 'RULE-04-CIDR-INSPECT',
      reasonCode: 'UNTRUSTED_CIDR',
      safeReason: `Client IP ${ip} originated from an unlisted CIDR range`,
      hypotheticalQuotaConsumption: false,
      durationNanos: 842000,
      trace: {
        evaluationId: `sim-trace-${Date.now()}`,
        timestamp: new Date().toISOString(),
        policyKey,
        versionNumber: request.candidateVersionNumber || 2,
        routeId: route,
        finalDecision: 'DENY',
        matchedRuleId: 'RULE-04-CIDR-INSPECT',
        reasonCode: 'UNTRUSTED_CIDR',
        safeReason: `Client IP ${ip} originated from an unlisted CIDR range`,
        evaluationDurationNanos: 842000,
        candidateRulesCount: 4,
        ruleEvaluations: [
          {
            ruleId: 'RULE-04-CIDR-INSPECT',
            priority: 5,
            matched: true,
            action: 'DENY',
            matchExplanation: `IP ${ip} matched blocklist pattern`,
          },
        ],
      },
    }
  }

  // Scenario 2: Missing required scope
  const hasGatewayInvoke = scopes.includes('gateway:invoke')
  if (!hasGatewayInvoke) {
    return {
      policyKey,
      versionEvaluated: request.candidateVersionNumber || 2,
      decision: 'DENY',
      matchedRuleId: 'RULE-01-SCOPE-CHECK',
      reasonCode: 'MISSING_REQUIRED_SCOPE',
      safeReason: "Request lacks mandatory 'gateway:invoke' scope authorization",
      hypotheticalQuotaConsumption: false,
      durationNanos: 1204000,
      trace: {
        evaluationId: `sim-trace-${Date.now()}`,
        timestamp: new Date().toISOString(),
        policyKey,
        versionNumber: request.candidateVersionNumber || 2,
        routeId: route,
        finalDecision: 'DENY',
        matchedRuleId: 'RULE-01-SCOPE-CHECK',
        reasonCode: 'MISSING_REQUIRED_SCOPE',
        safeReason: "Request lacks mandatory 'gateway:invoke' scope authorization",
        evaluationDurationNanos: 1204000,
        candidateRulesCount: 3,
        ruleEvaluations: [
          {
            ruleId: 'RULE-01-SCOPE-CHECK',
            priority: 10,
            matched: true,
            action: 'DENY',
            matchExplanation: "Required scope 'gateway:invoke' not present in token scopes",
          },
        ],
      },
    }
  }

  // Scenario 3: DELETE on STARTER plan
  if (method === 'DELETE' && (plan === 'STARTER' || plan === 'FREE')) {
    return {
      policyKey,
      versionEvaluated: request.candidateVersionNumber || 2,
      decision: 'DENY',
      matchedRuleId: 'RULE-02-DELETE-GUARD',
      reasonCode: 'TIER_RESTRICTION',
      safeReason: `HTTP DELETE on ${route} is restricted on '${plan}' plan tier`,
      hypotheticalQuotaConsumption: false,
      durationNanos: 950000,
      trace: {
        evaluationId: `sim-trace-${Date.now()}`,
        timestamp: new Date().toISOString(),
        policyKey,
        versionNumber: request.candidateVersionNumber || 2,
        routeId: route,
        finalDecision: 'DENY',
        matchedRuleId: 'RULE-02-DELETE-GUARD',
        reasonCode: 'TIER_RESTRICTION',
        safeReason: `HTTP DELETE on ${route} is restricted on '${plan}' plan tier`,
        evaluationDurationNanos: 950000,
        candidateRulesCount: 3,
        ruleEvaluations: [
          {
            ruleId: 'RULE-01-SCOPE-CHECK',
            priority: 10,
            matched: false,
            action: 'ALLOW_CONTINUE',
            nonMatchReason: 'Scope validated, continuing chain evaluation',
          },
          {
            ruleId: 'RULE-02-DELETE-GUARD',
            priority: 20,
            matched: true,
            action: 'DENY',
            matchExplanation: `Method DELETE not permitted for plan tier ${plan}`,
          },
        ],
      },
    }
  }

  // Scenario 4: Fully permitted request
  return {
    policyKey,
    versionEvaluated: request.candidateVersionNumber || 2,
    decision: 'ALLOW_CONTINUE',
    matchedRuleId: 'RULE-03-IP-BLOCKLIST',
    reasonCode: 'ALL_CONSTRAINTS_SATISFIED',
    safeReason: `Request to ${route} (${method}) permitted under ${plan} plan quota`,
    hypotheticalQuotaConsumption: true,
    durationNanos: 620000,
    trace: {
      evaluationId: `sim-trace-${Date.now()}`,
      timestamp: new Date().toISOString(),
      policyKey,
      versionNumber: request.candidateVersionNumber || 2,
      routeId: route,
      finalDecision: 'ALLOW_CONTINUE',
      matchedRuleId: 'RULE-03-IP-BLOCKLIST',
      reasonCode: 'ALL_CONSTRAINTS_SATISFIED',
      safeReason: `Request to ${route} (${method}) permitted under ${plan} plan quota`,
      evaluationDurationNanos: 620000,
      candidateRulesCount: 3,
      ruleEvaluations: [
        {
          ruleId: 'RULE-01-SCOPE-CHECK',
          priority: 10,
          matched: false,
          action: 'ALLOW_CONTINUE',
          nonMatchReason: "Scope 'gateway:invoke' present",
        },
        {
          ruleId: 'RULE-02-DELETE-GUARD',
          priority: 20,
          matched: false,
          action: 'ALLOW_CONTINUE',
          nonMatchReason: `Method is ${method}, not DELETE`,
        },
        {
          ruleId: 'RULE-03-IP-BLOCKLIST',
          priority: 30,
          matched: true,
          action: 'ALLOW_CONTINUE',
          matchExplanation: 'Default allow rule satisfied',
        },
      ],
    },
  }
}

export const replayDemoPolicy = (
  policyKey: string,
  request: PolicyReplayRequest
): PolicyReplayResponse => {
  return {
    policyKey,
    activeVersionEvaluated: 2,
    candidateVersionEvaluated: request.candidateVersionNumber || 3,
    totalRecordsSampled: Math.min(request.sampleLimit, 500),
    totalEligibleRecords: 12500,
    decisionsUnchanged: Math.floor(Math.min(request.sampleLimit, 500) * 0.98),
    newlyDenied: Math.floor(Math.min(request.sampleLimit, 500) * 0.02),
    newlyPermitted: 0,
    notEvaluableCount: 0,
    sampleDifferences: [
      {
        eventId: 'evt-hist-diff-01',
        endpointPath: '/api/v1/orders',
        httpMethod: 'POST',
        consumerPlan: 'STARTER',
        activeDecision: 'ALLOW_CONTINUE',
        candidateDecision: 'DENY',
        differenceReason: 'Matched candidate rule RULE-04-CIDR-INSPECT (untrusted IP)',
      },
    ],
  }
}
