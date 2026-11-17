package com.gatekeeper.policy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatePolicyDefinitionRequest(
    @NotBlank(message = "policyKey is required")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{3,64}$", message = "policyKey must contain 3-64 alphanumeric, underscore or hyphen characters")
    String policyKey,

    @NotBlank(message = "name is required")
    @Size(min = 2, max = 100, message = "name must be between 2 and 100 characters")
    String name,

    @Size(max = 255, message = "description cannot exceed 255 characters")
    String description
) {
}
