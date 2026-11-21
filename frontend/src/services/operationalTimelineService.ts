import { OperationalTimelineEvent, OperationalEventSeverity } from '@/types/resilience'
import { RouteResilienceSummary } from '@/types/health'
import { ChaosExperiment } from '@/types/chaos'
import { PolicyActivationAuditDto } from '@/types/policy'
import { UsageEventDetailDto } from '@/types/analytics'

export const operationalTimelineService = {
  /**
   * Derives operational events strictly from actual telemetry, audit logs,
   * resilience state, and chaos experiment executions.
   *
   * Explicit Severity Derivation Rules:
   * - CRITICAL: Circuit breaker in OPEN state, or HTTP status >= 500, or failed chaos assertion.
   * - WARNING: Circuit breaker in HALF_OPEN, HTTP status 429 (rate-limited), HTTP 403 (policy-denied), or failure rate > 10%.
   * - RECOVERY: Circuit breaker reset, policy rollback, or completed post-fault recovery.
   * - INFO: Normal 2xx traffic, policy activation, or scheduled experiment.
   */
  deriveEvents(
    resilienceData: RouteResilienceSummary[] | null,
    chaosExperiments: ChaosExperiment[] | null,
    policyAudits: PolicyActivationAuditDto[] | null,
    usageEvents: UsageEventDetailDto[] | null
  ): OperationalTimelineEvent[] {
    const events: OperationalTimelineEvent[] = []

    // 1. Resilience & Circuit Breaker Events
    if (resilienceData) {
      for (const route of resilienceData) {
        if (route.circuitBreakerState === 'OPEN') {
          events.push({
            id: `cb-open-${route.routeId}`,
            timestamp: new Date().toISOString(),
            category: 'CIRCUIT_BREAKER',
            source: 'ResilienceEngine',
            routeId: route.routeId,
            severity: 'CRITICAL',
            derivedSeverityRule: 'Rule: Circuit breaker in OPEN state fast-rejecting traffic (HTTP 503)',
            headline: `Circuit Breaker Tripped (OPEN): ${route.routeId}`,
            details: `Failure rate reached ${route.failureRate.toFixed(1)}%. Total ${route.rejectedCalls} calls fast-rejected locally.`,
          })
        } else if (route.circuitBreakerState === 'HALF_OPEN') {
          events.push({
            id: `cb-half-open-${route.routeId}`,
            timestamp: new Date().toISOString(),
            category: 'CIRCUIT_BREAKER',
            source: 'ResilienceEngine',
            routeId: route.routeId,
            severity: 'WARNING',
            derivedSeverityRule: 'Rule: Circuit breaker in HALF_OPEN state testing probe calls',
            headline: `Circuit Breaker Probing (HALF_OPEN): ${route.routeId}`,
            details: 'Probe requests are evaluating upstream service availability before restoring closed state.',
          })
        }

        if (route.bulkheadRejectedCalls > 0) {
          events.push({
            id: `bh-rejected-${route.routeId}`,
            timestamp: new Date().toISOString(),
            category: 'BULKHEAD',
            source: 'RouteBulkhead',
            routeId: route.routeId,
            severity: 'WARNING',
            derivedSeverityRule: 'Rule: Bulkhead concurrency limit exceeded; fast-rejecting surplus calls',
            headline: `Bulkhead Saturation: ${route.routeId}`,
            details: `${route.bulkheadRejectedCalls} calls rejected because max concurrency (${route.bulkheadMaxConcurrent}) was reached.`,
          })
        }
      }
    }

    // 2. Policy Governance Audit Events
    if (policyAudits) {
      for (const audit of policyAudits) {
        const isRollback = audit.action === 'ROLLED_BACK'
        events.push({
          id: `audit-${audit.id}`,
          timestamp: audit.createdAt,
          category: 'POLICY_DENIAL',
          source: 'PolicyAdminController',
          severity: isRollback ? 'RECOVERY' : 'INFO',
          derivedSeverityRule: isRollback
            ? 'Rule: Policy rollback reinstates previous validated active version'
            : 'Rule: Administrative policy activation event',
          headline: `Policy ${audit.action}: Version ${audit.versionNumber}`,
          details: `Target: Version ${audit.versionNumber}${audit.previousVersionNumber ? ` (from v${audit.previousVersionNumber})` : ''}. Reason: ${audit.reason || 'None specified'}. Actor: ${audit.actor}.`,
        })
      }
    }

    // 3. Chaos Experiment Execution Events
    if (chaosExperiments) {
      for (const exp of chaosExperiments) {
        const hasFailedAssertion = exp.assertions.some((a) => a.outcome === 'FAIL')
        const severity: OperationalEventSeverity = hasFailedAssertion ? 'CRITICAL' : 'INFO'

        events.push({
          id: `chaos-${exp.id}`,
          timestamp: exp.completedAt || exp.startedAt || exp.createdAt,
          category: 'CHAOS_EXPERIMENT',
          source: exp.provenance === 'LIVE_LOCAL_EXPERIMENT' ? 'ChaosExperimentEngine' : 'BenchmarkSuite (Phase 5D)',
          routeId: exp.metrics?.targetService || String(exp.target),
          severity,
          derivedSeverityRule: hasFailedAssertion
            ? 'Rule: Controlled experiment reported 1 or more failed reliability assertions'
            : 'Rule: Controlled experiment completed with verified resilience assertions',
          headline: `Chaos Experiment: ${exp.scenarioType}`,
          details: `Fault: ${exp.faultType}. State: ${exp.state}. Assertions: ${exp.assertions.filter((a) => a.outcome === 'PASS').length}/${exp.assertions.length} passed. Recovery: ${exp.metrics?.recoveryDurationMs ?? 0} ms.`,
        })
      }
    }

    // 4. Usage Telemetry Events
    if (usageEvents) {
      for (const evt of usageEvents) {
        let severity: OperationalEventSeverity = 'INFO'
        let rule = 'Rule: HTTP 2xx successful request'

        if (evt.httpStatus >= 500) {
          severity = 'CRITICAL'
          rule = 'Rule: Upstream or gateway HTTP 5xx failure'
        } else if (evt.httpStatus === 429) {
          severity = 'WARNING'
          rule = 'Rule: HTTP 429 Rate limit threshold reached'
        } else if (evt.httpStatus === 403) {
          severity = 'WARNING'
          rule = 'Rule: HTTP 403 Access or policy scope denied'
        }

        events.push({
          id: `usage-${evt.id}`,
          timestamp: typeof evt.timestamp === 'string' ? evt.timestamp : String(evt.timestamp || new Date().toISOString()),
          category: evt.httpStatus >= 500 ? 'UPSTREAM_ERROR' : 'POLICY_DENIAL',
          source: 'GatewayHttpProxy',
          routeId: evt.endpointPath,
          severity,
          derivedSeverityRule: rule,
          headline: `${evt.httpMethod} ${evt.endpointPath} -> ${evt.httpStatus}`,
          details: `Latency: ${evt.latencyMs} ms. Key Prefix: ${evt.keyPrefixSnapshot || 'None'}. Outcome: ${evt.outcomeCategory}. Plan: ${evt.rateLimitPlan}.`,
          correlationId: evt.correlationId ?? undefined,
          rawStatus: evt.httpStatus,
        })
      }
    }

    // Sort chronologically descending
    return events.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())
  },
}
