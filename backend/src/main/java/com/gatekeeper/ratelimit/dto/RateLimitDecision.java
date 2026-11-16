package com.gatekeeper.ratelimit.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable evaluation result returned by the rate-limiting engine.
 */
public record RateLimitDecision(
    boolean allowed,
    long tokensRemaining,
    long bucketCapacity,
    long retryAfterSeconds,
    long resetAfterSeconds,
    String limitPolicy,
    Instant decisionTimestamp
) {
    public RateLimitDecision {
        Objects.requireNonNull(limitPolicy, "limitPolicy must not be null");
        Objects.requireNonNull(decisionTimestamp, "decisionTimestamp must not be null");
        if (tokensRemaining < 0) {
            tokensRemaining = 0;
        }
        if (retryAfterSeconds < 0) {
            retryAfterSeconds = 0;
        }
        if (resetAfterSeconds < 0) {
            resetAfterSeconds = 0;
        }
    }

    /**
     * Factory method for creating a decision.
     */
    public static RateLimitDecision of(
        boolean allowed,
        long tokensRemaining,
        long bucketCapacity,
        long retryAfterSeconds,
        long resetAfterSeconds,
        String limitPolicy
    ) {
        return new RateLimitDecision(
            allowed,
            tokensRemaining,
            bucketCapacity,
            retryAfterSeconds,
            resetAfterSeconds,
            limitPolicy,
            Instant.now()
        );
    }
}
