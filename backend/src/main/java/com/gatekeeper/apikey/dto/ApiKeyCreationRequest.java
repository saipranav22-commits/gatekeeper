package com.gatekeeper.apikey.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

@Schema(description = "Request payload to create a new API key for a consumer")
public record ApiKeyCreationRequest(
    @NotBlank(message = "API key label/name is required")
    @Size(max = 100, message = "Name cannot exceed 100 characters")
    @Schema(description = "Human-readable label for the API key", example = "Production Gateway Key")
    String name,

    @NotEmpty(message = "At least one scope must be assigned")
    @Schema(description = "Set of authorized scopes", example = "[\"gateway:invoke\", \"read:orders\"]")
    Set<String> scopes,

    @Schema(description = "Optional UTC expiration timestamp", example = "2027-12-31T23:59:59Z")
    Instant expiresAt
) {
}
