import { SubscriptionPlanDto } from '@/types/plan'
import { DEMO_PLANS } from '../data/demoPlansData'

export const demoPlanService = {
  async listPlans(): Promise<SubscriptionPlanDto[]> {
    return DEMO_PLANS
  },

  async getPlan(planId: string): Promise<SubscriptionPlanDto> {
    const plan = DEMO_PLANS.find((p) => p.id === planId)
    if (!plan) throw new Error(`Plan with ID ${planId} not found`)
    return plan
  },
}
