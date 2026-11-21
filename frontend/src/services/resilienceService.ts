import { apiClient } from '@/lib/apiClient'
import { RouteResilienceSummary } from '@/types/health'
import { RouteResilienceDetail } from '@/types/resilience'

export interface ResetCircuitBreakerResponse {
  routeId: string
  status: string
  circuitState: string
  resetBy: string
}

export const resilienceService = {
  /**
   * Fetches the resilience overview for all configured routes.
   * Real endpoint: GET /api/v1/resilience/routes
   */
  async getRouteResilience(signal?: AbortSignal): Promise<RouteResilienceSummary[]> {
    const response = await apiClient.get<RouteResilienceSummary[]>('/api/v1/resilience/routes', {
      signal,
      timeout: 5000,
    })
    return response.data
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
