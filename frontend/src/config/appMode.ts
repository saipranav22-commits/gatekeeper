/**
 * Gatekeeper Application Execution Mode
 *
 * Distinctly separates:
 * - 'live': Genuine backend integration with Spring Boot, PostgreSQL, and Redis.
 * - 'demo': Public recruiter demonstration mode using coherent in-memory fixtures,
 *           deterministic policy simulation, and zero backend dependencies.
 */

export type AppMode = 'live' | 'demo'

let modeOverride: AppMode | null = null

/**
 * Allows automated tests to explicitly verify both LIVE and DEMO mode invariants
 * without altering global Vite bundler state.
 */
export const setAppModeOverrideForTesting = (mode: AppMode | null): void => {
  modeOverride = mode
}

/**
 * Returns the current active application execution mode.
 */
export const getAppMode = (): AppMode => {
  if (modeOverride !== null) {
    return modeOverride
  }
  if (import.meta.env.VITE_APP_MODE === 'demo') {
    return 'demo'
  }
  return 'live'
}

/**
 * Returns true if the application is running in the standalone demonstration mode.
 */
export const isDemoMode = (): boolean => {
  return getAppMode() === 'demo'
}
