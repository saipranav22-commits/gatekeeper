package com.gatekeeper.ratelimit.dto;

import com.gatekeeper.plan.entity.SubscriptionPlan;

import java.util.Objects;

/**
 * Immutable configuration for a rate-limiting plan.
 */
public record RateLimitPlan(
    String name,
    long bucketCapacity,
    long refillTokens,
    long refillPeriodSeconds
) {
    public RateLimitPlan {
        if (bucketCapacity <= 0) {
            throw new IllegalArgumentException("Bucket capacity must be positive, got: " + bucketCapacity);
        }
        if (refillTokens <= 0) {
            throw new IllegalArgumentException("Refill tokens must be positive, got: " + refillTokens);
        }
        if (refillPeriodSeconds <= 0) {
            throw new IllegalArgumentException("Refill period seconds must be positive, got: " + refillPeriodSeconds);
        }
        if (name == null || name.isBlank()) {
            name = "CUSTOM";
        }
    }

    /**
     * Adapts a JPA SubscriptionPlan entity into an immutable RateLimitPlan.
     */
    public static RateLimitPlan fromSubscriptionPlan(SubscriptionPlan plan) {
        Objects.requireNonNull(plan, "SubscriptionPlan must not be null");
        return new RateLimitPlan(
            plan.getName(),
            plan.getBucketCapacity(),
            plan.getRefillTokens(),
            (long) plan.getRefillPeriodSeconds()
        );
    }
}
