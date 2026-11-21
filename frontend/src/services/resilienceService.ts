import { apiClient } from '@/lib/apiClient'
import { RouteResilienceSummary } from '@/types/health'
import { RouteResilienceDetail } from '@/types/resilience'

export interface ResetCircuitBreakerResponse {
  routeId: string
  status: string
  circuitState: string
  resetBy: string
}

/**
 * Normalizes live Spring Boot RouteResilienceSummaryDto payloads to the frontend model.
 * Bridges backend field names (circuitState, failureRatePercent, slowCallRatePercent, circuitRejectedCalls)
 * and guards against undefined/null/non-finite numeric values.
 */
export function normalizeResilienceSummary(raw: Record<string, unknown> | null | undefined): RouteResilienceSummary {
  const safeNumber = (val: unknown, fallback = 0): number => {
    if (typeof val === 'number' && Number.isFinite(val)) {
      return val
    }
    return fallback
  }

  const rawCircuitState = (raw?.circuitBreakerState ?? raw?.circuitState) as string | undefined
  const circuitBreakerState =
    typeof rawCircuitState === 'string' && rawCircuitState.trim().length > 0
      ? rawCircuitState.trim().toUpperCase()
      : 'UNKNOWN'

  return {
    routeId: String(raw?.routeId ?? ''),
    circuitBreakerState,
    failureRate: safeNumber(raw?.failureRate ?? raw?.failureRatePercent, 0),
    slowCallRate: safeNumber(raw?.slowCallRate ?? raw?.slowCallRatePercent, 0),
    totalCalls: safeNumber(raw?.totalCalls, 0),
    failedCalls: safeNumber(raw?.failedCalls, 0),
    slowCalls: safeNumber(raw?.slowCalls, 0),
    rejectedCalls: safeNumber(raw?.rejectedCalls ?? raw?.circuitRejectedCalls, 0),
    bulkheadMaxConcurrent: safeNumber(raw?.bulkheadMaxConcurrent, 0),
    bulkheadAvailablePermits: safeNumber(raw?.bulkheadAvailablePermits, 0),
    bulkheadInFlightCalls: safeNumber(raw?.bulkheadInFlightCalls, 0),
    bulkheadRejectedCalls: safeNumber(raw?.bulkheadRejectedCalls, 0),
    retryMaxAttempts: safeNumber(raw?.retryMaxAttempts, 0),
  }
}

export const resilienceService = {
  /**
   * Fetches the resilience overview for all configured routes.
   * Real endpoint: GET /api/v1/resilience/routes
   */
  async getRouteResilience(signal?: AbortSignal): Promise<RouteResilienceSummary[]> {
    const response = await apiClient.get<Record<string, unknown>[]>('/api/v1/resilience/routes', {
      signal,
      timeout: 5000,
    })
    return Array.isArray(response.data) ? response.data.map(normalizeResilienceSummary) : []
  },

  /**
   * Fetches detailed metrics and configuration for a specific route.
   * Real endpoint: GET /api/v1/resilience/routes/{routeId}
   */
  async getRouteResilienceDetail(routeId: string, signal?: AbortSignal): Promise<RouteResilienceDetail> {
    const response = await apiClient.get<RouteResilienceDetail>(`/api/v1/resilience/routes/${encodeURIComponent(routeId)}`, {
      signal,
      timeout: 5000,
    })
    return response.data
  },

  /**
   * Resets the circuit breaker on a route back to CLOSED state and clears sliding window.
   * Real endpoint: POST /api/v1/resilience/routes/{routeId}/reset
   * Gated: Requires SUPER_ADMIN or ADMIN role.
   */
  async resetRouteCircuitBreaker(routeId: string): Promise<ResetCircuitBreakerResponse> {
    const response = await apiClient.post<ResetCircuitBreakerResponse>(
      `/api/v1/resilience/routes/${encodeURIComponent(routeId)}/reset`
    )
    return response.data
  },
}
