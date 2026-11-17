package com.gatekeeper.resilience.circuitbreaker;

import java.util.Objects;

/**
 * Immutable configuration for a RouteCircuitBreaker instance.
 */
public record CircuitBreakerConfig(
    int failureRateThreshold,
    int slowCallRateThreshold,
    long slowCallDurationMs,
    int slidingWindowSize,
    int minimumNumberOfCalls,
    long waitDurationInOpenStateMs,
    int permittedNumberOfCallsInHalfOpenState
) {
    public CircuitBreakerConfig {
        if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
            failureRateThreshold = 50;
        }
        if (slowCallRateThreshold <= 0 || slowCallRateThreshold > 100) {
            slowCallRateThreshold = 50;
        }
        if (slowCallDurationMs <= 0) {
            slowCallDurationMs = 2000L;
        }
        if (slidingWindowSize <= 0) {
            slidingWindowSize = 20;
        }
        if (minimumNumberOfCalls <= 0) {
            minimumNumberOfCalls = 5;
        }
        if (waitDurationInOpenStateMs <= 0) {
            waitDurationInOpenStateMs = 5000L;
        }
        if (permittedNumberOfCallsInHalfOpenState <= 0) {
            permittedNumberOfCallsInHalfOpenState = 3;
        }
    }

    public static CircuitBreakerConfig defaultConfig() {
        return new CircuitBreakerConfig(50, 50, 2000L, 20, 5, 5000L, 3);
    }
}
