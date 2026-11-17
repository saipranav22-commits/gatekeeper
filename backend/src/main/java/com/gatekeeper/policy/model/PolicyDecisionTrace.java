package com.gatekeeper.policy.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full explainable trace of a policy evaluation run.
 * Contains candidate rules considered and reasons for non-matches.
 * Strictly excludes secrets, tokens, or PII.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyDecisionTrace(
    UUID evaluationId,
    Instant timestamp,
    String policyKey,
    Integer versionNumber,
    String routeId,
    PolicyAction finalDecision,
    String matchedRuleId,
    String reasonCode,
    String safeReason,
    long evaluationDurationNanos,
    int candidateRulesCount,
    List<RuleEvaluationTrace> ruleEvaluations
) {
}
