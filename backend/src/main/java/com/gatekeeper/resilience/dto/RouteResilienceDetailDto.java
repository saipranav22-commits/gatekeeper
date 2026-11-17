package com.gatekeeper.resilience.dto;

/**
 * Detailed DTO containing full configuration and runtime metrics for circuit breaker,
 * bulkhead, and retry policy on a specific gateway route.
 */
public record RouteResilienceDetailDto(
    String routeId,
    CircuitBreakerDetail circuitBreaker,
    BulkheadDetail bulkhead,
    RetryDetail retry
) {
    public record CircuitBreakerDetail(
        String state,
        int failureRateThreshold,
        int slowCallRateThreshold,
        long slowCallDurationMs,
        int slidingWindowSize,
        int minimumNumberOfCalls,
        long waitDurationInOpenStateMs,
        int permittedNumberOfCallsInHalfOpenState,
        long totalCalls,
        long successfulCalls,
        long failedCalls,
        long slowCalls,
        float failureRatePercent,
        float slowCallRatePercent,
        long totalRejectedCalls
    ) {}

    public record BulkheadDetail(
        int maxConcurrentCalls,
        int availablePermits,
        int inFlightCalls,
        long rejectedCalls
    ) {}

    public record RetryDetail(
        int maxAttempts,
        long initialBackoffMs,
        double backoffMultiplier,
        double jitterFactor,
        long maxCumulativeDeadlineMs
    ) {}
}
