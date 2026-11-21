import { apiClient } from '@/lib/apiClient'
import { HealthResponse } from '@/types/health'
import { isDemoMode } from '@/config/appMode'
import { demoHealthService } from '@/demo/services/demoHealthService'

export interface ActuatorHealthComponent {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN' | string
  details?: Record<string, unknown>
}

export interface ActuatorHealthResponse {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN' | string
  components?: {
    db?: ActuatorHealthComponent
    redis?: ActuatorHealthComponent
    diskSpace?: ActuatorHealthComponent
    ping?: ActuatorHealthComponent
    [key: string]: unknown
  }
}

export const healthService = {
  /**
   * Fetches public gateway application health.
   * Real endpoint: GET /api/v1/health
   */
  async getHealth(signal?: AbortSignal): Promise<HealthResponse> {
    if (isDemoMode()) {
      return demoHealthService.getHealth()
    }
    const response = await apiClient.get<HealthResponse>('/api/v1/health', {
      signal,
      timeout: 5000,
    })
    return response.data
  },

  /**
   * Fetches authenticated Actuator component health (DB, Redis, Disk).
   * Real endpoint: GET /actuator/health
   * Note: Shows deep component details when authorized.
   */
  async getActuatorHealth(signal?: AbortSignal): Promise<ActuatorHealthResponse> {
    if (isDemoMode()) {
      return demoHealthService.getActuatorHealth()
    }
    const response = await apiClient.get<ActuatorHealthResponse>('/actuator/health', {
      signal,
      timeout: 5000,
    })
    return response.data
  },
}
