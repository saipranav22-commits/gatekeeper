package com.gatekeeper.policy.dto;

import com.gatekeeper.policy.model.PolicyRule;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

public record PolicyReplayRequest(
    Integer candidateVersionNumber,
    List<PolicyRule> candidateRules,
    @Min(value = 1, message = "sampleLimit must be at least 1")
    @Max(value = 500, message = "sampleLimit cannot exceed 500")
    int sampleLimit,
    @Min(value = 1, message = "sinceHours must be at least 1")
    @Max(value = 720, message = "sinceHours cannot exceed 720 (30 days)")
    int sinceHours
) {
    public PolicyReplayRequest {
        if (sampleLimit <= 0) sampleLimit = 100;
        if (sinceHours <= 0) sinceHours = 24;
    }
}
