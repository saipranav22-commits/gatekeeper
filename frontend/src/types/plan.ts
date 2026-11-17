export interface SubscriptionPlanDto {
  id: string
  name: string
  description: string
  bucketCapacity: number
  refillTokens: number
  refillPeriodSeconds: number
  isActive: boolean
  createdAt: string
  updatedAt: string
}
