package com.gatekeeper.gateway.dto;

import com.gatekeeper.ratelimit.dto.RateLimitPlan;

import java.security.Principal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Trusted authenticated consumer principal representing an active consumer and validated API key.
 */
public record ConsumerPrincipal(
    UUID consumerId,
    String consumerName,
    UUID apiKeyId,
    String keyPrefix,
    Set<String> scopes,
    RateLimitPlan plan
) implements Principal {

    public ConsumerPrincipal {
        Objects.requireNonNull(consumerId, "consumerId must not be null");
        Objects.requireNonNull(consumerName, "consumerName must not be null");
        Objects.requireNonNull(apiKeyId, "apiKeyId must not be null");
        Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        Objects.requireNonNull(scopes, "scopes must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
    }

    @Override
    public String getName() {
        return consumerName;
    }

    /**
     * Checks if the principal has been granted the required permission scope.
     */
    public boolean hasScope(String requiredScope) {
        if (requiredScope == null || requiredScope.isBlank()) {
            return false;
        }
        return scopes.contains(requiredScope.trim().toLowerCase());
    }
}
