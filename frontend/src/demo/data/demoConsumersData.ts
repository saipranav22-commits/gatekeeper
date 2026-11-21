import { ConsumerDto, CreateConsumerRequest } from '@/types/consumer'
import { DEMO_PLANS } from './demoPlansData'

const INITIAL_DEMO_CONSUMERS: ConsumerDto[] = [
  {
    id: 'c-acme-fintech',
    name: 'Acme Payments Corp',
    email: 'api-ops@acme-fintech.example',
    status: 'ACTIVE',
    plan: DEMO_PLANS[0], // Enterprise Platinum
    isDeleted: false,
    deletedAt: null,
    createdAt: '2026-02-01T10:00:00Z',
    updatedAt: '2026-11-20T14:30:00Z',
  },
  {
    id: 'c-global-logistics',
    name: 'Global Freight Dynamics',
    email: 'integration@globallogistics.example',
    status: 'ACTIVE',
    plan: DEMO_PLANS[1], // Business Standard
    isDeleted: false,
    deletedAt: null,
    createdAt: '2026-03-10T08:15:00Z',
    updatedAt: '2026-11-19T11:20:00Z',
  },
  {
    id: 'c-nova-health',
    name: 'Nova Health Telemetry',
    email: 'devops@novahealth.example',
    status: 'ACTIVE',
    plan: DEMO_PLANS[0], // Enterprise Platinum
    isDeleted: false,
    deletedAt: null,
    createdAt: '2026-04-05T12:00:00Z',
    updatedAt: '2026-11-21T09:45:00Z',
  },
  {
    id: 'c-retail-pulse',
    name: 'RetailPulse POS Network',
    email: 'gateway@retailpulse.example',
    status: 'SUSPENDED',
    plan: DEMO_PLANS[1], // Business Standard
    isDeleted: false,
    deletedAt: null,
    createdAt: '2026-05-18T16:20:00Z',
    updatedAt: '2026-11-18T18:00:00Z',
  },
  {
    id: 'c-quantum-ai',
    name: 'Quantum Intelligence Labs',
    email: 'systems@quantumai.example',
    status: 'ACTIVE',
    plan: DEMO_PLANS[2], // Starter Free
    isDeleted: false,
    deletedAt: null,
    createdAt: '2026-08-22T09:00:00Z',
    updatedAt: '2026-11-20T21:10:00Z',
  },
]

// Session-local memory store for interactive UI testing
let sessionConsumers: ConsumerDto[] = [...INITIAL_DEMO_CONSUMERS]

export const getDemoConsumers = (): ConsumerDto[] => {
  return [...sessionConsumers].filter((c) => !c.isDeleted)
}

export const getDemoConsumerById = (id: string): ConsumerDto | undefined => {
  return sessionConsumers.find((c) => c.id === id && !c.isDeleted)
}

export const createDemoConsumer = (request: CreateConsumerRequest): ConsumerDto => {
  const plan = DEMO_PLANS.find((p) => p.id === request.planId) || DEMO_PLANS[2]
  const newConsumer: ConsumerDto = {
    id: `c-demo-${Date.now().toString(36)}`,
    name: request.name,
    email: request.email,
    status: 'ACTIVE',
    plan,
    isDeleted: false,
    deletedAt: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }
  sessionConsumers = [newConsumer, ...sessionConsumers]
  return newConsumer
}

export const changeDemoConsumerPlan = (consumerId: string, planId: string): ConsumerDto => {
  const target = getDemoConsumerById(consumerId)
  if (!target) throw new Error(`Consumer ${consumerId} not found`)
  const newPlan = DEMO_PLANS.find((p) => p.id === planId) || DEMO_PLANS[0]
  target.plan = newPlan
  target.updatedAt = new Date().toISOString()
  return { ...target }
}

export const suspendDemoConsumer = (consumerId: string): ConsumerDto => {
  const target = getDemoConsumerById(consumerId)
  if (!target) throw new Error(`Consumer ${consumerId} not found`)
  target.status = 'SUSPENDED'
  target.updatedAt = new Date().toISOString()
  return { ...target }
}

export const reactivateDemoConsumer = (consumerId: string): ConsumerDto => {
  const target = getDemoConsumerById(consumerId)
  if (!target) throw new Error(`Consumer ${consumerId} not found`)
  target.status = 'ACTIVE'
  target.updatedAt = new Date().toISOString()
  return { ...target }
}

export const deleteDemoConsumer = (consumerId: string): void => {
  const target = getDemoConsumerById(consumerId)
  if (target) {
    target.isDeleted = true
    target.deletedAt = new Date().toISOString()
  }
}

export const resetDemoConsumers = (): void => {
  sessionConsumers = [...INITIAL_DEMO_CONSUMERS]
}
