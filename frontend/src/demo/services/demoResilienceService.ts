import { RouteResilienceSummary } from '@/types/health'
import { RouteResilienceDetail } from '@/types/resilience'
import { ResetCircuitBreakerResponse } from '@/services/resilienceService'
import {
  getDemoRouteResilience,
  getDemoRouteResilienceDetail,
  resetDemoCircuitBreaker,
} from '../data/demoResilienceData'

export const demoResilienceService = {
  async getRouteResilience(): Promise<RouteResilienceSummary[]> {
    return getDemoRouteResilience()
  },

  async getRouteResilienceDetail(routeId: string): Promise<RouteResilienceDetail> {
    return getDemoRouteResilienceDetail(routeId)
  },

  async resetCircuitBreaker(routeId: string): Promise<ResetCircuitBreakerResponse> {
    return resetDemoCircuitBreaker(routeId)
  },
}
