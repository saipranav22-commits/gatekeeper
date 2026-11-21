import { apiClient } from '@/lib/apiClient'
import { SubscriptionPlanDto } from '@/types/plan'
import { isDemoMode } from '@/config/appMode'
import { demoPlanService } from '@/demo/services/demoPlanService'

export const planService = {
  async listPlans(): Promise<SubscriptionPlanDto[]> {
    if (isDemoMode()) {
      return demoPlanService.listPlans()
    }
    const response = await apiClient.get<SubscriptionPlanDto[]>('/api/v1/plans')
    return response.data
  },

  async getPlan(planId: string): Promise<SubscriptionPlanDto> {
    if (isDemoMode()) {
      return demoPlanService.getPlan(planId)
    }
    const response = await apiClient.get<SubscriptionPlanDto>(`/api/v1/plans/${planId}`)
    return response.data
  },
}
