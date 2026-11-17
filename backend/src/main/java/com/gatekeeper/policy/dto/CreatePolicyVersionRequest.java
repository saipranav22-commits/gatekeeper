package com.gatekeeper.policy.dto;

import com.gatekeeper.policy.model.PolicyRule;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreatePolicyVersionRequest(
    @Size(max = 255, message = "description cannot exceed 255 characters")
    String description,

    @NotEmpty(message = "rules list must not be empty")
    List<PolicyRule> rules
) {
}
