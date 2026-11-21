import React, { useState } from 'react'
import { RouteResilienceSummary } from '@/types/health'
import { RouteResilienceDetail } from '@/types/resilience'
import { Badge } from '@/components/ui/Badge'
import { Modal } from '@/components/ui/Modal'
import {
  Zap,
  Gauge,
  Clock,
  RotateCcw,
  AlertTriangle,
  Info,
  ExternalLink,
  TrendingDown,
} from 'lucide-react'

interface ResilienceWorkbenchViewProps {
  summaries: RouteResilienceSummary[]
  activeDetail: RouteResilienceDetail | null
  selectedRouteId: string
  onSelectRoute: (routeId: string) => void
  onResetCircuitBreaker: (routeId: string) => Promise<void>
  onNavigateAnalytics?: (routeId: string) => void
  isAdmin: boolean
  isResetting?: boolean
}

export const ResilienceWorkbenchView: React.FC<ResilienceWorkbenchViewProps> = ({
  summaries,
  activeDetail,
  selectedRouteId,
  onSelectRoute,
  onResetCircuitBreaker,
  onNavigateAnalytics,
  isAdmin,
  isResetting,
}) => {
  const [showResetModal, setShowResetModal] = useState<boolean>(false)
  const [resetConfirmation, setResetConfirmation] = useState<string>('')

  const activeSummary = summaries.find((s) => s.routeId === selectedRouteId) || summaries[0]

  const getCircuitBadge = (state: string) => {
    switch (state) {
      case 'CLOSED':
        return <Badge variant="success">CLOSED (NOMINAL)</Badge>
      case 'HALF_OPEN':
        return (activeSummary?.failureRate ?? 0) <= 10 ? (
          <Badge variant="recovering">HALF_OPEN (RECOVERING)</Badge>
        ) : (
          <Badge variant="warning">HALF_OPEN (PROBING)</Badge>
        )
      case 'OPEN':
        return <Badge variant="danger">OPEN (FAIL-FAST)</Badge>
      default:
        return <Badge variant="slate">{state}</Badge>
    }
  }

  const handleConfirmReset = async () => {
    if (resetConfirmation !== activeSummary?.routeId) return
    await onResetCircuitBreaker(activeSummary.routeId)
    setShowResetModal(false)
    setResetConfirmation('')
  }

  // Calculate bulkhead percentage
  const maxConcurrency = activeSummary?.bulkheadMaxConcurrent || 10
  const inFlight = activeSummary?.bulkheadInFlightCalls || 0
  const concurrencyPercent = Math.min(100, Math.round((inFlight / maxConcurrency) * 100))

  return (
    <div className="space-y-6">
      {/* Route Selector Tabs */}
      <div className="flex flex-wrap items-center gap-2 border-b border-moss/30 pb-3">
        {summaries.map((s) => {
          const isSelected = s.routeId === selectedRouteId
          return (
            <button
              key={s.routeId}
              type="button"
              onClick={() => onSelectRoute(s.routeId)}
              className={`flex items-center gap-2 px-4 py-2 rounded-lg font-mono text-xs transition-colors border ${
                isSelected
                  ? 'bg-surface border-emerald text-emerald font-semibold shadow-xs'
                  : 'bg-canvas border-moss/30 text-titanium hover:text-silver-frost hover:border-moss/60'
              }`}
            >
              <span>{s.routeId}</span>
              <span
                className={`w-2 h-2 rounded-full ${
                  s.circuitBreakerState === 'CLOSED'
                    ? 'bg-emerald'
                    : s.circuitBreakerState === 'HALF_OPEN'
                    ? (s.failureRate ?? 0) <= 10
                      ? 'bg-sky-400 animate-pulse'
                      : 'bg-warning animate-pulse'
                    : 'bg-signal-red animate-pulse'
                }`}
              />
            </button>
          )
        })}
      </div>

      {activeSummary && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Main Inspection Column (2 Cols) */}
          <div className="lg:col-span-2 space-y-6">
            {/* 1. Circuit Breaker Telemetry Card */}
            <div className="bg-surface border border-moss/30 rounded-xl p-5 space-y-4">
              <div className="flex items-center justify-between border-b border-moss/20 pb-3">
                <div className="flex items-center gap-2.5">
                  <div className="p-2 rounded-lg bg-emerald/10 border border-emerald/30 text-emerald">
                    <Zap className="w-4 h-4" />
                  </div>
                  <div>
                    <h3 className="font-mono font-bold text-silver-frost text-sm">
                      Circuit Breaker: {activeSummary.routeId}
                    </h3>
                    <p className="text-[11px] font-mono text-titanium">
                      Sliding Window Evaluation & Thresholds
                    </p>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  {getCircuitBadge(activeSummary.circuitBreakerState)}
                  {isAdmin && (
                    <button
                      type="button"
                      onClick={() => setShowResetModal(true)}
                      disabled={isResetting || activeSummary.circuitBreakerState === 'CLOSED'}
                      className="flex items-center gap-1.5 px-3 py-1.5 bg-canvas hover:bg-surface-elevated text-titanium hover:text-silver-frost border border-moss/40 rounded-lg text-xs font-mono transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                      title={activeSummary.circuitBreakerState === 'CLOSED' ? 'Breaker is already closed' : 'Reset circuit breaker to CLOSED'}
                    >
                      <RotateCcw className={`w-3.5 h-3.5 ${isResetting ? 'animate-spin' : ''}`} />
                      <span>Reset Breaker</span>
                    </button>
                  )}
                </div>
              </div>

              {/* Real Metrics Grid */}
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <div className="bg-canvas p-3 rounded-lg border border-moss/20">
                  <span className="text-[10px] font-mono uppercase text-titanium">Failure Rate</span>
                  <p className="text-lg font-mono font-bold text-silver-frost mt-1">
                    {activeSummary.failureRate.toFixed(1)}%
                  </p>
                  <span className="text-[10px] font-mono text-titanium">
                    Threshold: {activeDetail?.circuitBreaker.failureRateThreshold ?? 50}%
                  </span>
                </div>
                <div className="bg-canvas p-3 rounded-lg border border-moss/20">
                  <span className="text-[10px] font-mono uppercase text-titanium">Slow Call Rate</span>
                  <p className="text-lg font-mono font-bold text-silver-frost mt-1">
                    {activeSummary.slowCallRate.toFixed(1)}%
                  </p>
                  <span className="text-[10px] font-mono text-titanium">
                    Threshold: {activeDetail?.circuitBreaker.slowCallRateThreshold ?? 50}%
                  </span>
                </div>
                <div className="bg-canvas p-3 rounded-lg border border-moss/20">
                  <span className="text-[10px] font-mono uppercase text-titanium">Total Calls</span>
                  <p className="text-lg font-mono font-bold text-silver-frost mt-1">
                    {activeSummary.totalCalls.toLocaleString()}
                  </p>
                  <span className="text-[10px] font-mono text-titanium">
                    Failed: {activeSummary.failedCalls}
                  </span>
                </div>
                <div className="bg-canvas p-3 rounded-lg border border-moss/20">
                  <span className="text-[10px] font-mono uppercase text-titanium">Fast Rejections</span>
                  <p className="text-lg font-mono font-bold text-signal-red mt-1">
                    {activeSummary.rejectedCalls.toLocaleString()}
                  </p>
                  <span className="text-[10px] font-mono text-titanium">
                    HTTP 503 Rejections
                  </span>
                </div>
              </div>

              {/* Sliding Window Parameters */}
              {activeDetail && (
                <div className="bg-canvas/50 p-3 rounded-lg border border-moss/20 grid grid-cols-2 sm:grid-cols-4 gap-2 text-[11px] font-mono">
                  <div>
                    <span className="text-titanium">Window Size:</span>{' '}
                    <span className="text-silver-frost font-bold">
                      {activeDetail.circuitBreaker.slidingWindowSize} calls
                    </span>
                  </div>
                  <div>
                    <span className="text-titanium">Min Calls:</span>{' '}
                    <span className="text-silver-frost font-bold">
                      {activeDetail.circuitBreaker.minimumNumberOfCalls}
                    </span>
                  </div>
                  <div>
                    <span className="text-titanium">Wait in Open:</span>{' '}
                    <span className="text-silver-frost font-bold">
                      {activeDetail.circuitBreaker.waitDurationInOpenStateMs} ms
                    </span>
                  </div>
                  <div>
                    <span className="text-titanium">Half-Open Probes:</span>{' '}
                    <span className="text-silver-frost font-bold">
                      {activeDetail.circuitBreaker.permittedNumberOfCallsInHalfOpenState}
                    </span>
                  </div>
                </div>
              )}
            </div>

            {/* 2. Bulkhead Concurrency Card */}
            <div className="bg-surface border border-moss/30 rounded-xl p-5 space-y-4">
              <div className="flex items-center justify-between border-b border-moss/20 pb-3">
                <div className="flex items-center gap-2.5">
                  <div className="p-2 rounded-lg bg-emerald/10 border border-emerald/30 text-emerald">
                    <Gauge className="w-4 h-4" />
                  </div>
                  <div>
                    <h3 className="font-mono font-bold text-silver-frost text-sm">
                      Route Bulkhead Semaphore
                    </h3>
                    <p className="text-[11px] font-mono text-titanium">
                      Thread pool & in-flight connection isolation
                    </p>
                  </div>
                </div>
                <span className="text-xs font-mono font-bold text-emerald">
                  {activeSummary.bulkheadInFlightCalls} / {activeSummary.bulkheadMaxConcurrent} In Flight
                </span>
              </div>

              {/* Visual Concurrency Meter */}
              <div className="space-y-1.5">
                <div className="flex justify-between text-xs font-mono text-titanium">
                  <span>Capacity Utilization</span>
                  <span>{concurrencyPercent}%</span>
                </div>
                <div className="h-3 w-full bg-canvas rounded-full overflow-hidden border border-moss/30 p-0.5">
                  <div
                    className={`h-full rounded-full transition-all duration-300 ${
                      concurrencyPercent > 80
                        ? 'bg-signal-red'
                        : concurrencyPercent > 50
                        ? 'bg-warning'
                        : 'bg-emerald'
                    }`}
                    style={{ width: `${Math.max(4, concurrencyPercent)}%` }}
                  />
                </div>
              </div>

              <div className="grid grid-cols-3 gap-3 text-xs font-mono">
                <div className="bg-canvas p-2.5 rounded-lg border border-moss/20">
                  <span className="text-titanium text-[10px]">Max Concurrent</span>
                  <p className="font-bold text-silver-frost mt-0.5">
                    {activeSummary.bulkheadMaxConcurrent}
                  </p>
                </div>
                <div className="bg-canvas p-2.5 rounded-lg border border-moss/20">
                  <span className="text-titanium text-[10px]">Available Permits</span>
                  <p className="font-bold text-emerald mt-0.5">
                    {activeSummary.bulkheadAvailablePermits}
                  </p>
                </div>
                <div className="bg-canvas p-2.5 rounded-lg border border-moss/20">
                  <span className="text-titanium text-[10px]">Rejected Calls</span>
                  <p className="font-bold text-signal-red mt-0.5">
                    {activeSummary.bulkheadRejectedCalls}
                  </p>
                </div>
              </div>
            </div>

            {/* 3. Retry Policy & Timeout Deadline Card */}
            <div className="bg-surface border border-moss/30 rounded-xl p-5 space-y-4">
              <div className="flex items-center justify-between border-b border-moss/20 pb-3">
                <div className="flex items-center gap-2.5">
                  <div className="p-2 rounded-lg bg-emerald/10 border border-emerald/30 text-emerald">
                    <Clock className="w-4 h-4" />
                  </div>
                  <div>
                    <h3 className="font-mono font-bold text-silver-frost text-sm">
                      Retry Budget & Cumulative Deadlines
                    </h3>
                    <p className="text-[11px] font-mono text-titanium">
                      Configured backoff schedule & timeout propagation
                    </p>
                  </div>
                </div>
                <Badge variant="slate">CONFIGURED DEFAULTS</Badge>
              </div>

              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs font-mono">
                <div className="bg-canvas p-3 rounded-lg border border-moss/20">
                  <span className="text-titanium text-[10px]">Max Attempts</span>
                  <p className="font-bold text-silver-frost mt-0.5">
                    {activeDetail?.retry.maxAttempts ?? activeSummary.retryMaxAttempts} attempts
                  </p>
                  <span className="text-[9px] text-titanium">Idempotent GET only</span>
                </div>
                <div className="bg-canvas p-3 rounded-lg border border-moss/20">
                  <span className="text-titanium text-[10px]">Initial Backoff</span>
                  <p className="font-bold text-silver-frost mt-0.5">
                    {activeDetail?.retry.initialBackoffMs ?? 100} ms
                  </p>
                  <span className="text-[9px] text-titanium">Exponential schedule</span>
                </div>
                <div className="bg-canvas p-3 rounded-lg border border-moss/20">
                  <span className="text-titanium text-[10px]">Jitter Factor</span>
                  <p className="font-bold text-silver-frost mt-0.5">
                    {activeDetail?.retry.jitterFactor ?? 0.5} (50%)
                  </p>
                  <span className="text-[9px] text-titanium">Prevents retry sync</span>
                </div>
                <div className="bg-canvas p-3 rounded-lg border border-moss/20">
                  <span className="text-titanium text-[10px]">Cumulative Deadline</span>
                  <p className="font-bold text-emerald mt-0.5">
                    {activeDetail?.retry.maxCumulativeDeadlineMs ?? 5000} ms
                  </p>
                  <span className="text-[9px] text-titanium">Hard outer ceiling</span>
                </div>
              </div>
            </div>
          </div>

          {/* Contextual & Diagnostic Column (1 Col) */}
          <div className="space-y-6">
            {/* Quick Diagnostic Shortcuts */}
            <div className="bg-surface border border-moss/30 rounded-xl p-5 space-y-4">
              <h4 className="font-mono font-bold text-silver-frost text-sm flex items-center gap-2">
                <TrendingDown className="w-4 h-4 text-emerald" />
                Incident Diagnostics
              </h4>
              <p className="text-xs font-mono text-titanium leading-relaxed">
                Connect real resilience telemetry directly to historical usage events and correlation traces.
              </p>

              <div className="space-y-2">
                {onNavigateAnalytics && (
                  <button
                    type="button"
                    onClick={() => onNavigateAnalytics(activeSummary.routeId)}
                    className="w-full flex items-center justify-between p-3 bg-canvas hover:bg-surface-elevated text-silver-frost border border-moss/30 rounded-lg text-xs font-mono transition-colors group"
                  >
                    <span>Inspect 5xx / 503 Route Events</span>
                    <ExternalLink className="w-4 h-4 text-emerald group-hover:translate-x-0.5 transition-transform" />
                  </button>
                )}
              </div>
            </div>

            {/* Architectural Knowledge Callouts */}
            <div className="bg-surface border border-moss/30 rounded-xl p-5 space-y-4">
              <h4 className="font-mono font-bold text-silver-frost text-sm flex items-center gap-2">
                <Info className="w-4 h-4 text-emerald" />
                Reliability Architecture
              </h4>

              <div className="space-y-3 text-xs font-mono text-titanium">
                <div className="bg-canvas/60 p-3 rounded-lg border border-moss/20 space-y-1">
                  <span className="font-bold text-silver-frost flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full bg-signal-red" />
                    Why OPEN State Matters
                  </span>
                  <p className="text-[11px] leading-relaxed">
                    When upstream fails repeatedly, an OPEN breaker fast-rejects requests locally in &lt;25ms. This prevents gateway thread pool starvation and stops hammering a struggling upstream service.
                  </p>
                </div>

                <div className="bg-canvas/60 p-3 rounded-lg border border-moss/20 space-y-1">
                  <span className="font-bold text-silver-frost flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full bg-warning" />
                    What HALF_OPEN Represents
                  </span>
                  <p className="text-[11px] leading-relaxed">
                    After the wait duration expires, the circuit allows a small configured batch of probe requests (e.g. 3). If they succeed, it closes automatically; if they fail, it re-trips to OPEN.
                  </p>
                </div>

                <div className="bg-canvas/60 p-3 rounded-lg border border-moss/20 space-y-1">
                  <span className="font-bold text-silver-frost flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full bg-emerald" />
                    Retry Amplification Safety
                  </span>
                  <p className="text-[11px] leading-relaxed">
                    Retries are strictly restricted to idempotent GET requests. Non-idempotent POSTs fail immediately to prevent duplicate mutations or unintended charges.
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Reset Confirmation Modal */}
      <Modal
        isOpen={showResetModal}
        onClose={() => {
          setShowResetModal(false)
          setResetConfirmation('')
        }}
        title="Reset Route Circuit Breaker"
      >
        <div className="space-y-4">
          <div className="p-3 bg-warning/10 border border-warning/30 rounded-lg flex items-start gap-3">
            <AlertTriangle className="w-5 h-5 text-warning shrink-0 mt-0.5" />
            <div className="text-xs font-mono text-silver-frost space-y-1">
              <p className="font-bold text-warning">Manual Circuit Override Notice</p>
              <p>
                Resetting the circuit breaker immediately forces the state to <strong>CLOSED</strong> and purges all sliding window call metrics. If the upstream service is still down, subsequent requests will experience latency or fail.
              </p>
              <p className="text-titanium text-[10px]">
                This action is audited and logged under your administrator identity.
              </p>
            </div>
          </div>

          <div className="space-y-1.5">
            <label className="text-xs font-mono text-titanium">
              Type <span className="text-emerald font-bold">{activeSummary?.routeId}</span> to confirm:
            </label>
            <input
              type="text"
              value={resetConfirmation}
              onChange={(e) => setResetConfirmation(e.target.value)}
              placeholder={activeSummary?.routeId}
              className="w-full bg-canvas border border-moss/40 rounded-lg px-3 py-2 text-xs font-mono text-silver-frost focus:border-emerald focus:outline-none"
            />
          </div>

          <div className="flex justify-end gap-3 pt-3 border-t border-moss/30">
            <button
              type="button"
              onClick={() => {
                setShowResetModal(false)
                setResetConfirmation('')
              }}
              className="px-4 py-2 bg-canvas hover:bg-surface-elevated text-titanium hover:text-silver-frost text-xs font-mono rounded-lg transition-colors"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleConfirmReset}
              disabled={resetConfirmation !== activeSummary?.routeId || isResetting}
              className="px-4 py-2 bg-signal-red hover:bg-signal-red/80 disabled:opacity-40 disabled:cursor-not-allowed text-white text-xs font-mono font-bold rounded-lg transition-colors flex items-center gap-2"
            >
              <RotateCcw className={`w-3.5 h-3.5 ${isResetting ? 'animate-spin' : ''}`} />
              <span>Confirm Reset to CLOSED</span>
            </button>
          </div>
        </div>
      </Modal>
    </div>
  )
}
