import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import React from 'react'
import { GatewayHealthProvider, useGatewayHealth } from '@/context/GatewayHealthContext'
import { healthService } from '@/services/healthService'
import { resilienceService } from '@/services/resilienceService'

vi.mock('@/services/healthService', () => ({
  healthService: {
    getHealth: vi.fn(),
  },
}))

vi.mock('@/services/resilienceService', () => ({
  resilienceService: {
    getRouteResilience: vi.fn(),
  },
}))

vi.mock('@/context/AuthContext', () => ({
  useAuth: () => ({
    isAuthenticated: true,
  }),
}))

const TestHealthConsumer: React.FC = () => {
  const { status, healthData, resilienceData, lastChecked, refetch } = useGatewayHealth()
  return (
    <div>
      <div data-testid="health-status">{status}</div>
      <div data-testid="service-name">{healthData?.serviceName || 'none'}</div>
      <div data-testid="resilience-count">{resilienceData?.length || 0}</div>
      <div data-testid="last-checked">{lastChecked ? 'checked' : 'none'}</div>
      <button onClick={() => refetch()} data-testid="refetch-btn">
        Refetch
      </button>
    </div>
  )
}

describe('GatewayHealthContext', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('transitions to HEALTHY when health is UP and circuit breakers are CLOSED', async () => {
    vi.mocked(healthService.getHealth).mockResolvedValueOnce({
      status: 'UP',
      service: 'gatekeeper',
      serviceName: 'gatekeeper',
      timestamp: new Date().toISOString(),
    })

    vi.mocked(resilienceService.getRouteResilience).mockResolvedValueOnce([
      {
        routeId: 'orders-service',
        circuitBreakerState: 'CLOSED',
        failureRate: 0.0,
        slowCallRate: 0.0,
        totalCalls: 100,
        failedCalls: 0,
        slowCalls: 0,
        rejectedCalls: 0,
        bulkheadMaxConcurrent: 50,
        bulkheadAvailablePermits: 50,
        bulkheadInFlightCalls: 0,
        bulkheadRejectedCalls: 0,
        retryMaxAttempts: 3,
      },
    ])

    render(
      <GatewayHealthProvider>
        <TestHealthConsumer />
      </GatewayHealthProvider>
    )

    await waitFor(() => {
      expect(screen.getByTestId('health-status').textContent).toBe('HEALTHY')
      expect(screen.getByTestId('service-name').textContent).toBe('gatekeeper')
      expect(screen.getByTestId('resilience-count').textContent).toBe('1')
    })
  })

  it('transitions to DEGRADED when any circuit breaker is OPEN', async () => {
    vi.mocked(healthService.getHealth).mockResolvedValueOnce({
      status: 'UP',
      service: 'gatekeeper',
      serviceName: 'gatekeeper',
      timestamp: new Date().toISOString(),
    })

    vi.mocked(resilienceService.getRouteResilience).mockResolvedValueOnce([
      {
        routeId: 'inventory-service',
        circuitBreakerState: 'OPEN',
        failureRate: 65.0,
        slowCallRate: 10.0,
        totalCalls: 200,
        failedCalls: 130,
        slowCalls: 20,
        rejectedCalls: 45,
        bulkheadMaxConcurrent: 30,
        bulkheadAvailablePermits: 0,
        bulkheadInFlightCalls: 30,
        bulkheadRejectedCalls: 45,
        retryMaxAttempts: 3,
      },
    ])

    render(
      <GatewayHealthProvider>
        <TestHealthConsumer />
      </GatewayHealthProvider>
    )

    await waitFor(() => {
      expect(screen.getByTestId('health-status').textContent).toBe('DEGRADED')
    })
  })

  it('transitions to OFFLINE when healthService throws a connection error', async () => {
    vi.mocked(healthService.getHealth).mockRejectedValueOnce(new Error('Network Error: Gateway Down'))

    render(
      <GatewayHealthProvider>
        <TestHealthConsumer />
      </GatewayHealthProvider>
    )

    await waitFor(() => {
      expect(screen.getByTestId('health-status').textContent).toBe('OFFLINE')
    })
  })

  it('cleans up timers and abort controllers cleanly on unmount', () => {
    vi.mocked(healthService.getHealth).mockResolvedValue({
      status: 'UP',
      service: 'gatekeeper',
      serviceName: 'gatekeeper',
      timestamp: new Date().toISOString(),
    })

    const { unmount } = render(
      <GatewayHealthProvider>
        <TestHealthConsumer />
      </GatewayHealthProvider>
    )

    expect(() => unmount()).not.toThrow()
  })
})
