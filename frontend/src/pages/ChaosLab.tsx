import React, { useState, useEffect, useCallback } from 'react'
import { useAuth } from '@/context/AuthContext'
import { useToast } from '@/context/ToastContext'
import {
  chaosService,
  CHAOS_SCENARIO_DEFINITIONS,
} from '@/services/chaosService'
import {
  ChaosScenarioType,
  ChaosExperiment,
} from '@/types/chaos'
import { Badge } from '@/components/ui/Badge'
import { Modal } from '@/components/ui/Modal'
import {
  Flame,
  ShieldCheck,
  AlertTriangle,
  Play,
  Activity,
  Zap,
  Lock,
  Info,
  Calendar,
  Check,
} from 'lucide-react'

export const ChaosLab: React.FC = () => {
  const { admin, canMutate } = useAuth()
  const { showToast } = useToast()

  const [selectedScenarioType, setSelectedScenarioType] = useState<ChaosScenarioType>('ORDERS_OUTAGE')
  const [experiments, setExperiments] = useState<ChaosExperiment[]>(() =>
    chaosService.getHistoricalExperiments()
  )
  const [selectedExperiment, setSelectedExperiment] = useState<ChaosExperiment | null>(() => {
    const historical = chaosService.getHistoricalExperiments()
    return historical.find((e) => e.scenarioType === 'ORDERS_OUTAGE') || historical[0] || null
  })
  const [isRunning, setIsRunning] = useState<boolean>(false)
  const [showRunModal, setShowRunModal] = useState<boolean>(false)
  const [isLiveBackendAvailable, setIsLiveBackendAvailable] = useState<boolean>(false)

  const isAdmin = canMutate() || admin?.role === 'ROLE_SUPER_ADMIN' || admin?.role === 'ROLE_ADMIN'
  const activeScenarioDef = CHAOS_SCENARIO_DEFINITIONS[selectedScenarioType]

  // Fetch experiments (Live + Historical)
  const loadExperiments = useCallback(async () => {
    try {
      const live = await chaosService.getLiveExperiments()
      const historical = chaosService.getHistoricalExperiments()

      if (live.length > 0) {
        setIsLiveBackendAvailable(true)
        const combined = [...live, ...historical]
        setExperiments(combined)
        const match = combined.find((e) => e.scenarioType === selectedScenarioType)
        setSelectedExperiment(match || combined[0])
      } else {
        setIsLiveBackendAvailable(false)
        setExperiments(historical)
        const match = historical.find((e) => e.scenarioType === selectedScenarioType)
        setSelectedExperiment(match || historical[0])
      }
    } catch {
      setIsLiveBackendAvailable(false)
      const historical = chaosService.getHistoricalExperiments()
      setExperiments(historical)
      const match = historical.find((e) => e.scenarioType === selectedScenarioType)
      setSelectedExperiment(match || historical[0])
    }
  }, [selectedScenarioType])

  useEffect(() => {
    let isMounted = true
    const timer = setTimeout(() => {
      if (isMounted) {
        loadExperiments()
      }
    }, 0)

    return () => {
      isMounted = false
      clearTimeout(timer)
    }
  }, [loadExperiments])

  // Select a scenario
  const handleSelectScenario = (type: ChaosScenarioType) => {
    setSelectedScenarioType(type)
    const match = experiments.find((e) => e.scenarioType === type)
    setSelectedExperiment(match || null)
  }

  // Execute Controlled Experiment
  const handleExecuteExperiment = async () => {
    setIsRunning(true)
    try {
      const result = await chaosService.runExperiment({
        scenario: selectedScenarioType,
        parameters: {},
      })
      showToast(
        'success',
        'Chaos Experiment Executed',
        `Scenario ${selectedScenarioType} completed with state ${result.state}.`
      )
      setShowRunModal(false)
      await loadExperiments()
    } catch (err: unknown) {
      const errorMessage =
        err && typeof err === 'object' && 'response' in err
          ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
          : undefined
      showToast(
        'error',
        'Execution Failed or Blocked',
        errorMessage || 'Production guard or environment security policy rejected the experiment.'
      )
    } finally {
      setIsRunning(false)
    }
  }

  return (
    <div className="space-y-6">
      {/* Platform Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-moss/40 pb-5">
        <div>
          <div className="flex items-center gap-2">
            <span className="text-[10px] font-mono uppercase tracking-widest text-warning font-semibold px-2 py-0.5 rounded bg-warning/10 border border-warning/30">
              Resilience Validation & Chaos Lab
            </span>
            <span className="text-xs text-titanium font-mono">Phase 5D Benchmark Evidence</span>
          </div>
          <h1 className="text-2xl font-bold font-mono text-silver-frost tracking-tight mt-1 flex items-center gap-2">
            <Flame className="w-6 h-6 text-warning" />
            Controlled Chaos Engineering Laboratory
          </h1>
          <p className="text-xs font-mono text-titanium mt-0.5">
            Empirical verification of circuit breakers, bulkhead isolation, fail-closed security, and retry bounds.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <Badge variant={isLiveBackendAvailable ? 'success' : 'slate'}>
            {isLiveBackendAvailable ? 'LOCAL DEV PROFILE ACTIVE' : 'PRODUCTION LOCKOUT: SAFE MODE'}
          </Badge>
        </div>
      </div>

      {/* Strict Production Safety Guard Banner */}
      <div className="bg-surface border border-moss/30 rounded-xl p-4 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
        <div className="flex items-start gap-3">
          <div className="p-2.5 rounded-lg bg-emerald/10 border border-emerald/30 text-emerald shrink-0 mt-0.5">
            <ShieldCheck className="w-5 h-5" />
          </div>
          <div className="space-y-1">
            <h4 className="font-mono font-bold text-silver-frost text-sm flex items-center gap-2">
              Strict Non-Production Blast Radius Guard
            </h4>
            <p className="text-xs font-mono text-titanium leading-relaxed max-w-3xl">
              Gatekeeper enforces hard compile-time and runtime isolation: <code className="text-emerald">{'@Profile({"local", "test", "dev"})'}</code> prevents <code className="text-silver-frost">ChaosAdminController</code> from mounting in production, and <code className="text-silver-frost">ChaosSafetyGuard</code> rejects external hosts, enforces a 500-request ceiling, and mandates automatic post-fault resource cleanup.
            </p>
          </div>
        </div>

        {isAdmin && isLiveBackendAvailable ? (
          <button
            type="button"
            onClick={() => setShowRunModal(true)}
            disabled={isRunning}
            className="shrink-0 flex items-center gap-2 px-4 py-2 bg-warning/10 hover:bg-warning/20 text-warning border border-warning/40 rounded-lg text-xs font-mono font-bold transition-colors disabled:opacity-50"
          >
            <Play className={`w-3.5 h-3.5 ${isRunning ? 'animate-spin' : ''}`} />
            <span>Execute Experiment</span>
          </button>
        ) : (
          <div className="shrink-0 flex items-center gap-2 px-3 py-2 bg-canvas border border-moss/30 rounded-lg text-xs font-mono text-titanium">
            <Lock className="w-3.5 h-3.5 text-warning" />
            <span>Execution Gated in Prod</span>
          </div>
        )}
      </div>

      {/* Scenario Directory Ribbon */}
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-2">
        {Object.values(CHAOS_SCENARIO_DEFINITIONS).map((def) => {
          const isSelected = def.type === selectedScenarioType
          return (
            <button
              key={def.type}
              type="button"
              onClick={() => handleSelectScenario(def.type)}
              className={`p-3 rounded-xl border text-left font-mono transition-all ${
                isSelected
                  ? 'bg-surface border-emerald text-emerald shadow-emerald-sm'
                  : 'bg-canvas border-moss/30 text-titanium hover:text-silver-frost hover:border-moss/60'
              }`}
            >
              <div className="flex items-center justify-between">
                <span className="text-[10px] text-titanium uppercase tracking-wider">
                  {def.type.replace('_', ' ')}
                </span>
                <span
                  className={`w-2 h-2 rounded-full ${
                    isSelected ? 'bg-emerald' : 'bg-moss/40'
                  }`}
                />
              </div>
              <p className="text-xs font-bold text-silver-frost mt-1 truncate">
                {def.title.split(':')[1]?.trim() || def.title}
              </p>
            </button>
          )
        })}
      </div>

      {/* Main Scenario & Experiment Inspector */}
      {activeScenarioDef && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Column 1 & 2: Experiment Specifications & Verification Assertions */}
          <div className="lg:col-span-2 space-y-6">
            {/* Scenario Blueprint Card */}
            <div className="bg-surface border border-moss/30 rounded-xl p-5 space-y-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 border-b border-moss/20 pb-3">
                <div>
                  <h3 className="font-mono font-bold text-silver-frost text-base">
                    {activeScenarioDef.title}
                  </h3>
                  <p className="text-xs font-mono text-emerald mt-0.5">
                    {activeScenarioDef.subtitle}
                  </p>
                </div>
                {selectedExperiment && (
                  <div className="flex items-center gap-2">
                    <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-moss/20 text-titanium border border-moss/30">
                      ID: {selectedExperiment.id}
                    </span>
                    <Badge
                      variant={
                        selectedExperiment.provenance === 'LIVE_LOCAL_EXPERIMENT'
                          ? 'success'
                          : 'purple'
                      }
                    >
                      {selectedExperiment.provenance === 'LIVE_LOCAL_EXPERIMENT'
                        ? 'LIVE LOCAL EXECUTION'
                        : 'HISTORICAL TEST ARTIFACT'}
                    </Badge>
                  </div>
                )}
              </div>

              <p className="text-xs font-mono text-silver-frost leading-relaxed">
                {activeScenarioDef.description}
              </p>

              {/* Technical Specifications Grid */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs font-mono">
                <div className="bg-canvas p-3 rounded-lg border border-moss/20 space-y-1">
                  <span className="text-titanium text-[10px] uppercase">Target Service</span>
                  <p className="font-bold text-silver-frost">{activeScenarioDef.target}</p>
                </div>
                <div className="bg-canvas p-3 rounded-lg border border-moss/20 space-y-1">
                  <span className="text-titanium text-[10px] uppercase">Injected Fault Mode</span>
                  <p className="font-bold text-signal-red">{activeScenarioDef.faultType}</p>
                </div>
                <div className="bg-canvas p-3 rounded-lg border border-moss/20 space-y-1 sm:col-span-2">
                  <span className="text-titanium text-[10px] uppercase">Resilience Mechanism Tested</span>
                  <p className="font-bold text-emerald">{activeScenarioDef.resilienceMechanism}</p>
                </div>
              </div>

              {/* Safeguards List */}
              <div className="bg-canvas/40 p-3 rounded-lg border border-moss/20 space-y-1.5">
                <span className="text-[10px] font-mono uppercase text-titanium flex items-center gap-1">
                  <ShieldCheck className="w-3 h-3 text-emerald" />
                  Active Blast Radius Containment Safeguards
                </span>
                <div className="flex flex-wrap gap-2 pt-1">
                  {activeScenarioDef.safeguards.map((sg, idx) => (
                    <span
                      key={idx}
                      className="text-[11px] font-mono px-2 py-0.5 rounded bg-moss/20 text-silver-frost border border-moss/30"
                    >
                      {sg}
                    </span>
                  ))}
                </div>
              </div>
            </div>

            {/* Empirical Assertions Table */}
            <div className="bg-surface border border-moss/30 rounded-xl p-5 space-y-4">
              <div className="flex items-center justify-between border-b border-moss/20 pb-3">
                <div className="flex items-center gap-2.5">
                  <Activity className="w-4 h-4 text-emerald" />
                  <h4 className="font-mono font-bold text-silver-frost text-sm">
                    Verified Empirical Assertions
                  </h4>
                </div>
                <span className="text-xs font-mono text-titanium">
                  {selectedExperiment?.assertions.filter((a) => a.outcome === 'PASS').length ?? 0} /{' '}
                  {selectedExperiment?.assertions.length ?? 0} Passed
                </span>
              </div>

              <div className="space-y-3">
                {selectedExperiment?.assertions.map((assertion, idx) => (
                  <div
                    key={idx}
                    className="bg-canvas border border-moss/20 rounded-lg p-3.5 space-y-2"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <span className="font-mono font-bold text-silver-frost text-xs">
                          {assertion.name}
                        </span>
                        <p className="text-[11px] font-mono text-titanium mt-0.5">
                          {assertion.description}
                        </p>
                      </div>
                      <Badge
                        variant={
                          assertion.outcome === 'PASS'
                            ? 'success'
                            : assertion.outcome === 'FAIL'
                            ? 'danger'
                            : 'slate'
                        }
                      >
                        {assertion.outcome}
                      </Badge>
                    </div>

                    {/* Observed Evidence Details */}
                    <div className="bg-surface-elevated/40 p-2.5 rounded border border-moss/20 text-[11px] font-mono text-emerald flex items-center gap-2">
                      <Check className="w-3.5 h-3.5 shrink-0" />
                      <span>{assertion.observedDetails}</span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* Column 3: Measured Telemetry & Recovery Assessment */}
          <div className="space-y-6">
            {/* Measured Metrics Card */}
            <div className="bg-surface border border-moss/30 rounded-xl p-5 space-y-4">
              <h4 className="font-mono font-bold text-silver-frost text-sm flex items-center gap-2 border-b border-moss/20 pb-3">
                <Zap className="w-4 h-4 text-emerald" />
                Measured Telemetry Output
              </h4>

              {selectedExperiment?.metrics ? (
                <div className="space-y-3 text-xs font-mono">
                  <div className="flex justify-between py-1 border-b border-moss/20">
                    <span className="text-titanium">Total Requests</span>
                    <span className="text-silver-frost font-bold">
                      {selectedExperiment.metrics.totalRequests}
                    </span>
                  </div>
                  <div className="flex justify-between py-1 border-b border-moss/20">
                    <span className="text-titanium">Success vs Failed</span>
                    <span className="text-silver-frost">
                      {selectedExperiment.metrics.successfulRequests} OK /{' '}
                      <span className="text-signal-red">
                        {selectedExperiment.metrics.failedRequests} Fail
                      </span>
                    </span>
                  </div>

                  {/* Status Distribution */}
                  <div className="py-1 border-b border-moss/20 space-y-1">
                    <span className="text-titanium text-[10px] uppercase">HTTP Status Breakdown</span>
                    <div className="flex flex-wrap gap-2 pt-0.5">
                      {Object.entries(selectedExperiment.metrics.statusDistribution).map(([code, count]) => (
                        <span
                          key={code}
                          className={`px-2 py-0.5 rounded text-[10px] font-bold border ${
                            code === '200'
                              ? 'bg-emerald/10 border-emerald/30 text-emerald'
                              : 'bg-signal-red/10 border-signal-red/30 text-signal-red'
                          }`}
                        >
                          {code}: {count}
                        </span>
                      ))}
                    </div>
                  </div>

                  {/* Latency Quantiles */}
                  <div className="grid grid-cols-3 gap-2 py-1 border-b border-moss/20 text-center">
                    <div className="bg-canvas p-2 rounded border border-moss/20">
                      <span className="text-[10px] text-titanium">P50 Latency</span>
                      <p className="font-bold text-silver-frost mt-0.5">
                        {selectedExperiment.metrics.gatewayLatencyP50Ms} ms
                      </p>
                    </div>
                    <div className="bg-canvas p-2 rounded border border-moss/20">
                      <span className="text-[10px] text-titanium">P95 Latency</span>
                      <p className="font-bold text-silver-frost mt-0.5">
                        {selectedExperiment.metrics.gatewayLatencyP95Ms} ms
                      </p>
                    </div>
                    <div className="bg-canvas p-2 rounded border border-moss/20">
                      <span className="text-[10px] text-titanium">P99 Latency</span>
                      <p className="font-bold text-silver-frost mt-0.5">
                        {selectedExperiment.metrics.gatewayLatencyP99Ms} ms
                      </p>
                    </div>
                  </div>

                  {/* Circuit Transitions */}
                  {selectedExperiment.metrics.circuitBreakerTransitions.length > 0 && (
                    <div className="py-1 border-b border-moss/20 space-y-1">
                      <span className="text-titanium text-[10px] uppercase">State Transitions</span>
                      <div className="space-y-1">
                        {selectedExperiment.metrics.circuitBreakerTransitions.map((t, i) => (
                          <div
                            key={i}
                            className="text-[10px] text-silver-frost bg-canvas p-1.5 rounded border border-moss/20"
                          >
                            {t}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  <div className="flex justify-between py-1 border-b border-moss/20">
                    <span className="text-titanium">Measured Recovery</span>
                    <span className="text-emerald font-bold">
                      {selectedExperiment.metrics.recoveryDurationMs} ms
                    </span>
                  </div>

                  <div className="flex justify-between py-1 border-b border-moss/20">
                    <span className="text-titanium">Sibling Route Availability</span>
                    <span className="text-emerald font-bold">
                      {selectedExperiment.metrics.healthyServiceAvailability.toFixed(1)}%
                    </span>
                  </div>

                  <div className="flex justify-between py-1">
                    <span className="text-titanium">Post-Fault Cleanup</span>
                    <span className="text-emerald font-bold">
                      {selectedExperiment.metrics.cleanupStatus}
                    </span>
                  </div>
                </div>
              ) : (
                <p className="text-xs font-mono text-titanium">No detailed metrics recorded.</p>
              )}
            </div>

            {/* Historical Test Provenance Callout */}
            <div className="bg-surface border border-moss/30 rounded-xl p-5 space-y-3">
              <h4 className="font-mono font-bold text-silver-frost text-sm flex items-center gap-2">
                <Info className="w-4 h-4 text-emerald" />
                Evidence Provenance
              </h4>
              <p className="text-xs font-mono text-titanium leading-relaxed">
                This experiment was executed and verified during Gatekeeper's Phase 5D resilience benchmark suite. All assertion outcomes and latency distributions reflect genuine measured test outputs recorded under controlled network condition injection.
              </p>
              <div className="text-[10px] font-mono text-titanium pt-2 border-t border-moss/20 flex items-center gap-2">
                <Calendar className="w-3.5 h-3.5 text-emerald" />
                <span>
                  Recorded:{' '}
                  {selectedExperiment?.createdAt
                    ? new Date(selectedExperiment.createdAt).toLocaleDateString()
                    : 'Historical Phase 5D'}
                </span>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Execute Experiment Modal */}
      <Modal
        isOpen={showRunModal}
        onClose={() => setShowRunModal(false)}
        title="Execute Controlled Chaos Experiment"
      >
        <div className="space-y-4">
          <div className="p-3 bg-warning/10 border border-warning/30 rounded-lg flex items-start gap-3">
            <AlertTriangle className="w-5 h-5 text-warning shrink-0 mt-0.5" />
            <div className="text-xs font-mono text-silver-frost space-y-1">
              <p className="font-bold text-warning">Controlled Fault Injection Confirmation</p>
              <p>
                You are about to execute scenario <strong>{activeScenarioDef?.title}</strong> against local test target <code>{activeScenarioDef?.target}</code>.
              </p>
              <p className="text-titanium text-[10px]">
                The experiment is subject to the 60s timeout ceiling and will automatically restore state on completion.
              </p>
            </div>
          </div>

          <div className="bg-canvas p-3 rounded-lg border border-moss/30 text-xs font-mono space-y-1">
            <p className="text-titanium">Expected Behavior:</p>
            <p className="text-silver-frost">{activeScenarioDef?.expectedBehavior}</p>
          </div>

          <div className="flex justify-end gap-3 pt-3 border-t border-moss/30">
            <button
              type="button"
              onClick={() => setShowRunModal(false)}
              className="px-4 py-2 bg-canvas hover:bg-surface-elevated text-titanium hover:text-silver-frost text-xs font-mono rounded-lg transition-colors"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleExecuteExperiment}
              disabled={isRunning}
              className="px-4 py-2 bg-warning hover:bg-warning/80 text-black text-xs font-mono font-bold rounded-lg transition-colors flex items-center gap-2"
            >
              <Play className={`w-3.5 h-3.5 ${isRunning ? 'animate-spin' : ''}`} />
              <span>{isRunning ? 'Running Experiment...' : 'Confirm & Execute'}</span>
            </button>
          </div>
        </div>
      </Modal>
    </div>
  )
}
