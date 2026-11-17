package com.gatekeeper.policy.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * Immutable policy rule definition.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyRule(
    String ruleId,
    int priority,
    Boolean enabled,
    PolicyRuleConditions conditions,
    PolicyAction action,
    String description,
    String reasonCode,
    String safeReason
) implements Comparable<PolicyRule> {

    public PolicyRule {
        Objects.requireNonNull(ruleId, "ruleId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (conditions == null) {
            conditions = PolicyRuleConditions.empty();
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            reasonCode = action == PolicyAction.DENY ? "POLICY_RESTRICTION" : "POLICY_PERMITTED";
        }
        if (safeReason == null || safeReason.isBlank()) {
            safeReason = description != null ? description : "Evaluated by policy rule " + ruleId;
        }
    }

    /**
     * Deterministic natural ordering:
     * 1. Ascending numerical priority (lower number = higher precedence)
     * 2. Stable tie-breaker: ruleId alphabetical ascending
     */
    @Override
    public int compareTo(PolicyRule other) {
        int cmp = Integer.compare(this.priority, other.priority);
        if (cmp != 0) {
            return cmp;
        }
        return this.ruleId.compareTo(other.ruleId);
    }
}
