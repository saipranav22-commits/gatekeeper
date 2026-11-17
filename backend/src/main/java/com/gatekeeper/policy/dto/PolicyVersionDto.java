package com.gatekeeper.policy.dto;

import com.gatekeeper.policy.model.PolicyRule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PolicyVersionDto(
    UUID id,
    String policyKey,
    int versionNumber,
    String status,
    String description,
    List<PolicyRule> rules,
    int rulesCount,
    Instant createdAt,
    String createdBy,
    Instant activatedAt,
    String activatedBy
) {
}
