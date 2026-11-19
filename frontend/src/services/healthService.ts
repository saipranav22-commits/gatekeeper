import { apiClient } from '@/lib/apiClient'
import { HealthResponse } from '@/types/health'

export const healthService = {
  async getHealth(signal?: AbortSignal): Promise<HealthResponse> {
    const response = await apiClient.get<HealthResponse>('/api/v1/health', {
      signal,
      timeout: 5000,
    })
    return response.data
  },
}
