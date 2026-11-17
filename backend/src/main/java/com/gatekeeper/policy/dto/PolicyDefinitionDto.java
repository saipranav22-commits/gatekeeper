package com.gatekeeper.policy.dto;

import java.time.Instant;
import java.util.UUID;

public record PolicyDefinitionDto(
    UUID id,
    String policyKey,
    String name,
    String description,
    Integer activeVersionNumber,
    Integer shadowVersionNumber,
    Long versionLock,
    Instant createdAt,
    Instant updatedAt
) {
}
