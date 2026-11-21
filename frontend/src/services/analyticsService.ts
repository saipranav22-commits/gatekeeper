import { apiClient } from '@/lib/apiClient'
import { PageableResponse } from '@/types/api'
import {
  AnalyticsOverviewDto,
  ConsumerAnalyticsDto,
  TimeSeriesPointDto,
  TopConsumerDto,
  UsageEventDetailDto,
} from '@/types/analytics'
import { isDemoMode } from '@/config/appMode'
import { demoAnalyticsService } from '@/demo/services/demoAnalyticsService'

export const analyticsService = {
  async getOverview(start?: string, end?: string): Promise<AnalyticsOverviewDto> {
    if (isDemoMode()) {
      return demoAnalyticsService.getOverview(start, end)
    }
    const params: Record<string, string> = {}
    if (start) params.start = start
    if (end) params.end = end
    const response = await apiClient.get<AnalyticsOverviewDto>('/api/v1/analytics/overview', { params })
    return response.data
  },

  async getTimeSeries(
    start?: string,
    end?: string,
    granularity: 'hourly' | 'daily' = 'hourly'
  ): Promise<TimeSeriesPointDto[]> {
    if (isDemoMode()) {
      return demoAnalyticsService.getTimeSeries(start, end, granularity)
    }
    const params: Record<string, string> = { granularity }
    if (start) params.start = start
    if (end) params.end = end
    const response = await apiClient.get<TimeSeriesPointDto[]>('/api/v1/analytics/timeseries', { params })
    return response.data
  },

  async getConsumerAnalytics(consumerId: string, start?: string, end?: string): Promise<ConsumerAnalyticsDto> {
    if (isDemoMode()) {
      return demoAnalyticsService.getConsumerAnalytics(consumerId, start, end)
    }
    const params: Record<string, string> = {}
    if (start) params.start = start
    if (end) params.end = end
    const response = await apiClient.get<ConsumerAnalyticsDto>(`/api/v1/analytics/consumers/${consumerId}`, { params })
    return response.data
  },

  async getConsumerUsageEvents(
    consumerId: string,
    start?: string,
    end?: string,
    page: number = 0,
    size: number = 20
  ): Promise<PageableResponse<UsageEventDetailDto>> {
    if (isDemoMode()) {
      return demoAnalyticsService.getConsumerUsageEvents(consumerId, start, end, page, size)
    }
    const params: Record<string, string | number> = { page, size }
    if (start) params.start = start
    if (end) params.end = end
    const response = await apiClient.get<PageableResponse<UsageEventDetailDto>>(
      `/api/v1/analytics/consumers/${consumerId}/usage`,
      { params }
    )
    return response.data
  },

  async getTopConsumers(start?: string, end?: string, limit: number = 10): Promise<TopConsumerDto[]> {
    if (isDemoMode()) {
      return demoAnalyticsService.getTopConsumers(start, end, limit)
    }
    const params: Record<string, string | number> = { limit }
    if (start) params.start = start
    if (end) params.end = end
    const response = await apiClient.get<TopConsumerDto[]>('/api/v1/analytics/top-consumers', { params })
    return response.data
  },
}
