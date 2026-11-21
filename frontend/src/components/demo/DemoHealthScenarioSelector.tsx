import React from 'react'
import { useGatewayHealth, DemoHealthState } from '@/context/GatewayHealthContext'
import { Radio, RotateCcw } from 'lucide-react'

interface ScenarioOption {
  key: DemoHealthState
  label: string
  color: string
  dotClass: string
  activeBorderClass: string
  activeBgClass: string
  activeTextClass: string
  description: string
}

const SCENARIOS: ScenarioOption[] = [
  {
    key: 'HEALTHY',
    label: 'HEALTHY',
    color: '#31E99A',
    dotClass: 'bg-emerald shadow-emerald-sm',
    activeBorderClass: 'border-emerald/60',
    activeBgClass: 'bg-emerald/15',
    activeTextClass: 'text-emerald',
    description: 'All upstream routes operational. Resilience4j circuit breakers CLOSED. Nominal latency (<15ms) and <0.1% baseline error rate.',
  },
  {
    key: 'DEGRADED',
    label: 'DEGRADED',
    color: '#E9B35A',
    dotClass: 'bg-warning-amber',
    activeBorderClass: 'border-warning-amber/60',
    activeBgClass: 'bg-warning-amber/15',
    activeTextClass: 'text-warning-amber',
    description: 'Orders Service experiencing elevated latency (24.1% slow calls) and 38.6% failure rate. Concurrency bulkhead shedding traffic.',
  },
  {
    key: 'UNHEALTHY',
    label: 'UNHEALTHY',
    color: '#F05460',
    dotClass: 'bg-signal-red',
    activeBorderClass: 'border-signal-red/60',
    activeBgClass: 'bg-signal-red/15',
    activeTextClass: 'text-signal-red',
    description: 'Critical simulated outage on Orders Service. Circuit breaker OPEN (fail-fast active). Requests rejected at gateway edge.',
  },
  {
    key: 'RECOVERING',
    label: 'RECOVERING',
    color: '#38BDF8',
    dotClass: 'bg-sky-400',
    activeBorderClass: 'border-sky-500/60',
    activeBgClass: 'bg-sky-500/15',
    activeTextClass: 'text-sky-400',
    description: 'Orders Service connectivity restored. Circuit breaker in HALF_OPEN trial state, probing upstream stability with 4.8% error rate.',
  },
]

export const DemoHealthScenarioSelector: React.FC = () => {
  const { demoScenario, setDemoScenario, resetDemoScenario } = useGatewayHealth()

  const currentOption = SCENARIOS.find((s) => s.key === demoScenario) || SCENARIOS[0]

  return (
    <div
      data-testid="demo-scenario-panel"
      className="bg-surface rounded-xl border border-moss/40 p-4 shadow-card-dark transition-colors"
      role="region"
      aria-label="Demo Infrastructure Health Scenario Selector"
    >
      {/* Header bar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pb-3 border-b border-moss-subtle">
        <div className="flex items-center gap-2">
          <Radio className="w-4 h-4 text-emerald animate-pulse" />
          <span className="text-xs font-mono font-bold uppercase tracking-wider text-silver-frost">
            Demo Scenario
          </span>
          <span className="text-[10px] text-titanium-muted font-mono">
            — Simulated infrastructure state
          </span>
        </div>

        <div className="flex items-center gap-2">
          <button
            type="button"
            data-testid="demo-scenario-reset"
            onClick={() => resetDemoScenario()}
            title="Reset to default healthy demo state"
            className="flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-mono text-titanium hover:text-silver-frost bg-surface-elevated border border-moss/40 hover:border-emerald/40 rounded-lg transition-colors cursor-pointer"
          >
            <RotateCcw className="w-3 h-3 text-titanium-muted" />
            <span>Reset Demo State</span>
          </button>
        </div>
      </div>

      {/* Segmented Scenario Control */}
      <div className="pt-3">
        <div
          role="radiogroup"
          aria-label="Select Infrastructure Health Scenario"
          className="grid grid-cols-2 sm:grid-cols-4 gap-2"
        >
          {SCENARIOS.map((opt) => {
            const isSelected = demoScenario === opt.key
            return (
              <button
                key={opt.key}
                type="button"
                role="radio"
                aria-checked={isSelected}
                data-testid={`demo-scenario-${opt.key.toLowerCase()}`}
                onClick={() => setDemoScenario(opt.key)}
                className={`flex items-center justify-center gap-2 py-2 px-3 rounded-lg text-xs font-mono font-bold tracking-wider uppercase transition-all cursor-pointer border ${
                  isSelected
                    ? `${opt.activeBgClass} ${opt.activeBorderClass} ${opt.activeTextClass} shadow-xs ring-1 ring-inset ${opt.activeBorderClass}`
                    : 'bg-surface-elevated/40 border-moss/30 text-titanium hover:text-silver-frost hover:border-moss/70'
                }`}
              >
                <span
                  className={`w-2 h-2 rounded-full transition-transform ${opt.dotClass} ${
                    isSelected ? 'scale-125' : 'opacity-60'
                  }`}
                />
                <span>{opt.label}</span>
              </button>
            )
          })}
        </div>

        {/* Current Scenario Active Explanation Banner */}
        <div className="mt-3 p-3 bg-surface-elevated/50 rounded-lg border border-moss-subtle flex items-start gap-2.5 text-xs">
          <div className="mt-0.5 shrink-0">
            <span
              className={`inline-block w-2.5 h-2.5 rounded-full ${currentOption.dotClass}`}
            />
          </div>
          <div className="flex-1 space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className={`font-mono font-bold ${currentOption.activeTextClass}`}>
                [{currentOption.label} SCENARIO ACTIVE]
              </span>
              <span className="text-titanium text-[11px] font-sans">
                {currentOption.description}
              </span>
            </div>
            <div className="text-[10px] text-titanium-muted font-mono">
              Simulated frontend telemetry only. No real microservices or cloud resources affected.
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
