import { SubscriptionPlanDto } from './plan'

export type ConsumerStatus = 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED'

export interface ConsumerDto {
  id: string
  name: string
  email: string
  status: ConsumerStatus
  plan: SubscriptionPlanDto | null
  isDeleted: boolean
  deletedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateConsumerRequest {
  name: string
  email: string
  planId: string
}

export interface ChangePlanRequest {
  planId: string
}
