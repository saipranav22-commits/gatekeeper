export type HealthStatus = 'HEALTHY' | 'DEGRADED' | 'OFFLINE' | 'CHECKING' | 'STALE'

export interface HealthResponse {
  status: string
  service: string
  serviceName: string
  timestamp: string
}

export interface RouteResilienceSummary {
  routeId: string
  circuitBreakerState: 'CLOSED' | 'OPEN' | 'HALF_OPEN' | string
  failureRate: number
  slowCallRate: number
  totalCalls: number
  failedCalls: number
  slowCalls: number
  rejectedCalls: number
  bulkheadMaxConcurrent: number
  bulkheadAvailablePermits: number
  bulkheadInFlightCalls: number
  bulkheadRejectedCalls: number
  retryMaxAttempts: number
}
