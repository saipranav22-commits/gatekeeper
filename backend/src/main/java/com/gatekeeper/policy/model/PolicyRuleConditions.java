package com.gatekeeper.policy.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable match conditions evaluated against a trusted request context.
 * Null or empty fields represent wildcard (match any).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyRuleConditions(
    String routeId,
    String httpMethod,
    String consumerPlan,
    Set<String> requiredScopes,
    Set<String> excludedScopes,
    String timeWindowStartUtc,
    String timeWindowEndUtc,
    Set<String> daysOfWeek
) {
    public PolicyRuleConditions {
        if (routeId != null) routeId = routeId.trim();
        if (httpMethod != null) httpMethod = httpMethod.trim().toUpperCase();
        if (consumerPlan != null) consumerPlan = consumerPlan.trim().toUpperCase();
        if (requiredScopes != null) requiredScopes = Set.copyOf(requiredScopes);
        if (excludedScopes != null) excludedScopes = Set.copyOf(excludedScopes);
        if (daysOfWeek != null) daysOfWeek = Set.copyOf(daysOfWeek);
    }

    public static PolicyRuleConditions empty() {
        return new PolicyRuleConditions(null, null, null, null, null, null, null, null);
    }
}
