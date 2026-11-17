package com.gatekeeper.resilience.retry;

/**
 * Configuration for bounded, safe retry behavior.
 */
public record RetryConfig(
    int maxAttempts,
    long initialBackoffMs,
    double backoffMultiplier,
    double jitterFactor,
    long maxCumulativeDeadlineMs
) {
    public RetryConfig {
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }
        if (initialBackoffMs <= 0) {
            initialBackoffMs = 100L;
        }
        if (backoffMultiplier <= 1.0) {
            backoffMultiplier = 2.0;
        }
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            jitterFactor = 0.5;
        }
        if (maxCumulativeDeadlineMs <= 0) {
            maxCumulativeDeadlineMs = 5000L;
        }
    }

    public static RetryConfig defaultConfig() {
        return new RetryConfig(3, 100L, 2.0, 0.5, 5000L);
    }
}
