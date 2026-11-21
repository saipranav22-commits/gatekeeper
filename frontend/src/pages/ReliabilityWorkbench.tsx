import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'
import { useToast } from '@/context/ToastContext'
import { useGatewayHealth } from '@/context/GatewayHealthContext'
import { resilienceService } from '@/services/resilienceService'
import { healthService, ActuatorHealthResponse } from '@/services/healthService'
import { topologyService } from '@/services/topologyService'
import { operationalTimelineService } from '@/services/operationalTimelineService'
import { policyService } from '@/services/policyService'
import { chaosService } from '@/services/chaosService'
import { RouteResilienceSummary } from '@/types/health'
import { RouteResilienceDetail, OperationalTimelineEvent } from '@/types/resilience'
import { ChaosExperiment } from '@/types/chaos'
import { PolicyActivationAuditDto } from '@/types/policy'
import { ServiceTopologyView } from '@/components/reliability/ServiceTopologyView'
import { ResilienceWorkbenchView } from '@/components/reliability/ResilienceWorkbenchView'
import { OperationalTimelineView } from '@/components/reliability/OperationalTimelineView'
import { isDemoMode } from '@/config/appMode'
import { DemoHealthScenarioSelector } from '@/components/demo/DemoHealthScenarioSelector'
import {
  Network,
  Zap,
  Clock,
  RefreshCw,
} from 'lucide-react'

export const ReliabilityWorkbench: React.FC = () => {
  const { admin, canMutate } = useAuth()
  const { showToast } = useToast()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()

  const { status: gatewayStatus, resetDemoScenario } = useGatewayHealth()

  // Tab State
  const tabParam = searchParams.get('tab')
  const initialTab: 'topology' | 'resilience' | 'timeline' =
    tabParam === 'resilience' || tabParam === 'timeline' ? tabParam : 'topology'
  const [activeTab, setActiveTab] = useState<'topology' | 'resilience' | 'timeline'>(initialTab)

  // Selection & Telemetry State
  const [summaries, setSummaries] = useState<RouteResilienceSummary[]>([])
  const [selectedRouteId, setSelectedRouteId] = useState<string>('orders-service-v1')
  const [activeDetail, setActiveDetail] = useState<RouteResilienceDetail | null>(null)
  const [actuatorHealth, setActuatorHealth] = useState<ActuatorHealthResponse | null>(null)
  const [chaosExperiments, setChaosExperiments] = useState<ChaosExperiment[]>(() =>
    chaosService.getHistoricalExperiments()
  )
  const [policyAudits, setPolicyAudits] = useState<PolicyActivationAuditDto[]>([])

  const [isLoading, setIsLoading] = useState<boolean>(false)
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false)
  const [isResetting, setIsResetting] = useState<boolean>(false)
  const [lastRefreshed, setLastRefreshed] = useState<Date>(new Date())

  const isAdmin = canMutate() || admin?.role === 'ROLE_SUPER_ADMIN' || admin?.role === 'ROLE_ADMIN'

  // Synchronize Tab with URL query params
  const handleTabChange = (tab: 'topology' | 'resilience' | 'timeline') => {
    setActiveTab(tab)
    setSearchParams({ tab })
  }

  // Load Resilience and Health Telemetry
  const fetchTelemetry = useCallback(async (isBackground = false) => {
    if (isBackground) setIsRefreshing(true)

    try {
      // 1. Fetch Route Resilience Summaries
      const rData = await resilienceService.getRouteResilience()
      setSummaries(rData)

      // Auto-select first route if not selected
      const routeToFetch = rData.find((r) => r.routeId === selectedRouteId)
        ? selectedRouteId
        : rData[0]?.routeId || 'orders-service-v1'

      // 2. Fetch Detail for Active Route
      try {
        const detail = await resilienceService.getRouteResilienceDetail(routeToFetch)
        setActiveDetail(detail)
      } catch {
        // Fallback or route missing
      }

      // 3. Fetch Actuator Health (DB & Redis components)
      try {
        const actHealth = await healthService.getActuatorHealth()
        setActuatorHealth(actHealth)
      } catch {
        setActuatorHealth(null)
      }

      // 4. Fetch Chaos Experiments (Live + Historical)
      try {
        const experiments = await chaosService.getAllExperimentsCombined()
        setChaosExperiments(experiments)
      } catch {
        setChaosExperiments(chaosService.getHistoricalExperiments())
      }

      // 5. Fetch Policy Audits
      try {
        const audits = await policyService.getAuditHistory('api-gateway-default')
        setPolicyAudits(audits)
      } catch {
        setPolicyAudits([])
      }

      setLastRefreshed(new Date())
    } catch {
      if (!isBackground) {
        showToast(
          'error',
          'Resilience Telemetry Offline',
          'Unable to reach resilience controllers. Displaying cached or fallback states.'
        )
      }
    } finally {
      setIsLoading(false)
      setIsRefreshing(false)
    }
  }, [selectedRouteId, showToast])

  useEffect(() => {
    let isMounted = true
    const timer = setTimeout(() => {
      if (isMounted) {
        fetchTelemetry(false)
      }
    }, 0)

    // Periodic 30s background poll
    const interval = setInterval(() => {
      if (document.visibilityState === 'visible') {
        fetchTelemetry(true)
      }
    }, 30000)

    return () => {
      isMounted = false
      clearTimeout(timer)
      clearInterval(interval)
    }
  }, [fetchTelemetry])

  // Synchronize telemetry with scenario changes immediately (avoid duplicate fetch on initial mount)
  const prevGatewayStatusRef = useRef(gatewayStatus)
  useEffect(() => {
    if (prevGatewayStatusRef.current !== gatewayStatus) {
      prevGatewayStatusRef.current = gatewayStatus
      fetchTelemetry(true)
    }
  }, [gatewayStatus, fetchTelemetry])

  // Select route and fetch its specific detail
  const handleSelectRoute = async (routeId: string) => {
    setSelectedRouteId(routeId)
    try {
      const detail = await resilienceService.getRouteResilienceDetail(routeId)
      setActiveDetail(detail)
    } catch {
      setActiveDetail(null)
    }
  }

  // Reset Circuit Breaker Handler
  const handleResetCircuitBreaker = async (routeId: string) => {
    setIsResetting(true)
    try {
      const result = await resilienceService.resetRouteCircuitBreaker(routeId)
      showToast(
        'success',
        'Circuit Breaker Reset to CLOSED',
        `Route ${result.routeId} successfully reset by ${result.resetBy}. Sliding window purged.`
      )
      if (isDemoMode()) {
        await resetDemoScenario()
      } else {
        await fetchTelemetry(true)
      }
    } catch (err: unknown) {
      const errorMessage =
        err && typeof err === 'object' && 'response' in err
          ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
          : undefined
      showToast(
        'error',
        'Reset Failed',
        errorMessage || 'Failed to reset circuit breaker.'
      )
    } finally {
      setIsResetting(false)
    }
  }

  // Deep Link to Analytics
  const handleNavigateAnalytics = (_routeId?: string) => {
    navigate('/analytics')
  }

  // Construct Configured Topology Data
  const topologyData = useMemo(() => {
    return topologyService.buildTopology(
      gatewayStatus,
      summaries,
      actuatorHealth,
      lastRefreshed.toISOString()
    )
  }, [gatewayStatus, summaries, actuatorHealth, lastRefreshed])

  // Derive Operational Timeline Events
  const timelineEvents: OperationalTimelineEvent[] = useMemo(() => {
    return operationalTimelineService.deriveEvents(
      summaries,
      chaosExperiments,
      policyAudits,
      null
    )
  }, [summaries, chaosExperiments, policyAudits])

  return (
    <div className="space-y-6">
      {/* Platform Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-moss/40 pb-5">
        <div>
          <div className="flex items-center gap-2">
            <span className="text-[10px] font-mono uppercase tracking-widest text-emerald font-semibold px-2 py-0.5 rounded bg-emerald/10 border border-emerald/30">
              Reliability & Control Plane
            </span>
            <span className="text-xs text-titanium font-mono">Resilience4j & SSRF Guard</span>
          </div>
          <h1 className="text-2xl font-bold font-mono text-silver-frost tracking-tight mt-1">
            Reliability Command Center
          </h1>
          <p className="text-xs font-mono text-titanium mt-0.5">
            Real-time topology, circuit breakers, bulkhead isolation, and operational diagnostics.
          </p>
        </div>

        {/* Global Controls & Refresh Status */}
        <div className="flex items-center gap-3">
          <div className="text-right hidden sm:block">
            <p className="text-[10px] font-mono text-titanium">TELEMETRY LAST OBSERVED</p>
            <p className="text-xs font-mono font-bold text-silver-frost">
              {lastRefreshed.toLocaleTimeString()}
            </p>
          </div>

          <button
            type="button"
            onClick={() => fetchTelemetry(true)}
            disabled={isRefreshing}
            className="flex items-center gap-2 px-3 py-2 bg-canvas hover:bg-surface-elevated text-titanium hover:text-silver-frost border border-moss/40 rounded-lg text-xs font-mono transition-colors disabled:opacity-50"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isRefreshing ? 'animate-spin text-emerald' : ''}`} />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {/* Recruiter Demo Health Scenario Selector */}
      {isDemoMode() && (
        <DemoHealthScenarioSelector />
      )}

      {/* Navigation Sub-Tabs */}
      <div className="flex flex-wrap items-center gap-2 bg-surface p-1 rounded-xl border border-moss/30 max-w-fit">
        <button
          type="button"
          onClick={() => handleTabChange('topology')}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-mono font-medium transition-all ${
            activeTab === 'topology'
              ? 'bg-moss/40 text-emerald border border-emerald/40 shadow-xs'
              : 'text-titanium hover:text-silver-frost hover:bg-surface-elevated'
          }`}
        >
          <Network className="w-4 h-4" />
          <span>Configured Service Topology</span>
        </button>

        <button
          type="button"
          onClick={() => handleTabChange('resilience')}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-mono font-medium transition-all ${
            activeTab === 'resilience'
              ? 'bg-moss/40 text-emerald border border-emerald/40 shadow-xs'
              : 'text-titanium hover:text-silver-frost hover:bg-surface-elevated'
          }`}
        >
          <Zap className="w-4 h-4" />
          <span>Resilience Policies & Breakers</span>
        </button>

        <button
          type="button"
          onClick={() => handleTabChange('timeline')}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-mono font-medium transition-all ${
            activeTab === 'timeline'
              ? 'bg-moss/40 text-emerald border border-emerald/40 shadow-xs'
              : 'text-titanium hover:text-silver-frost hover:bg-surface-elevated'
          }`}
        >
          <Clock className="w-4 h-4" />
          <span>Operational Timelines & Audits</span>
          {timelineEvents.length > 0 && (
            <span className="text-[10px] px-1.5 py-0.2 rounded-full bg-emerald/20 text-emerald font-bold">
              {timelineEvents.length}
            </span>
          )}
        </button>
      </div>

      {/* Main Tab Panels */}
      {isLoading ? (
        <div className="bg-surface/40 border border-moss/20 rounded-xl p-12 text-center space-y-3 animate-pulse">
          <div className="w-6 h-6 border-2 border-emerald border-t-transparent rounded-full animate-spin mx-auto" />
          <p className="text-xs font-mono text-titanium">Polling Gatekeeper resilience engine...</p>
        </div>
      ) : (
        <>
          {activeTab === 'topology' && (
            <ServiceTopologyView
              topology={topologyData}
              onSelectRoute={(routeId) => {
                handleSelectRoute(routeId)
                handleTabChange('resilience')
              }}
              onNavigateAnalytics={handleNavigateAnalytics}
            />
          )}

          {activeTab === 'resilience' && (
            <ResilienceWorkbenchView
              summaries={summaries}
              activeDetail={activeDetail}
              selectedRouteId={selectedRouteId}
              onSelectRoute={handleSelectRoute}
              onResetCircuitBreaker={handleResetCircuitBreaker}
              onNavigateAnalytics={handleNavigateAnalytics}
              isAdmin={isAdmin}
              isResetting={isResetting}
            />
          )}

          {activeTab === 'timeline' && (
            <OperationalTimelineView events={timelineEvents} />
          )}
        </>
      )}
    </div>
  )
}
