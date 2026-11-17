package com.gatekeeper.policy.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Trusted, immutable request context supplied to the policy evaluation engine.
 * Never constructed from untrusted or unauthenticated client data.
 */
public record PolicyEvaluationContext(
    String routeId,
    String httpMethod,
    String clientIp,
    UUID consumerId,
    String consumerPlan,
    Set<String> apiKeyScopes,
    Instant requestTimeUtc
) {
    public PolicyEvaluationContext {
        if (httpMethod != null) httpMethod = httpMethod.trim().toUpperCase();
        if (consumerPlan != null) consumerPlan = consumerPlan.trim().toUpperCase();
        apiKeyScopes = apiKeyScopes != null ? Collections.unmodifiableSet(apiKeyScopes) : Collections.emptySet();
        if (requestTimeUtc == null) requestTimeUtc = Instant.now();
    }
}
