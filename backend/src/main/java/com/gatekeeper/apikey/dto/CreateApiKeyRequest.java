package com.gatekeeper.apikey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record CreateApiKeyRequest(
    @NotNull(message = "Consumer ID is required")
    UUID consumerId,

    @NotBlank(message = "API key label/name is required")
    @Size(max = 100, message = "Name cannot exceed 100 characters")
    String name,

    @NotEmpty(message = "At least one scope must be assigned")
    Set<String> scopes,

    Instant expiresAt
) {
}
