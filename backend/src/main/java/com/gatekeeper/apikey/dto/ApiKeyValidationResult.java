package com.gatekeeper.apikey.dto;

import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.consumer.entity.ApiConsumer;

import java.util.Set;

/**
 * Domain outcome of validating a presented raw API key string.
 */
public record ApiKeyValidationResult(
    boolean isValid,
    String rejectionReason,
    ApiConsumer consumer,
    ApiKey apiKey,
    Set<String> scopes
) {
    public static ApiKeyValidationResult valid(ApiConsumer consumer, ApiKey apiKey, Set<String> scopes) {
        return new ApiKeyValidationResult(true, null, consumer, apiKey, scopes);
    }

    public static ApiKeyValidationResult invalid(String reason) {
        return new ApiKeyValidationResult(false, reason, null, null, Set.of());
    }
}
