package com.gatekeeper.consumer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Request payload to update a consumer's subscription plan")
public record ChangePlanRequest(
    @NotNull(message = "Subscription plan ID is required")
    @Schema(description = "Unique identifier of the target subscription plan", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    UUID planId
) {
}
