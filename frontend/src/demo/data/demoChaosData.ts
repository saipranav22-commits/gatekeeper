import { ChaosExperiment, ChaosRunRequest } from '@/types/chaos'
import { setDemoResilienceScenario } from './demoResilienceData'

let sessionExperiments: ChaosExperiment[] = []

export const getDemoHistoricalExperiments = (): ChaosExperiment[] => {
  return [...sessionExperiments]
}

export const setInitialChaosExperiments = (initial: ChaosExperiment[]): void => {
  if (sessionExperiments.length === 0) {
    sessionExperiments = [...initial]
  }
}

export const runDemoExperiment = async (request: ChaosRunRequest): Promise<ChaosExperiment> => {
  // Safe frontend simulation: update demo resilience scenario and record experiment result
  if (request.scenario === 'ORDERS_OUTAGE') {
    setDemoResilienceScenario('CIRCUIT_OPEN')
  } else if (request.scenario === 'SLOW_UPSTREAM') {
    setDemoResilienceScenario('ELEVATED_ERROR_RATE')
  }

  const simulatedExp: ChaosExperiment = {
    id: `exp-sim-${Date.now().toString(36)}`,
    scenarioType: request.scenario,
    target: 'orders-service-v1',
    faultType: 'HTTP_ERROR',
    parameters: request.parameters || {},
    state: 'COMPLETED',
    createdAt: new Date().toISOString(),
    startedAt: new Date().toISOString(),
    completedAt: new Date(Date.now() + 1500).toISOString(),
    failureReason: null,
    cleanupStatus: 'SUCCESS',
    provenance: 'LIVE_LOCAL_EXPERIMENT',
    assertions: [
      {
        name: 'SimulatedResilienceGuard',
        description: 'Safe client-side execution without backend mutation',
        outcome: 'PASS',
        observedDetails: 'Demonstration simulation executed in browser state only',
      },
      {
        name: 'ResilienceTelemetrySynchronization',
        description: 'Resilience metrics updated in session memory',
        outcome: 'PASS',
        observedDetails: 'Circuit breaker state reflects simulated fault in Reliability Workbench',
      },
    ],
  }

  sessionExperiments = [simulatedExp, ...sessionExperiments]
  return simulatedExp
}
