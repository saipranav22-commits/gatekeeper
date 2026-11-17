package com.gatekeeper.resilience.circuitbreaker;

/**
 * Snapshot of metrics collected by a RouteCircuitBreaker instance.
 */
public record CircuitBreakerMetrics(
    long totalCalls,
    long successfulCalls,
    long failedCalls,
    long slowCalls,
    float failureRate,
    float slowCallRate,
    long rejectedCalls
) {}
