import { SubscriptionPlanDto } from '@/types/plan'

export const DEMO_PLANS: SubscriptionPlanDto[] = [
  {
    id: 'plan-enterprise-platinum',
    name: 'Enterprise Platinum',
    description: 'High-throughput dedicated plan with 10,000 burst capacity and 1,000 req/sec sustained rate.',
    bucketCapacity: 10000,
    refillTokens: 1000,
    refillPeriodSeconds: 1,
    isActive: true,
    createdAt: '2026-01-15T00:00:00Z',
    updatedAt: '2026-01-15T00:00:00Z',
  },
  {
    id: 'plan-business-standard',
    name: 'Business Standard',
    description: 'Production business tier with 2,500 burst capacity and 250 req/sec replenishment.',
    bucketCapacity: 2500,
    refillTokens: 250,
    refillPeriodSeconds: 1,
    isActive: true,
    createdAt: '2026-01-15T00:00:00Z',
    updatedAt: '2026-01-15T00:00:00Z',
  },
  {
    id: 'plan-starter-free',
    name: 'Developer Starter',
    description: 'Evaluation tier with 100 burst tokens and 10 req/sec token bucket refill rate.',
    bucketCapacity: 100,
    refillTokens: 10,
    refillPeriodSeconds: 1,
    isActive: true,
    createdAt: '2026-01-15T00:00:00Z',
    updatedAt: '2026-01-15T00:00:00Z',
  },
]
