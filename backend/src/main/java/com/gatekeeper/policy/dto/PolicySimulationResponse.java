package com.gatekeeper.policy.dto;

import com.gatekeeper.policy.model.PolicyAction;
import com.gatekeeper.policy.model.PolicyDecisionTrace;

public record PolicySimulationResponse(
    String policyKey,
    Integer versionEvaluated,
    PolicyAction decision,
    String matchedRuleId,
    String reasonCode,
    String safeReason,
    boolean hypotheticalQuotaConsumption,
    long durationNanos,
    PolicyDecisionTrace trace
) {
}
