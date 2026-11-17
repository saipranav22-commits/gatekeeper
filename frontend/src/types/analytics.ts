export interface AnalyticsOverviewDto {
  totalRequests: number
  allowedRequests: number
  rateLimitedRequests: number
  scopeDeniedRequests: number
  errorRequests: number
  rateLimitDenialPercentage: number
  usageByPlan: Record<string, number>
  windowStart: string
  windowEnd: string
}

export interface TimeSeriesPointDto {
  bucket: string
  totalRequests: number
  allowedRequests: number
  rateLimitedRequests: number
  scopeDeniedRequests: number
  errorRequests: number
}

export interface TopConsumerDto {
  consumerId: string
  consumerName: string
  consumerEmail: string
  plan: string
  totalRequests: number
  allowedRequests: number
  rateLimitedRequests: number
  rateLimitDenialPercentage: number
}

export interface ConsumerAnalyticsDto {
  consumerId: string
  consumerName: string
  consumerEmail: string
  currentPlan: string
  totalRequests: number
  allowedRequests: number
  rateLimitedRequests: number
  scopeDeniedRequests: number
  firstSeen: string | null
  lastSeen: string | null
  requestsByEndpoint: Record<string, number>
  requestsByKeyPrefix: Record<string, number>
}

export interface UsageEventDetailDto {
  id: string
  timestamp: string
  apiKeyId: string | null
  keyPrefixSnapshot: string | null
  endpointPath: string
  httpMethod: string
  httpStatus: number
  latencyMs: number
  outcomeCategory: string
  rateLimitPlan: string | null
  correlationId: string | null
}
