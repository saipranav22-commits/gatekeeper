import { apiClient } from '@/lib/apiClient'
import { PageableResponse } from '@/types/api'
import { ChangePlanRequest, ConsumerDto, CreateConsumerRequest } from '@/types/consumer'

export const consumerService = {
  async listConsumers(
    page: number = 0,
    size: number = 20,
    sortBy: string = 'createdAt',
    direction: 'asc' | 'desc' = 'desc'
  ): Promise<PageableResponse<ConsumerDto>> {
    const response = await apiClient.get<PageableResponse<ConsumerDto>>('/api/v1/consumers', {
      params: { page, size, sortBy, direction },
    })
    return response.data
  },

  async getConsumer(consumerId: string): Promise<ConsumerDto> {
    const response = await apiClient.get<ConsumerDto>(`/api/v1/consumers/${consumerId}`)
    return response.data
  },

  async createConsumer(request: CreateConsumerRequest): Promise<ConsumerDto> {
    const response = await apiClient.post<ConsumerDto>('/api/v1/consumers', request)
    return response.data
  },

  async changePlan(consumerId: string, planId: string): Promise<ConsumerDto> {
    const payload: ChangePlanRequest = { planId }
    const response = await apiClient.patch<ConsumerDto>(`/api/v1/consumers/${consumerId}/plan`, payload)
    return response.data
  },

  async suspendConsumer(consumerId: string): Promise<ConsumerDto> {
    const response = await apiClient.patch<ConsumerDto>(`/api/v1/consumers/${consumerId}/suspend`)
    return response.data
  },

  async reactivateConsumer(consumerId: string): Promise<ConsumerDto> {
    const response = await apiClient.patch<ConsumerDto>(`/api/v1/consumers/${consumerId}/reactivate`)
    return response.data
  },

  async deleteConsumer(consumerId: string): Promise<void> {
    await apiClient.delete(`/api/v1/consumers/${consumerId}`)
  },
}
