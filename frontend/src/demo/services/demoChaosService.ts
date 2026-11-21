import { ChaosExperiment, ChaosRunRequest } from '@/types/chaos'
import {
  getDemoHistoricalExperiments,
  runDemoExperiment,
} from '../data/demoChaosData'
import { HISTORICAL_CHAOS_RESULTS } from '@/services/chaosService'

export const demoChaosService = {
  async getLiveExperiments(): Promise<ChaosExperiment[]> {
    // In demo mode, live cluster experiments are not active
    return []
  },

  getHistoricalExperiments(): ChaosExperiment[] {
    const session = getDemoHistoricalExperiments()
    if (session.length > 0) {
      return [...session, ...HISTORICAL_CHAOS_RESULTS]
    }
    return HISTORICAL_CHAOS_RESULTS
  },

  async runExperiment(request: ChaosRunRequest): Promise<ChaosExperiment> {
    return runDemoExperiment(request)
  },
}
