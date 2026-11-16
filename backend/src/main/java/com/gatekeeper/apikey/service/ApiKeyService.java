package com.gatekeeper.apikey.service;

import com.gatekeeper.apikey.dto.ApiKeyCreationResult;
import com.gatekeeper.apikey.dto.ApiKeyMetadataDto;
import com.gatekeeper.apikey.dto.ApiKeyValidationResult;
import com.gatekeeper.apikey.dto.CreateApiKeyRequest;
import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.apikey.entity.ApiKeyScope;
import com.gatekeeper.apikey.entity.ApiKeyStatus;
import com.gatekeeper.apikey.repository.ApiKeyRepository;
import com.gatekeeper.common.exception.InvalidApiKeyException;
import com.gatekeeper.common.exception.InvalidStateTransitionException;
import com.gatekeeper.common.exception.ResourceNotFoundException;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.entity.ConsumerStatus;
import com.gatekeeper.consumer.service.ConsumerService;
import com.gatekeeper.ratelimit.dto.RateLimitPlan;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Profile("!foundation")
@Transactional
public class ApiKeyService {

    private static final int MAX_COLLISION_RETRIES = 5;
    private static final Pattern SCOPE_PATTERN = Pattern.compile("^[a-z0-9]+(:[a-z0-9_-]+)*$");

    private final ApiKeyRepository apiKeyRepository;
    private final ConsumerService consumerService;
    private final ApiKeyGenerator apiKeyGenerator;
    private final ApiKeyHasher apiKeyHasher;

    public ApiKeyService(ApiKeyRepository apiKeyRepository,
                         ConsumerService consumerService,
                         ApiKeyGenerator apiKeyGenerator,
                         ApiKeyHasher apiKeyHasher) {
        this.apiKeyRepository = apiKeyRepository;
        this.consumerService = consumerService;
        this.apiKeyGenerator = apiKeyGenerator;
        this.apiKeyHasher = apiKeyHasher;
    }

    /**
     * Creates an API key for an active consumer.
     * Persists only the public lookup prefix and SHA-256 secret digest.
     * Returns the complete raw key string <b>exactly once</b>.
     */
    public ApiKeyCreationResult createApiKey(CreateApiKeyRequest request) {
        Objects.requireNonNull(request, "CreateApiKeyRequest must not be null");
        validateKeyCreationData(request);

        ApiConsumer consumer = this.consumerService.getActiveConsumerEntity(request.consumerId());
        if (consumer.getStatus() != ConsumerStatus.ACTIVE) {
            throw new InvalidStateTransitionException("Cannot create API key for consumer in " + consumer.getStatus() + " status");
        }

        // Bounded collision retry check on unique prefix constraint
        ApiKeyGenerator.GeneratedApiKey generated = this.apiKeyGenerator.generateUniqueKey(
            this.apiKeyRepository::existsByKeyPrefix,
            MAX_COLLISION_RETRIES
        );

        String secretDigest = this.apiKeyHasher.hashSecret(generated.secret());

        ApiKey apiKey = new ApiKey(consumer, request.name().trim(), generated.keyPrefix(), secretDigest);
        if (request.expiresAt() != null) {
            apiKey.setExpiresAt(request.expiresAt());
        }

        for (String scope : request.scopes()) {
            apiKey.addScope(scope.trim().toLowerCase());
        }

        ApiKey saved = this.apiKeyRepository.save(apiKey);
        ApiKeyMetadataDto metadata = ApiKeyMetadataDto.fromEntity(saved);

        return new ApiKeyCreationResult(metadata, generated.fullKey());
    }

    /**
     * Validates a presented raw API key string against required scopes.
     * Does not expose the raw key or hash in return data or exceptions.
     */
    @Transactional(readOnly = true)
    public ApiKeyValidationResult validateKey(String rawApiKey, Set<String> requiredScopes) {
        ApiKeyGenerator.ParsedApiKey parsed;
        try {
            parsed = this.apiKeyGenerator.parseKey(rawApiKey);
        } catch (InvalidApiKeyException e) {
            return ApiKeyValidationResult.invalid(e.getMessage());
        }

        Optional<ApiKey> keyOpt = this.apiKeyRepository.findByKeyPrefix(parsed.keyPrefix());
        if (keyOpt.isEmpty()) {
            return ApiKeyValidationResult.invalid("API key not found");
        }

        ApiKey apiKey = keyOpt.get();

        if (apiKey.isDeleted()) {
            return ApiKeyValidationResult.invalid("API key has been deleted");
        }

        if (apiKey.getStatus() != ApiKeyStatus.ACTIVE) {
            return ApiKeyValidationResult.invalid("API key is " + apiKey.getStatus());
        }

        if (apiKey.isExpired()) {
            return ApiKeyValidationResult.invalid("API key has expired");
        }

        ApiConsumer consumer = apiKey.getConsumer();
        if (consumer == null || consumer.isDeleted()) {
            return ApiKeyValidationResult.invalid("Associated consumer is deleted");
        }

        if (consumer.getStatus() != ConsumerStatus.ACTIVE) {
            return ApiKeyValidationResult.invalid("Associated consumer is " + consumer.getStatus());
        }

        // Constant-time SHA-256 verification
        if (!this.apiKeyHasher.verify(parsed.secret(), apiKey.getSecretDigest())) {
            return ApiKeyValidationResult.invalid("Invalid secret");
        }

        Set<String> assignedScopes = apiKey.getScopes().stream()
            .map(ApiKeyScope::getScope)
            .collect(Collectors.toSet());

        if (requiredScopes != null && !requiredScopes.isEmpty()) {
            if (!assignedScopes.containsAll(requiredScopes)) {
                return ApiKeyValidationResult.invalid("Missing required scope(s)");
            }
        }

        // Note: validateKey is executed with @Transactional(readOnly = true) to prevent database row locks,
        // dirty-checking overhead, and optimistic lock contention on the gateway request hot path.
        // Setting lastUsedAt here updates the in-memory entity for caller inspection, but does not issue
        // synchronous UPDATE statements to PostgreSQL during readOnly transactions.
        // Persistent access telemetry and usage tracking are cleanly decoupled and scheduled for Phase 3C.
        Instant now = Instant.now();
        if (apiKey.getLastUsedAt() == null || apiKey.getLastUsedAt().isBefore(now.minusSeconds(5))) {
            apiKey.setLastUsedAt(now);
        }

        // Adapt consumer subscription plan to immutable RateLimitPlan within transaction
        RateLimitPlan rateLimitPlan = null;
        if (consumer.getPlan() != null) {
            rateLimitPlan = RateLimitPlan.fromSubscriptionPlan(consumer.getPlan());
        }

        return ApiKeyValidationResult.valid(consumer, apiKey, assignedScopes, rateLimitPlan);
    }

    /**
     * Revokes an active API key with idempotent semantics.
     */
    public ApiKeyMetadataDto revokeKey(UUID keyId, String reason) {
        ApiKey apiKey = this.apiKeyRepository.findById(keyId)
            .orElseThrow(() -> new ResourceNotFoundException("ApiKey", "id", keyId));

        if (apiKey.getStatus() == ApiKeyStatus.REVOKED) {
            return ApiKeyMetadataDto.fromEntity(apiKey); // Idempotent
        }

        apiKey.revoke(reason != null ? reason.trim() : "Revoked by administrator");
        return ApiKeyMetadataDto.fromEntity(apiKey);
    }

    @Transactional(readOnly = true)
    public ApiKeyMetadataDto getKeyMetadata(UUID keyId) {
        ApiKey apiKey = this.apiKeyRepository.findById(keyId)
            .orElseThrow(() -> new ResourceNotFoundException("ApiKey", "id", keyId));
        return ApiKeyMetadataDto.fromEntity(apiKey);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyMetadataDto> listKeysForConsumer(UUID consumerId) {
        this.consumerService.getActiveConsumerEntity(consumerId);
        return this.apiKeyRepository.findByConsumerIdAndIsDeletedFalse(consumerId)
            .stream()
            .map(ApiKeyMetadataDto::fromEntity)
            .toList();
    }

    private void validateKeyCreationData(CreateApiKeyRequest request) {
        if (request.name() == null || request.name().trim().isBlank()) {
            throw new IllegalArgumentException("API key name must not be blank");
        }
        if (request.name().trim().length() > 100) {
            throw new IllegalArgumentException("API key name cannot exceed 100 characters");
        }
        if (request.scopes() == null || request.scopes().isEmpty()) {
            throw new IllegalArgumentException("API key must have at least one assigned scope");
        }
        for (String scope : request.scopes()) {
            if (scope == null || scope.trim().isBlank() || !SCOPE_PATTERN.matcher(scope.trim().toLowerCase()).matches()) {
                throw new IllegalArgumentException("Invalid scope format: '" + scope + "' (expected format: e.g. 'gateway:invoke' or 'read:orders')");
            }
        }
        if (request.expiresAt() != null && request.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Expiration timestamp must be in the future");
        }
    }
}
