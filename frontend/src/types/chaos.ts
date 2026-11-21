export type ChaosScenarioType =
  | 'ORDERS_OUTAGE'
  | 'SLOW_UPSTREAM'
  | 'BULKHEAD_SATURATION'
  | 'REDIS_FAILURE'
  | 'POLICY_SAFETY'
  | 'RETRY_STORM'

export type ChaosTarget =
  | 'ORDERS_SERVICE'
  | 'INVENTORY_SERVICE'
  | 'REDIS_DEPENDENCY'
  | 'DATABASE_DEPENDENCY'
  | 'GATEWAY_INTERNAL'
  | 'POLICY_ENGINE'

export type FaultType =
  | 'HTTP_ERROR'
  | 'LATENCY_INJECTION'
  | 'CONCURRENCY_BURST'
  | 'CONNECTION_DROP'
  | 'PACKET_LOSS'
  | 'POLICY_CORRUPTION'
  | 'STOCHASTIC_FAILURES'

export type ExperimentState =
  | 'CREATED'
  | 'SCHEDULED'
  | 'RUNNING'
  | 'FAULT_INJECTED'
  | 'EVALUATING'
  | 'RECOVERING'
  | 'COMPLETED'
  | 'FAILED'
  | 'ROLLED_BACK'

export type AssertionOutcome = 'PASS' | 'FAIL' | 'SKIPPED' | 'INCONCLUSIVE'

export interface ExperimentAssertion {
  name: string
  description: string
  outcome: AssertionOutcome
  observedDetails: string
}

export interface ReliabilityMetrics {
  experimentId: string
  scenarioType: ChaosScenarioType
  targetService: string
  startTime: string
  endTime: string
  durationMs: number
  injectedFaultType: string
  totalRequests: number
  successfulRequests: number
  failedRequests: number
  statusDistribution: Record<string, number>
  errorClassification: Record<string, number>
  gatewayLatencyP50Ms: number
  gatewayLatencyP95Ms: number
  gatewayLatencyP99Ms: number
  upstreamLatencyP50Ms: number
  upstreamLatencyP95Ms: number
  upstreamLatencyP99Ms: number
  circuitBreakerTransitions: string[]
  bulkheadRejectionCount: number
  retryAttemptCount: number
  peakConcurrency: number
  recoveryDurationMs: number
  healthyServiceAvailability: number
  cleanupStatus: string
  assertions?: ExperimentAssertion[]
}

export type ExperimentProvenance = 'LIVE_LOCAL_EXPERIMENT' | 'HISTORICAL_TEST_ARTIFACT'

export interface ChaosExperiment {
  id: string
  scenarioType: ChaosScenarioType
  target: ChaosTarget | string
  faultType: FaultType | string
  parameters: Record<string, unknown>
  state: ExperimentState
  createdAt: string
  startedAt?: string
  completedAt?: string
  failureReason?: string | null
  cleanupStatus: string
  provenance: ExperimentProvenance
  assertions: ExperimentAssertion[]
  metrics?: ReliabilityMetrics
}

export interface ChaosRunRequest {
  scenario: ChaosScenarioType
  parameters?: Record<string, unknown>
}

export interface ScenarioDefinition {
  type: ChaosScenarioType
  title: string
  subtitle: string
  description: string
  target: string
  faultType: string
  resilienceMechanism: string
  expectedBehavior: string
  safeguards: string[]
}
