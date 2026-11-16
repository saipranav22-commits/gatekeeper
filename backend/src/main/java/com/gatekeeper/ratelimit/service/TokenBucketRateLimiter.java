package com.gatekeeper.ratelimit.service;

import com.gatekeeper.plan.entity.SubscriptionPlan;
import com.gatekeeper.ratelimit.dto.RateLimitDecision;
import com.gatekeeper.ratelimit.dto.RateLimitPlan;

import java.util.UUID;

/**
 * Service contract for distributed token bucket rate limiting.
 */
public interface TokenBucketRateLimiter {

    /**
     * Consumes tokens from the consumer's bucket.
     *
     * @param consumerId  the unique consumer identifier
     * @param plan        the rate limit plan policy
     * @param requestCost number of tokens required (must be >= 1)
     * @return rate limit decision
     */
    RateLimitDecision consume(UUID consumerId, RateLimitPlan plan, int requestCost);

    /**
     * Consumes 1 token from the consumer's bucket.
     *
     * @param consumerId the unique consumer identifier
     * @param plan       the rate limit plan policy
     * @return rate limit decision
     */
    default RateLimitDecision consume(UUID consumerId, RateLimitPlan plan) {
        return consume(consumerId, plan, 1);
    }

    /**
     * Consumes tokens from the consumer's bucket using a JPA SubscriptionPlan entity.
     *
     * @param consumerId  the unique consumer identifier
     * @param plan        the subscription plan entity
     * @param requestCost number of tokens required (must be >= 1)
     * @return rate limit decision
     */
    default RateLimitDecision consume(UUID consumerId, SubscriptionPlan plan, int requestCost) {
        return consume(consumerId, RateLimitPlan.fromSubscriptionPlan(plan), requestCost);
    }

    /**
     * Consumes 1 token from the consumer's bucket using a JPA SubscriptionPlan entity.
     *
     * @param consumerId the unique consumer identifier
     * @param plan       the subscription plan entity
     * @return rate limit decision
     */
    default RateLimitDecision consume(UUID consumerId, SubscriptionPlan plan) {
        return consume(consumerId, plan, 1);
    }

    /**
     * Resets or clears the bucket for the given consumer.
     *
     * @param consumerId the unique consumer identifier
     */
    void resetBucket(UUID consumerId);
}
