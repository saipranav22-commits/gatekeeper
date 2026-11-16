package com.gatekeeper.apikey.service;

import com.gatekeeper.apikey.dto.ApiKeyCreationResult;
import com.gatekeeper.apikey.dto.ApiKeyMetadataDto;
import com.gatekeeper.apikey.dto.ApiKeyValidationResult;
import com.gatekeeper.apikey.dto.CreateApiKeyRequest;
import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.apikey.entity.ApiKeyStatus;
import com.gatekeeper.apikey.repository.ApiKeyRepository;
import com.gatekeeper.common.exception.InvalidStateTransitionException;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.entity.ConsumerStatus;
import com.gatekeeper.consumer.service.ConsumerService;
import com.gatekeeper.plan.entity.SubscriptionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private ConsumerService consumerService;

    @Spy
    private ApiKeyGenerator apiKeyGenerator = new ApiKeyGenerator();

    @Spy
    private ApiKeyHasher apiKeyHasher = new ApiKeyHasher();

    @InjectMocks
    private ApiKeyService apiKeyService;

    private ApiConsumer activeConsumer;
    private ApiConsumer suspendedConsumer;
    private ApiConsumer deletedConsumer;

    @BeforeEach
    void setUp() {
        SubscriptionPlan plan = new SubscriptionPlan("PRO", "Pro tier", 600, 600, 60);
        plan.setId(UUID.randomUUID());

        this.activeConsumer = new ApiConsumer("Acme Corp", "dev@acme.com", plan);
        this.activeConsumer.setId(UUID.randomUUID());

        this.suspendedConsumer = new ApiConsumer("Suspended Corp", "suspended@corp.com", plan);
        this.suspendedConsumer.setId(UUID.randomUUID());
        this.suspendedConsumer.setStatus(ConsumerStatus.SUSPENDED);

        this.deletedConsumer = new ApiConsumer("Deleted Corp", "deleted@corp.com", plan);
        this.deletedConsumer.setId(UUID.randomUUID());
        this.deletedConsumer.softDelete();
    }

    @Test
    @DisplayName("Create API key returns full key once and persists only digest and prefix")
    void testCreateApiKeySuccess() {
        UUID consumerId = this.activeConsumer.getId();
        CreateApiKeyRequest request = new CreateApiKeyRequest(
            consumerId,
            "Server Key",
            Set.of("gateway:invoke", "read:orders"),
            Instant.now().plus(30, ChronoUnit.DAYS)
        );

        when(this.consumerService.getActiveConsumerEntity(consumerId)).thenReturn(this.activeConsumer);
        when(this.apiKeyRepository.existsByKeyPrefix(anyString())).thenReturn(false);
        when(this.apiKeyRepository.save(any(ApiKey.class))).thenAnswer(invocation -> {
            ApiKey k = invocation.getArgument(0);
            k.setId(UUID.randomUUID());
            return k;
        });

        ApiKeyCreationResult result = this.apiKeyService.createApiKey(request);

        assertThat(result).isNotNull();
        assertThat(result.plaintextKey()).isNotNull();
        assertThat(result.plaintextKey()).startsWith("gk_live_");
        assertThat(result.metadata().keyPrefix()).isEqualTo(result.plaintextKey().substring(0, 24));
        assertThat(result.metadata().name()).isEqualTo("Server Key");
        assertThat(result.metadata().scopes()).containsExactlyInAnyOrder("gateway:invoke", "read:orders");

        verify(this.apiKeyRepository).save(any(ApiKey.class));
    }

    @Test
    @DisplayName("Create API key throws when consumer is suspended")
    void testCreateApiKeySuspendedConsumer() {
        UUID consumerId = this.suspendedConsumer.getId();
        CreateApiKeyRequest request = new CreateApiKeyRequest(
            consumerId,
            "Server Key",
            Set.of("gateway:invoke"),
            null
        );

        when(this.consumerService.getActiveConsumerEntity(consumerId)).thenReturn(this.suspendedConsumer);

        assertThatThrownBy(() -> this.apiKeyService.createApiKey(request))
            .isInstanceOf(InvalidStateTransitionException.class)
            .hasMessageContaining("SUSPENDED");
    }

    @Test
    @DisplayName("Create API key throws on past expiration date")
    void testCreateApiKeyPastExpiration() {
        CreateApiKeyRequest request = new CreateApiKeyRequest(
            this.activeConsumer.getId(),
            "Server Key",
            Set.of("gateway:invoke"),
            Instant.now().minus(1, ChronoUnit.DAYS)
        );

        assertThatThrownBy(() -> this.apiKeyService.createApiKey(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Expiration timestamp must be in the future");
    }

    @Test
    @DisplayName("Validate key succeeds for valid active key and matching scopes")
    void testValidateKeySuccess() {
        ApiKeyGenerator.GeneratedApiKey keyPair = this.apiKeyGenerator.generateKey();
        String secretDigest = this.apiKeyHasher.hashSecret(keyPair.secret());

        ApiKey apiKey = new ApiKey(this.activeConsumer, "Test Key", keyPair.keyPrefix(), secretDigest);
        apiKey.addScope("gateway:invoke");
        apiKey.addScope("read:orders");

        when(this.apiKeyRepository.findByKeyPrefix(keyPair.keyPrefix())).thenReturn(Optional.of(apiKey));

        ApiKeyValidationResult result = this.apiKeyService.validateKey(keyPair.fullKey(), Set.of("gateway:invoke"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.rejectionReason()).isNull();
        assertThat(result.consumer()).isEqualTo(this.activeConsumer);
        assertThat(result.scopes()).contains("gateway:invoke", "read:orders");
        assertThat(apiKey.getLastUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("Validate key fails on incorrect secret material")
    void testValidateKeyWrongSecret() {
        ApiKeyGenerator.GeneratedApiKey key1 = this.apiKeyGenerator.generateKey();
        ApiKeyGenerator.GeneratedApiKey key2 = this.apiKeyGenerator.generateKey();
        String secretDigest1 = this.apiKeyHasher.hashSecret(key1.secret());

        ApiKey apiKey = new ApiKey(this.activeConsumer, "Test Key", key1.keyPrefix(), secretDigest1);
        when(this.apiKeyRepository.findByKeyPrefix(key1.keyPrefix())).thenReturn(Optional.of(apiKey));

        // Use key1 prefix with key2 secret
        String forgedKey = key1.keyPrefix() + "_" + key2.secret();
        ApiKeyValidationResult result = this.apiKeyService.validateKey(forgedKey, Set.of());

        assertThat(result.isValid()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("Invalid secret");
    }

    @Test
    @DisplayName("Validate key fails when key is expired")
    void testValidateKeyExpired() {
        ApiKeyGenerator.GeneratedApiKey keyPair = this.apiKeyGenerator.generateKey();
        String secretDigest = this.apiKeyHasher.hashSecret(keyPair.secret());

        ApiKey apiKey = new ApiKey(this.activeConsumer, "Test Key", keyPair.keyPrefix(), secretDigest);
        apiKey.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));

        when(this.apiKeyRepository.findByKeyPrefix(keyPair.keyPrefix())).thenReturn(Optional.of(apiKey));

        ApiKeyValidationResult result = this.apiKeyService.validateKey(keyPair.fullKey(), Set.of());

        assertThat(result.isValid()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("API key has expired");
    }

    @Test
    @DisplayName("Validate key fails when key is revoked")
    void testValidateKeyRevoked() {
        ApiKeyGenerator.GeneratedApiKey keyPair = this.apiKeyGenerator.generateKey();
        String secretDigest = this.apiKeyHasher.hashSecret(keyPair.secret());

        ApiKey apiKey = new ApiKey(this.activeConsumer, "Test Key", keyPair.keyPrefix(), secretDigest);
        apiKey.revoke("Compromised key");

        when(this.apiKeyRepository.findByKeyPrefix(keyPair.keyPrefix())).thenReturn(Optional.of(apiKey));

        ApiKeyValidationResult result = this.apiKeyService.validateKey(keyPair.fullKey(), Set.of());

        assertThat(result.isValid()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("API key is REVOKED");
    }

    @Test
    @DisplayName("Validate key fails when consumer is suspended")
    void testValidateKeySuspendedConsumer() {
        ApiKeyGenerator.GeneratedApiKey keyPair = this.apiKeyGenerator.generateKey();
        String secretDigest = this.apiKeyHasher.hashSecret(keyPair.secret());

        ApiKey apiKey = new ApiKey(this.suspendedConsumer, "Test Key", keyPair.keyPrefix(), secretDigest);

        when(this.apiKeyRepository.findByKeyPrefix(keyPair.keyPrefix())).thenReturn(Optional.of(apiKey));

        ApiKeyValidationResult result = this.apiKeyService.validateKey(keyPair.fullKey(), Set.of());

        assertThat(result.isValid()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("Associated consumer is SUSPENDED");
    }

    @Test
    @DisplayName("Validate key fails when consumer is deleted")
    void testValidateKeyDeletedConsumer() {
        ApiKeyGenerator.GeneratedApiKey keyPair = this.apiKeyGenerator.generateKey();
        String secretDigest = this.apiKeyHasher.hashSecret(keyPair.secret());

        ApiKey apiKey = new ApiKey(this.deletedConsumer, "Test Key", keyPair.keyPrefix(), secretDigest);

        when(this.apiKeyRepository.findByKeyPrefix(keyPair.keyPrefix())).thenReturn(Optional.of(apiKey));

        ApiKeyValidationResult result = this.apiKeyService.validateKey(keyPair.fullKey(), Set.of());

        assertThat(result.isValid()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("Associated consumer is deleted");
    }

    @Test
    @DisplayName("Validate key fails when missing required scopes")
    void testValidateKeyMissingScopes() {
        ApiKeyGenerator.GeneratedApiKey keyPair = this.apiKeyGenerator.generateKey();
        String secretDigest = this.apiKeyHasher.hashSecret(keyPair.secret());

        ApiKey apiKey = new ApiKey(this.activeConsumer, "Test Key", keyPair.keyPrefix(), secretDigest);
        apiKey.addScope("read:orders");

        when(this.apiKeyRepository.findByKeyPrefix(keyPair.keyPrefix())).thenReturn(Optional.of(apiKey));

        ApiKeyValidationResult result = this.apiKeyService.validateKey(keyPair.fullKey(), Set.of("write:orders"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("Missing required scope(s)");
    }

    @Test
    @DisplayName("Revoke key is idempotent across three repeated calls and preserves original audit data")
    void testRevokeKeyIdempotent() {
        UUID keyId = UUID.randomUUID();
        ApiKey apiKey = new ApiKey(this.activeConsumer, "Test Key", "gk_live_1234567890abcdef", "digest");
        apiKey.setId(keyId);

        when(this.apiKeyRepository.findById(keyId)).thenReturn(Optional.of(apiKey));

        ApiKeyMetadataDto firstRevoke = this.apiKeyService.revokeKey(keyId, "Security incident");
        assertThat(firstRevoke.status()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(firstRevoke.revocationReason()).isEqualTo("Security incident");
        assertThat(firstRevoke.revokedAt()).isNotNull();

        // 2nd revocation
        ApiKeyMetadataDto secondRevoke = this.apiKeyService.revokeKey(keyId, "Another reason");
        assertThat(secondRevoke.status()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(secondRevoke.revocationReason()).isEqualTo("Security incident"); // unchanged
        assertThat(secondRevoke.revokedAt()).isEqualTo(firstRevoke.revokedAt()); // unchanged

        // 3rd revocation
        ApiKeyMetadataDto thirdRevoke = this.apiKeyService.revokeKey(keyId, null);
        assertThat(thirdRevoke.status()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(thirdRevoke.revocationReason()).isEqualTo("Security incident"); // unchanged
        assertThat(thirdRevoke.revokedAt()).isEqualTo(firstRevoke.revokedAt()); // unchanged
    }

    @Test
    @DisplayName("Concurrent revocation calls handled safely without corruption")
    void testConcurrentRevocation() throws Exception {
        UUID keyId = UUID.randomUUID();
        ApiKey apiKey = new ApiKey(this.activeConsumer, "Test Key", "gk_live_concurrent1234", "digest");
        apiKey.setId(keyId);

        when(this.apiKeyRepository.findById(keyId)).thenReturn(Optional.of(apiKey));

        int threadCount = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.List<ApiKeyMetadataDto> results = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ApiKeyMetadataDto res = this.apiKeyService.revokeKey(keyId, "Thread Reason " + idx);
                    results.add(res);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(results).hasSize(threadCount);
        for (ApiKeyMetadataDto res : results) {
            assertThat(res.status()).isEqualTo(ApiKeyStatus.REVOKED);
            assertThat(res.revokedAt()).isNotNull();
        }
        assertThat(apiKey.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
    }
}
