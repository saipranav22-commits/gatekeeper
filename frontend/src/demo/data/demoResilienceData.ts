import { RouteResilienceSummary } from '@/types/health'
import { RouteResilienceDetail } from '@/types/resilience'
import { ResetCircuitBreakerResponse } from '@/services/resilienceService'

export type DemoHealthState = 'HEALTHY' | 'DEGRADED' | 'UNHEALTHY' | 'RECOVERING'
export type DemoResilienceScenario = DemoHealthState | 'ELEVATED_ERROR_RATE' | 'CIRCUIT_OPEN' | 'RECOVERY'

let currentScenario: DemoResilienceScenario = 'HEALTHY'

export const normalizeToHealthState = (scenario: DemoResilienceScenario): DemoHealthState => {
  if (scenario === 'CIRCUIT_OPEN' || scenario === 'UNHEALTHY') return 'UNHEALTHY'
  if (scenario === 'ELEVATED_ERROR_RATE' || scenario === 'DEGRADED') return 'DEGRADED'
  if (scenario === 'RECOVERY' || scenario === 'RECOVERING') return 'RECOVERING'
  return 'HEALTHY'
}

export const setDemoHealthScenario = (scenario: DemoResilienceScenario): void => {
  currentScenario = scenario
}

export const getDemoHealthScenario = (): DemoHealthState => {
  return normalizeToHealthState(currentScenario)
}

export const resetDemoHealthScenario = (): void => {
  currentScenario = 'HEALTHY'
}

export const setDemoResilienceScenario = (scenario: DemoResilienceScenario): void => {
  currentScenario = scenario
}

export const getDemoResilienceScenario = (): DemoResilienceScenario => {
  return currentScenario
}

export const getDemoRouteResilience = (): RouteResilienceSummary[] => {
  switch (currentScenario) {
    case 'UNHEALTHY':
    case 'CIRCUIT_OPEN':
      return [
        {
          routeId: 'orders-service-v1',
          circuitBreakerState: 'OPEN',
          failureRate: 74.2,
          slowCallRate: 18.5,
          totalCalls: 1250,
          failedCalls: 928,
          slowCalls: 231,
          rejectedCalls: 342,
          bulkheadMaxConcurrent: 50,
          bulkheadAvailablePermits: 50,
          bulkheadInFlightCalls: 0,
          bulkheadRejectedCalls: 14,
          retryMaxAttempts: 3,
        },
        {
          routeId: 'inventory-service-v1',
          circuitBreakerState: 'CLOSED',
          failureRate: 0.2,
          slowCallRate: 1.1,
          totalCalls: 3400,
          failedCalls: 7,
          slowCalls: 37,
          rejectedCalls: 0,
          bulkheadMaxConcurrent: 50,
          bulkheadAvailablePermits: 48,
          bulkheadInFlightCalls: 2,
          bulkheadRejectedCalls: 0,
          retryMaxAttempts: 3,
        },
      ]

    case 'DEGRADED':
    case 'ELEVATED_ERROR_RATE':
      return [
        {
          routeId: 'orders-service-v1',
          circuitBreakerState: 'HALF_OPEN',
          failureRate: 38.6,
          slowCallRate: 24.1,
          totalCalls: 890,
          failedCalls: 344,
          slowCalls: 215,
          rejectedCalls: 45,
          bulkheadMaxConcurrent: 50,
          bulkheadAvailablePermits: 44,
          bulkheadInFlightCalls: 6,
          bulkheadRejectedCalls: 2,
          retryMaxAttempts: 3,
        },
        {
          routeId: 'inventory-service-v1',
          circuitBreakerState: 'CLOSED',
          failureRate: 0.4,
          slowCallRate: 1.5,
          totalCalls: 2800,
          failedCalls: 11,
          slowCalls: 42,
          rejectedCalls: 0,
          bulkheadMaxConcurrent: 50,
          bulkheadAvailablePermits: 49,
          bulkheadInFlightCalls: 1,
          bulkheadRejectedCalls: 0,
          retryMaxAttempts: 3,
        },
      ]

    case 'RECOVERING':
    case 'RECOVERY':
      return [
        {
          routeId: 'orders-service-v1',
          circuitBreakerState: 'HALF_OPEN',
          failureRate: 4.8,
          slowCallRate: 3.2,
          totalCalls: 512,
          failedCalls: 25,
          slowCalls: 16,
          rejectedCalls: 0,
          bulkheadMaxConcurrent: 50,
          bulkheadAvailablePermits: 47,
          bulkheadInFlightCalls: 3,
          bulkheadRejectedCalls: 0,
          retryMaxAttempts: 3,
        },
        {
          routeId: 'inventory-service-v1',
          circuitBreakerState: 'CLOSED',
          failureRate: 0.1,
          slowCallRate: 0.8,
          totalCalls: 1800,
          failedCalls: 2,
          slowCalls: 14,
          rejectedCalls: 0,
          bulkheadMaxConcurrent: 50,
          bulkheadAvailablePermits: 50,
          bulkheadInFlightCalls: 0,
          bulkheadRejectedCalls: 0,
          retryMaxAttempts: 3,
        },
      ]

    case 'HEALTHY':
    default:
      return [
        {
          routeId: 'orders-service-v1',
          circuitBreakerState: 'CLOSED',
          failureRate: 0.1,
          slowCallRate: 0.4,
          totalCalls: 4210,
          failedCalls: 4,
          slowCalls: 17,
          rejectedCalls: 0,
          bulkheadMaxConcurrent: 50,
          bulkheadAvailablePermits: 48,
          bulkheadInFlightCalls: 2,
          bulkheadRejectedCalls: 0,
          retryMaxAttempts: 3,
        },
        {
          routeId: 'inventory-service-v1',
          circuitBreakerState: 'CLOSED',
          failureRate: 0.05,
          slowCallRate: 0.2,
          totalCalls: 3890,
          failedCalls: 2,
          slowCalls: 8,
          rejectedCalls: 0,
          bulkheadMaxConcurrent: 50,
          bulkheadAvailablePermits: 49,
          bulkheadInFlightCalls: 1,
          bulkheadRejectedCalls: 0,
          retryMaxAttempts: 3,
        },
      ]
  }
}

export const getDemoRouteResilienceDetail = (routeId: string): RouteResilienceDetail => {
  const isOrders = routeId === 'orders-service-v1'
  const isCircuitOpen = (currentScenario === 'UNHEALTHY' || (currentScenario as string) === 'CIRCUIT_OPEN') && isOrders
  const isHalfOpen = (currentScenario === 'DEGRADED' || currentScenario === 'RECOVERING' || (currentScenario as string) === 'ELEVATED_ERROR_RATE' || (currentScenario as string) === 'RECOVERY') && isOrders
  const isRecovering = (currentScenario === 'RECOVERING' || (currentScenario as string) === 'RECOVERY') && isOrders

  return {
    routeId,
    circuitBreaker: {
      state: isCircuitOpen ? 'OPEN' : isHalfOpen ? 'HALF_OPEN' : 'CLOSED',
      failureRateThreshold: 50.0,
      slowCallRateThreshold: 50.0,
      slowCallDurationMs: 2000,
      slidingWindowSize: 20,
      minimumNumberOfCalls: 10,
      waitDurationInOpenStateMs: 15000,
      permittedNumberOfCallsInHalfOpenState: 5,
      totalCalls: isOrders ? (isCircuitOpen ? 1250 : isRecovering ? 512 : 4210) : 3890,
      successfulCalls: isOrders ? (isCircuitOpen ? 322 : isRecovering ? 487 : 4206) : 3888,
      failedCalls: isOrders ? (isCircuitOpen ? 928 : isRecovering ? 25 : 4) : 2,
      slowCalls: isOrders ? (isCircuitOpen ? 231 : isRecovering ? 16 : 17) : 8,
      failureRatePercent: isOrders ? (isCircuitOpen ? 74.2 : isRecovering ? 4.8 : isHalfOpen ? 38.6 : 0.1) : 0.05,
      slowCallRatePercent: isOrders ? (isCircuitOpen ? 18.5 : isRecovering ? 3.2 : isHalfOpen ? 24.1 : 0.4) : 0.2,
      totalRejectedCalls: isOrders && isCircuitOpen ? 342 : 0,
    },
    bulkhead: {
      maxConcurrentCalls: 50,
      availablePermits: isOrders && isCircuitOpen ? 50 : 48,
      inFlightCalls: isOrders && isCircuitOpen ? 0 : 2,
      rejectedCalls: isOrders && isCircuitOpen ? 14 : 0,
    },
    retry: {
      maxAttempts: 3,
      initialBackoffMs: 200,
      backoffMultiplier: 2.0,
      jitterFactor: 0.1,
      maxCumulativeDeadlineMs: 1500,
    },
  }
}

export const resetDemoCircuitBreaker = (routeId: string): ResetCircuitBreakerResponse => {
  currentScenario = 'HEALTHY'
  return {
    routeId,
    status: 'SUCCESS',
    circuitState: 'CLOSED',
    resetBy: 'demo-recruiter',
  }
}
