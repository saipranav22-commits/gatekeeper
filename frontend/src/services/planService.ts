import { apiClient } from '@/lib/apiClient'
import { SubscriptionPlanDto } from '@/types/plan'

export const planService = {
  async listPlans(): Promise<SubscriptionPlanDto[]> {
    const response = await apiClient.get<SubscriptionPlanDto[]>('/api/v1/plans')
    return response.data
  },

  async getPlan(planId: string): Promise<SubscriptionPlanDto> {
    const response = await apiClient.get<SubscriptionPlanDto>(`/api/v1/plans/${planId}`)
    return response.data
  },
}
