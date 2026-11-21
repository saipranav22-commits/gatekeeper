import { PageableResponse } from '@/types/api'
import {
  AnalyticsOverviewDto,
  ConsumerAnalyticsDto,
  TimeSeriesPointDto,
  TopConsumerDto,
  UsageEventDetailDto,
} from '@/types/analytics'
import {
  DEMO_CONSUMER_ANALYTICS,
  DEMO_TOP_CONSUMERS,
  DEMO_USAGE_EVENTS,
  generateDemoTimeSeries,
  getDemoAnalyticsOverview,
} from '../data/demoAnalyticsData'
import { getDemoHealthScenario } from '../data/demoResilienceData'

export const demoAnalyticsService = {
  async getOverview(_start?: string, _end?: string): Promise<AnalyticsOverviewDto> {
    return getDemoAnalyticsOverview(getDemoHealthScenario())
  },

  async getTimeSeries(
    _start?: string,
    _end?: string,
    granularity: 'hourly' | 'daily' = 'hourly'
  ): Promise<TimeSeriesPointDto[]> {
    return generateDemoTimeSeries(granularity)
  },

  async getConsumerAnalytics(
    consumerId: string,
    _start?: string,
    _end?: string
  ): Promise<ConsumerAnalyticsDto> {
    const data = DEMO_CONSUMER_ANALYTICS[consumerId]
    if (data) return data

    return {
      consumerId,
      consumerName: 'Demo Consumer',
      consumerEmail: `${consumerId}@demo.example`,
      currentPlan: 'Business Standard',
      totalRequests: 12050,
      allowedRequests: 11980,
      rateLimitedRequests: 70,
      scopeDeniedRequests: 0,
      firstSeen: '2026-05-01T00:00:00Z',
      lastSeen: new Date().toISOString(),
      requestsByEndpoint: {
        '/api/v1/orders': 9500,
        '/api/v1/inventory': 2550,
      },
      requestsByKeyPrefix: {
        'gk_demo_key': 12050,
      },
    }
  },

  async getConsumerUsageEvents(
    _consumerId: string,
    _start?: string,
    _end?: string,
    page: number = 0,
    size: number = 20
  ): Promise<PageableResponse<UsageEventDetailDto>> {
    const totalElements = DEMO_USAGE_EVENTS.length
    const totalPages = Math.ceil(totalElements / size)
    const content = DEMO_USAGE_EVENTS.slice(page * size, (page + 1) * size)

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

  async getTopConsumers(
    _start?: string,
    _end?: string,
    limit: number = 10
  ): Promise<TopConsumerDto[]> {
    return DEMO_TOP_CONSUMERS.slice(0, limit)
  },
}
