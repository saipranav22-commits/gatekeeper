import {
  AnalyticsOverviewDto,
  ConsumerAnalyticsDto,
  TimeSeriesPointDto,
  TopConsumerDto,
  UsageEventDetailDto,
} from '@/types/analytics'

export const DEMO_ANALYTICS_OVERVIEW: AnalyticsOverviewDto = {
  totalRequests: 1482920,
  allowedRequests: 1447812,
  rateLimitedRequests: 28140,
  scopeDeniedRequests: 3200,
  errorRequests: 3768,
  rateLimitDenialPercentage: 1.897,
  usageByPlan: {
    'Enterprise Platinum': 982400,
    'Business Standard': 420120,
    'Developer Starter': 80400,
  },
  windowStart: '2026-11-20T00:00:00Z',
  windowEnd: '2026-11-21T00:00:00Z',
}

export const DEMO_TOP_CONSUMERS: TopConsumerDto[] = [
  {
    consumerId: 'c-acme-fintech',
    consumerName: 'Acme Payments Corp',
    consumerEmail: 'api-ops@acme-fintech.example',
    plan: 'Enterprise Platinum',
    totalRequests: 684200,
    allowedRequests: 678900,
    rateLimitedRequests: 5300,
    rateLimitDenialPercentage: 0.77,
  },
  {
    consumerId: 'c-global-logistics',
    consumerName: 'Global Freight Dynamics',
    consumerEmail: 'integration@globallogistics.example',
    plan: 'Business Standard',
    totalRequests: 420100,
    allowedRequests: 408500,
    rateLimitedRequests: 11600,
    rateLimitDenialPercentage: 2.76,
  },
  {
    consumerId: 'c-nova-health',
    consumerName: 'Nova Health Telemetry',
    consumerEmail: 'devops@novahealth.example',
    plan: 'Enterprise Platinum',
    totalRequests: 298220,
    allowedRequests: 292100,
    rateLimitedRequests: 6120,
    rateLimitDenialPercentage: 2.05,
  },
  {
    consumerId: 'c-quantum-ai',
    consumerName: 'Quantum Intelligence Labs',
    consumerEmail: 'systems@quantumai.example',
    plan: 'Developer Starter',
    totalRequests: 80400,
    allowedRequests: 68312,
    rateLimitedRequests: 5120,
    rateLimitDenialPercentage: 6.36,
  },
]

export const generateDemoTimeSeries = (granularity: 'hourly' | 'daily' = 'hourly'): TimeSeriesPointDto[] => {
  const points: TimeSeriesPointDto[] = []
  const count = granularity === 'daily' ? 30 : 24
  const now = Date.now()

  for (let i = count - 1; i >= 0; i--) {
    const timeOffset = granularity === 'daily' ? i * 24 * 3600 * 1000 : i * 3600 * 1000
    const pointTime = new Date(now - timeOffset)
    const bucket = granularity === 'daily'
      ? pointTime.toISOString().split('T')[0]
      : `${pointTime.getHours().toString().padStart(2, '0')}:00`

    // Deterministic diurnal traffic curve with slight variance
    const baseHour = pointTime.getHours()
    const trafficMultiplier = 0.5 + 0.5 * Math.sin(((baseHour - 6) / 24) * 2 * Math.PI)
    const total = Math.floor((granularity === 'daily' ? 48000 : 2200) * (0.8 + 0.4 * trafficMultiplier))
    const rateLimited = Math.floor(total * 0.02)
    const scopeDenied = Math.floor(total * 0.003)
    const error = Math.floor(total * 0.002)
    const allowed = total - rateLimited - scopeDenied - error

    points.push({
      bucket,
      totalRequests: total,
      allowedRequests: allowed,
      rateLimitedRequests: rateLimited,
      scopeDeniedRequests: scopeDenied,
      errorRequests: error,
    })
  }

  return points
}

export const DEMO_CONSUMER_ANALYTICS: Record<string, ConsumerAnalyticsDto> = {
  'c-acme-fintech': {
    consumerId: 'c-acme-fintech',
    consumerName: 'Acme Payments Corp',
    consumerEmail: 'api-ops@acme-fintech.example',
    currentPlan: 'Enterprise Platinum',
    totalRequests: 684200,
    allowedRequests: 678900,
    rateLimitedRequests: 5300,
    scopeDeniedRequests: 0,
    firstSeen: '2026-02-01T10:00:00Z',
    lastSeen: '2026-11-21T18:42:15Z',
    requestsByEndpoint: {
      '/api/v1/orders': 512000,
      '/api/v1/orders/{id}': 142200,
      '/api/v1/analytics/stream': 30000,
    },
    requestsByKeyPrefix: {
      'gk_demo_acme_7f9a': 642000,
      'gk_demo_acme_14c3': 42200,
    },
  },
  'c-global-logistics': {
    consumerId: 'c-global-logistics',
    consumerName: 'Global Freight Dynamics',
    consumerEmail: 'integration@globallogistics.example',
    currentPlan: 'Business Standard',
    totalRequests: 420100,
    allowedRequests: 408500,
    rateLimitedRequests: 11600,
    scopeDeniedRequests: 0,
    firstSeen: '2026-03-10T08:15:00Z',
    lastSeen: '2026-11-21T17:15:30Z',
    requestsByEndpoint: {
      '/api/v1/orders': 310000,
      '/api/v1/inventory/check': 110100,
    },
    requestsByKeyPrefix: {
      'gk_demo_glo_98be': 420100,
    },
  },
}

export const DEMO_USAGE_EVENTS: UsageEventDetailDto[] = [
  {
    id: 'evt-901',
    timestamp: '2026-11-21T18:42:15Z',
    apiKeyId: 'key-acme-prod-01',
    keyPrefixSnapshot: 'gk_demo_acme_7f9a',
    endpointPath: '/api/v1/orders',
    httpMethod: 'POST',
    httpStatus: 201,
    latencyMs: 4.8,
    outcomeCategory: 'SUCCESS',
    rateLimitPlan: 'Enterprise Platinum',
    correlationId: 'corr-e3a1-4892',
  },
  {
    id: 'evt-902',
    timestamp: '2026-11-21T18:41:59Z',
    apiKeyId: 'key-acme-prod-01',
    keyPrefixSnapshot: 'gk_demo_acme_7f9a',
    endpointPath: '/api/v1/orders',
    httpMethod: 'POST',
    httpStatus: 201,
    latencyMs: 5.2,
    outcomeCategory: 'SUCCESS',
    rateLimitPlan: 'Enterprise Platinum',
    correlationId: 'corr-e3a1-4891',
  },
  {
    id: 'evt-903',
    timestamp: '2026-11-21T18:40:12Z',
    apiKeyId: 'key-global-orders-01',
    keyPrefixSnapshot: 'gk_demo_glo_98be',
    endpointPath: '/api/v1/orders',
    httpMethod: 'POST',
    httpStatus: 429,
    latencyMs: 1.2,
    outcomeCategory: 'RATE_LIMITED',
    rateLimitPlan: 'Business Standard',
    correlationId: 'corr-c91f-1102',
  },
  {
    id: 'evt-904',
    timestamp: '2026-11-21T18:38:44Z',
    apiKeyId: 'key-acme-staging-02',
    keyPrefixSnapshot: 'gk_demo_acme_14c3',
    endpointPath: '/api/v1/orders',
    httpMethod: 'DELETE',
    httpStatus: 403,
    latencyMs: 2.1,
    outcomeCategory: 'POLICY_DENIED',
    rateLimitPlan: 'Enterprise Platinum',
    correlationId: 'corr-a44b-9931',
  },
  {
    id: 'evt-905',
    timestamp: '2026-11-21T18:35:10Z',
    apiKeyId: 'key-nova-telemetry-01',
    keyPrefixSnapshot: 'gk_demo_nov_54d1',
    endpointPath: '/api/v1/orders',
    httpMethod: 'GET',
    httpStatus: 200,
    latencyMs: 3.9,
    outcomeCategory: 'SUCCESS',
    rateLimitPlan: 'Enterprise Platinum',
    correlationId: 'corr-f01a-7784',
  },
]
