package com.gatekeeper.apikey.dto;

import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.apikey.entity.ApiKeyScope;
import com.gatekeeper.apikey.entity.ApiKeyStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Public metadata representation of an API key.
 * <b>Never</b> exposes raw secret material or persistent SHA-256 digests.
 */
public record ApiKeyMetadataDto(
    UUID id,
    UUID consumerId,
    String name,
    String keyPrefix,
    ApiKeyStatus status,
    Set<String> scopes,
    Instant expiresAt,
    Instant revokedAt,
    String revocationReason,
    Instant lastUsedAt,
    Long version,
    boolean isDeleted,
    Instant createdAt,
    Instant updatedAt
) {
    public static ApiKeyMetadataDto fromEntity(ApiKey apiKey) {
        if (apiKey == null) return null;
        Set<String> scopeNames = apiKey.getScopes() != null
            ? apiKey.getScopes().stream().map(ApiKeyScope::getScope).collect(Collectors.toSet())
            : Set.of();

        return new ApiKeyMetadataDto(
            apiKey.getId(),
            apiKey.getConsumer() != null ? apiKey.getConsumer().getId() : null,
            apiKey.getName(),
            apiKey.getKeyPrefix(),
            apiKey.getStatus(),
            scopeNames,
            apiKey.getExpiresAt(),
            apiKey.getRevokedAt(),
            apiKey.getRevocationReason(),
            apiKey.getLastUsedAt(),
            apiKey.getVersion(),
            apiKey.isDeleted(),
            apiKey.getCreatedAt(),
            apiKey.getUpdatedAt()
        );
    }
}
