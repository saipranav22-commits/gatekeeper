package com.gatekeeper.resilience.bulkhead;

/**
 * Immutable configuration for a RouteBulkhead instance.
 */
public record BulkheadConfig(
    int maxConcurrentCalls
) {
    public BulkheadConfig {
        if (maxConcurrentCalls <= 0) {
            maxConcurrentCalls = 10;
        }
    }

    public static BulkheadConfig defaultConfig() {
        return new BulkheadConfig(10);
    }
}
