import { apiClient } from '@/lib/apiClient'
import { RouteResilienceSummary } from '@/types/health'

export const resilienceService = {
  async getRouteResilience(signal?: AbortSignal): Promise<RouteResilienceSummary[]> {
    const response = await apiClient.get<RouteResilienceSummary[]>('/api/v1/resilience/routes', {
      signal,
      timeout: 5000,
    })
    return response.data
  },
}
