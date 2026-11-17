package com.gatekeeper.policy.dto;

public record ConfigureShadowPolicyRequest(
    Integer shadowVersionNumber // Null to clear shadow version
) {
}
