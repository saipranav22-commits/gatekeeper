export type PolicyAction = 'ALLOW_CONTINUE' | 'DENY'

export interface PolicyRuleConditions {
  routeId?: string | null
  httpMethod?: string | null
  consumerPlan?: string | null
  requiredScopes?: string[] | null
  excludedScopes?: string[] | null
  timeWindowStartUtc?: string | null
  timeWindowEndUtc?: string | null
  daysOfWeek?: string[] | null
}

export interface PolicyRule {
  ruleId: string
  priority: number
  enabled: boolean
  conditions?: PolicyRuleConditions | null
  action: PolicyAction
  description?: string | null
  reasonCode?: string | null
  safeReason?: string | null
}

export interface PolicyDefinitionDto {
  id: string
  policyKey: string
  name: string
  description?: string | null
  activeVersionNumber?: number | null
  shadowVersionNumber?: number | null
  versionLock: number
  createdAt: string
  updatedAt: string
}

export type PolicyVersionStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED' | 'ROLLED_BACK'

export interface PolicyVersionDto {
  id: string
  policyKey: string
  versionNumber: number
  status: PolicyVersionStatus
  description?: string | null
  rules: PolicyRule[]
  rulesCount: number
  createdAt: string
  createdBy: string
  activatedAt?: string | null
  activatedBy?: string | null
}

export type PolicyAuditActionType =
  | 'ACTIVATED'
  | 'ROLLED_BACK'
  | 'DEACTIVATED'
  | 'SHADOW_SET'
  | 'SHADOW_CLEARED'

export interface PolicyActivationAuditDto {
  id: string
  policyKey: string
  versionNumber: number
  previousVersionNumber?: number | null
  action: PolicyAuditActionType
  actor: string
  reason?: string | null
  createdAt: string
}

export interface RuleEvaluationTrace {
  ruleId: string
  priority: number
  matched: boolean
  action: PolicyAction
  matchExplanation?: string | null
  nonMatchReason?: string | null
}

export interface PolicyDecisionTrace {
  evaluationId: string
  timestamp: string
  policyKey: string
  versionNumber?: number | null
  routeId?: string | null
  finalDecision: PolicyAction
  matchedRuleId?: string | null
  reasonCode?: string | null
  safeReason?: string | null
  evaluationDurationNanos: number
  candidateRulesCount: number
  ruleEvaluations: RuleEvaluationTrace[]
}

export interface PolicySimulationRequest {
  candidateVersionNumber?: number
  candidateRules?: PolicyRule[]
  routeId?: string
  httpMethod?: string
  consumerPlan?: string
  apiKeyScopes?: string[]
  clientIp?: string
  requestTimeUtc?: string
}

export interface PolicySimulationResponse {
  policyKey: string
  versionEvaluated: number
  decision: PolicyAction
  matchedRuleId?: string | null
  reasonCode?: string | null
  safeReason?: string | null
  hypotheticalQuotaConsumption: boolean
  durationNanos: number
  trace?: PolicyDecisionTrace | null
}

export interface PolicyReplayRequest {
  candidateVersionNumber?: number
  candidateRules?: PolicyRule[]
  sampleLimit: number
  sinceHours: number
}

export interface ReplayDifferenceDetail {
  eventId: string
  endpointPath: string
  httpMethod: string
  consumerPlan: string
  activeDecision: string
  candidateDecision: string
  differenceReason: string
}

export interface PolicyReplayResponse {
  policyKey: string
  activeVersionEvaluated?: number | null
  candidateVersionEvaluated?: number | null
  totalRecordsSampled: number
  totalEligibleRecords: number
  decisionsUnchanged: number
  newlyDenied: number
  newlyPermitted: number
  notEvaluableCount: number
  sampleDifferences: ReplayDifferenceDetail[]
}

export interface CreatePolicyDefinitionRequest {
  policyKey: string
  name: string
  description?: string
}

export interface CreatePolicyVersionRequest {
  description?: string
  rules: PolicyRule[]
}

export interface ConfigureShadowPolicyRequest {
  shadowVersionNumber?: number | null
}
