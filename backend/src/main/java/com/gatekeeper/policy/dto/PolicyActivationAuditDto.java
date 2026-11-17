package com.gatekeeper.policy.dto;

import java.time.Instant;
import java.util.UUID;

public record PolicyActivationAuditDto(
    UUID id,
    String policyKey,
    int versionNumber,
    Integer previousVersionNumber,
    String action,
    String actor,
    String reason,
    Instant createdAt
) {
}
