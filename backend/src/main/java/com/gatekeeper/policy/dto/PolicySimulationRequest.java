package com.gatekeeper.policy.dto;

import com.gatekeeper.policy.model.PolicyRule;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record PolicySimulationRequest(
    Integer candidateVersionNumber,
    List<PolicyRule> candidateRules,
    String routeId,
    String httpMethod,
    String consumerPlan,
    Set<String> apiKeyScopes,
    String clientIp,
    Instant requestTimeUtc
) {
}
