package com.gatekeeper.policy.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Structured outcome of policy evaluation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyDecision(
    PolicyAction action,
    String policyKey,
    Integer policyVersion,
    String matchedRuleId,
    String reasonCode,
    String safeReason,
    boolean quotaConsumed,
    long durationNanos,
    PolicyDecisionTrace trace
) {
    public static PolicyDecision allowDefault(String policyKey, Integer version, long durationNanos, PolicyDecisionTrace trace) {
        return new PolicyDecision(
            PolicyAction.ALLOW_CONTINUE,
            policyKey,
            version,
            null,
            "DEFAULT_ALLOW",
            "No specific policy restriction matched; proceeding through mandatory platform security",
            true,
            durationNanos,
            trace
        );
    }

    public static PolicyDecision match(
            PolicyRule rule,
            String policyKey,
            Integer version,
            long durationNanos,
            PolicyDecisionTrace trace) {

        boolean consumesQuota = rule.action() == PolicyAction.ALLOW_CONTINUE;
        return new PolicyDecision(
            rule.action(),
            policyKey,
            version,
            rule.ruleId(),
            rule.reasonCode(),
            rule.safeReason(),
            consumesQuota,
            durationNanos,
            trace
        );
    }
}
