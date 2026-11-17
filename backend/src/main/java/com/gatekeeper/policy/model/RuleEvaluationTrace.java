package com.gatekeeper.policy.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Diagnostic trace for a single rule considered during evaluation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleEvaluationTrace(
    String ruleId,
    int priority,
    boolean matched,
    PolicyAction action,
    String matchExplanation,
    String nonMatchReason
) {
    public static RuleEvaluationTrace matched(PolicyRule rule, String explanation) {
        return new RuleEvaluationTrace(
            rule.ruleId(),
            rule.priority(),
            true,
            rule.action(),
            explanation,
            null
        );
    }

    public static RuleEvaluationTrace nonMatched(PolicyRule rule, String nonMatchReason) {
        return new RuleEvaluationTrace(
            rule.ruleId(),
            rule.priority(),
            false,
            rule.action(),
            null,
            nonMatchReason
        );
    }
}
