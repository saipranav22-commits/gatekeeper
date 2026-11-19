import React, { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react'
import { HealthStatus, HealthResponse, RouteResilienceSummary } from '@/types/health'
import { healthService } from '@/services/healthService'
import { resilienceService } from '@/services/resilienceService'
import { useAuth } from './AuthContext'

interface GatewayHealthContextValue {
  status: HealthStatus
  healthData: HealthResponse | null
  resilienceData: RouteResilienceSummary[] | null
  lastChecked: Date | null
  isChecking: boolean
  pollingInterval: number
  setPollingInterval: (ms: number) => void
  refetch: () => Promise<void>
}

const GatewayHealthContext = createContext<GatewayHealthContextValue | undefined>(undefined)

export const GatewayHealthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated } = useAuth()
  const [status, setStatus] = useState<HealthStatus>('CHECKING')
  const [healthData, setHealthData] = useState<HealthResponse | null>(null)
  const [resilienceData, setResilienceData] = useState<RouteResilienceSummary[] | null>(null)
  const [lastChecked, setLastChecked] = useState<Date | null>(null)
  const [isChecking, setIsChecking] = useState<boolean>(false)
  const [pollingInterval, setPollingInterval] = useState<number>(30000) // 30 seconds default
  const abortControllerRef = useRef<AbortController | null>(null)

  const checkHealth = useCallback(async () => {
    // Cancel any previous in-flight check
    if (abortControllerRef.current) {
      abortControllerRef.current.abort()
    }
    const controller = new AbortController()
    abortControllerRef.current = controller

    setIsChecking(true)
    try {
      // 1. Fetch public health endpoint
      const hData = await healthService.getHealth(controller.signal)
      setHealthData(hData)

      // 2. Fetch authenticated route resilience if authenticated
      let rData: RouteResilienceSummary[] = []
      if (isAuthenticated) {
        try {
          rData = await resilienceService.getRouteResilience(controller.signal)
          setResilienceData(rData)
        } catch {
          // If resilience fails (e.g. insufficient role or route missing), keep empty
          rData = []
        }
      }

      setLastChecked(new Date())

      // 3. Determine composite operational health status
      if (hData.status === 'UP') {
        const hasTrippedBreaker = rData.some(
          (r) => r.circuitBreakerState === 'OPEN' || r.circuitBreakerState === 'HALF_OPEN'
        )
        const hasHighFailureRate = rData.some((r) => r.failureRate > 20)

        if (hasTrippedBreaker || hasHighFailureRate) {
          setStatus('DEGRADED')
        } else {
          setStatus('HEALTHY')
        }
      } else {
        setStatus('DEGRADED')
      }
    } catch (err: unknown) {
      if ((err as Error)?.name === 'CanceledError' || (err as Error)?.name === 'AbortError') {
        return
      }
      setStatus('OFFLINE')
      setLastChecked(new Date())
    } finally {
      setIsChecking(false)
    }
  }, [isAuthenticated])

  // Periodic polling with pause on hidden tab
  useEffect(() => {
    const initialTimer = setTimeout(() => {
      checkHealth()
    }, 0)

    if (pollingInterval <= 0) {
      return () => {
        clearTimeout(initialTimer)
        if (abortControllerRef.current) {
          abortControllerRef.current.abort()
        }
      }
    }

    const timer = setInterval(() => {
      // Only poll when the tab is currently visible
      if (document.visibilityState === 'visible') {
        checkHealth()
      } else {
        setStatus((prev) => (prev === 'HEALTHY' ? 'STALE' : prev))
      }
    }, pollingInterval)

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        checkHealth()
      }
    }

    document.addEventListener('visibilitychange', handleVisibilityChange)

    return () => {
      clearTimeout(initialTimer)
      clearInterval(timer)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      if (abortControllerRef.current) {
        abortControllerRef.current.abort()
      }
    }
  }, [checkHealth, pollingInterval])

  return (
    <GatewayHealthContext.Provider
      value={{
        status,
        healthData,
        resilienceData,
        lastChecked,
        isChecking,
        pollingInterval,
        setPollingInterval,
        refetch: checkHealth,
      }}
    >
      {children}
    </GatewayHealthContext.Provider>
  )
}

export const useGatewayHealth = (): GatewayHealthContextValue => {
  const context = useContext(GatewayHealthContext)
  if (!context) {
    throw new Error('useGatewayHealth must be used within a GatewayHealthProvider')
  }
  return context
}
