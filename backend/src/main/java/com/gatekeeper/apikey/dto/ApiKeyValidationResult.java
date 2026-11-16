package com.gatekeeper.apikey.dto;

import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.ratelimit.dto.RateLimitPlan;

import java.util.Set;

/**
 * Domain outcome of validating a presented raw API key string.
 */
public record ApiKeyValidationResult(
    boolean isValid,
    String rejectionReason,
    ApiConsumer consumer,
    ApiKey apiKey,
    Set<String> scopes,
    RateLimitPlan rateLimitPlan
) {
    public static ApiKeyValidationResult valid(ApiConsumer consumer, ApiKey apiKey, Set<String> scopes, RateLimitPlan rateLimitPlan) {
        return new ApiKeyValidationResult(true, null, consumer, apiKey, scopes, rateLimitPlan);
    }

    public static ApiKeyValidationResult valid(ApiConsumer consumer, ApiKey apiKey, Set<String> scopes) {
        RateLimitPlan plan = null;
        if (consumer != null && consumer.getPlan() != null) {
            plan = RateLimitPlan.fromSubscriptionPlan(consumer.getPlan());
        }
        return new ApiKeyValidationResult(true, null, consumer, apiKey, scopes, plan);
    }

    public static ApiKeyValidationResult invalid(String reason) {
        return new ApiKeyValidationResult(false, reason, null, null, Set.of(), null);
    }
}
