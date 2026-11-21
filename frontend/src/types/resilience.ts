export type CircuitBreakerState = 'CLOSED' | 'OPEN' | 'HALF_OPEN' | 'DISABLED' | 'FORCED_OPEN'

export interface RouteCircuitBreakerDetail {
  state: CircuitBreakerState
  failureRateThreshold: number
  slowCallRateThreshold: number
  slowCallDurationMs: number
  slidingWindowSize: number
  minimumNumberOfCalls: number
  waitDurationInOpenStateMs: number
  permittedNumberOfCallsInHalfOpenState: number
  totalCalls: number
  successfulCalls: number
  failedCalls: number
  slowCalls: number
  failureRatePercent: number
  slowCallRatePercent: number
  totalRejectedCalls: number
}

export interface RouteBulkheadDetail {
  maxConcurrentCalls: number
  availablePermits: number
  inFlightCalls: number
  rejectedCalls: number
}

export interface RouteRetryDetail {
  maxAttempts: number
  initialBackoffMs: number
  backoffMultiplier: number
  jitterFactor: number
  maxCumulativeDeadlineMs: number
}

export interface RouteResilienceDetail {
  routeId: string
  circuitBreaker: RouteCircuitBreakerDetail
  bulkhead: RouteBulkheadDetail
  retry: RouteRetryDetail
}

export type DependencyType = 'GATEWAY' | 'UPSTREAM_HTTP' | 'DATABASE' | 'CACHE' | 'CLIENT'

export type ServiceHealthStatus = 'HEALTHY' | 'DEGRADED' | 'DOWN' | 'UNKNOWN' | 'RECOVERING'

export interface ServiceTopologyNode {
  id: string
  label: string
  serviceId: string
  type: DependencyType
  role: string
  endpointOrHost: string
  status: ServiceHealthStatus
  statusReason?: string
  lastObserved?: string
  circuitBreakerState?: CircuitBreakerState
  failureRate?: number
  inFlightCalls?: number
  maxConcurrency?: number
  metrics?: {
    totalRequests?: number
    p95LatencyMs?: number
    errorRatePercent?: number
  }
}

export interface ServiceTopologyLink {
  source: string
  target: string
  label?: string
  protocol: 'HTTP' | 'JDBC' | 'REDIS' | 'HTTPS'
  circuitState?: CircuitBreakerState
  isEncrypted: boolean
  isRateLimited: boolean
}

export interface ConfiguredTopologyData {
  nodes: ServiceTopologyNode[]
  links: ServiceTopologyLink[]
  generatedAt: string
  isDiscovered: boolean // Always false: explicitly labeled configured
}

export type OperationalEventSeverity = 'INFO' | 'WARNING' | 'CRITICAL' | 'RECOVERY'

export interface OperationalTimelineEvent {
  id: string
  timestamp: string
  category: 'CIRCUIT_BREAKER' | 'BULKHEAD' | 'RETRY' | 'POLICY_DENIAL' | 'UPSTREAM_ERROR' | 'CHAOS_EXPERIMENT'
  source: string
  routeId?: string
  consumerId?: string
  severity: OperationalEventSeverity
  derivedSeverityRule: string
  headline: string
  details: string
  correlationId?: string
  rawStatus?: number
}
