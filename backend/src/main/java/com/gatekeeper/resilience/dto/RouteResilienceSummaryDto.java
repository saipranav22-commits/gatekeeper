package com.gatekeeper.resilience.dto;

/**
 * Summary DTO of resilience metrics and circuit breaker / bulkhead status for a route.
 */
public record RouteResilienceSummaryDto(
    String routeId,
    String circuitState,
    float failureRatePercent,
    float slowCallRatePercent,
    long totalCalls,
    long failedCalls,
    long slowCalls,
    long circuitRejectedCalls,
    int bulkheadMaxConcurrent,
    int bulkheadAvailablePermits,
    int bulkheadInFlightCalls,
    long bulkheadRejectedCalls,
    int retryMaxAttempts
) {}
