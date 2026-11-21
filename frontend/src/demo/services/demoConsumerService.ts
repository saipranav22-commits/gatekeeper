import { PageableResponse } from '@/types/api'
import { ConsumerDto, CreateConsumerRequest } from '@/types/consumer'
import {
  changeDemoConsumerPlan,
  createDemoConsumer,
  deleteDemoConsumer,
  getDemoConsumerById,
  getDemoConsumers,
  reactivateDemoConsumer,
  suspendDemoConsumer,
} from '../data/demoConsumersData'

export const demoConsumerService = {
  async listConsumers(
    page: number = 0,
    size: number = 20,
    sortBy: string = 'createdAt',
    direction: 'asc' | 'desc' = 'desc'
  ): Promise<PageableResponse<ConsumerDto>> {
    const consumers = getDemoConsumers()

    // Sort
    consumers.sort((a, b) => {
      const aVal = (a as unknown as Record<string, string>)[sortBy] || ''
      const bVal = (b as unknown as Record<string, string>)[sortBy] || ''
      return direction === 'asc' ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal)
    })

    const totalElements = consumers.length
    const totalPages = Math.ceil(totalElements / size)
    const content = consumers.slice(page * size, (page + 1) * size)

    return {
      content,
      totalPages,
      totalElements,
      last: page >= totalPages - 1,
      size,
      number: page,
      numberOfElements: content.length,
      first: page === 0,
      empty: content.length === 0,
    }
  },

  async getConsumer(consumerId: string): Promise<ConsumerDto> {
    const consumer = getDemoConsumerById(consumerId)
    if (!consumer) {
      throw new Error(`Consumer with ID ${consumerId} not found`)
    }
    return consumer
  },

  async createConsumer(request: CreateConsumerRequest): Promise<ConsumerDto> {
    return createDemoConsumer(request)
  },

  async changePlan(consumerId: string, planId: string): Promise<ConsumerDto> {
    return changeDemoConsumerPlan(consumerId, planId)
  },

  async suspendConsumer(consumerId: string): Promise<ConsumerDto> {
    return suspendDemoConsumer(consumerId)
  },

  async reactivateConsumer(consumerId: string): Promise<ConsumerDto> {
    return reactivateDemoConsumer(consumerId)
  },

  async deleteConsumer(consumerId: string): Promise<void> {
    deleteDemoConsumer(consumerId)
  },
}
