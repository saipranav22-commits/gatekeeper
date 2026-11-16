package com.gatekeeper.apikey.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload to revoke an API key")
public record RevokeApiKeyRequest(
    @Size(max = 255, message = "Revocation reason cannot exceed 255 characters")
    @Schema(description = "Audit reason explaining why the key is being revoked", example = "Key rotation or suspected compromise")
    String reason
) {
}
