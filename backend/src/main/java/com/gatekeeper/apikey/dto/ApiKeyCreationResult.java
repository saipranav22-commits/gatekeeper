package com.gatekeeper.apikey.dto;

import java.util.Objects;

/**
 * Result returned <b>exactly once</b> when an API key is generated.
 * Contains both the persisted metadata and the transient plaintext key string.
 */
public record ApiKeyCreationResult(
    ApiKeyMetadataDto metadata,
    String plaintextKey
) {
    public ApiKeyCreationResult {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(plaintextKey, "plaintextKey must not be null");
    }
}
