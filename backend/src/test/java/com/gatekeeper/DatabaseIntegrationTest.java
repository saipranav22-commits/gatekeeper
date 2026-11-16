package com.gatekeeper;

import com.gatekeeper.analytics.entity.UsageEvent;
import com.gatekeeper.analytics.repository.UsageEventRepository;
import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.apikey.entity.ApiKeyScope;
import com.gatekeeper.apikey.entity.ApiKeyStatus;
import com.gatekeeper.apikey.repository.ApiKeyRepository;
import com.gatekeeper.apikey.repository.ApiKeyScopeRepository;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.entity.ConsumerStatus;
import com.gatekeeper.consumer.repository.ApiConsumerRepository;
import com.gatekeeper.plan.entity.SubscriptionPlan;
import com.gatekeeper.plan.repository.SubscriptionPlanRepository;
import com.gatekeeper.security.entity.AdminUser;
import com.gatekeeper.security.repository.AdminUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
class DatabaseIntegrationTest {

    @Autowired
    private SubscriptionPlanRepository planRepository;

    @Autowired
    private ApiConsumerRepository consumerRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeyScopeRepository scopeRepository;

    @Autowired
    private UsageEventRepository usageEventRepository;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Test
    @DisplayName("Verify Flyway seed data for subscription plans")
    void testFlywaySeedPlans() {
        Optional<SubscriptionPlan> freePlan = planRepository.findByName("FREE");
        assertThat(freePlan).isPresent();
        assertThat(freePlan.get().getBucketCapacity()).isEqualTo(60L);
        assertThat(freePlan.get().getRefillTokens()).isEqualTo(60L);
        assertThat(freePlan.get().getRefillPeriodSeconds()).isEqualTo(60);
        assertThat(freePlan.get().isActive()).isTrue();

        Optional<SubscriptionPlan> proPlan = planRepository.findByName("PRO");
        assertThat(proPlan).isPresent();
        assertThat(proPlan.get().getBucketCapacity()).isEqualTo(600L);
        assertThat(proPlan.get().getRefillTokens()).isEqualTo(600L);

        Optional<SubscriptionPlan> enterprisePlan = planRepository.findByName("ENTERPRISE");
        assertThat(enterprisePlan).isPresent();
        assertThat(enterprisePlan.get().getBucketCapacity()).isEqualTo(6000L);
    }

    @Test
    @DisplayName("Verify ApiConsumer persistence and unique email constraint")
    void testConsumerPersistenceAndConstraints() {
        SubscriptionPlan plan = planRepository.findByName("FREE").orElseThrow();
        ApiConsumer consumer = new ApiConsumer("Acme Corp", "dev@acme.com", plan);
        ApiConsumer saved = consumerRepository.saveAndFlush(consumer);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(ConsumerStatus.ACTIVE);
        assertThat(saved.isDeleted()).isFalse();

        ApiConsumer duplicate = new ApiConsumer("Acme Duplicate", "dev@acme.com", plan);
        assertThatThrownBy(() -> consumerRepository.saveAndFlush(duplicate))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Verify ApiKey and ApiKeyScope persistence with prefix lookup")
    void testApiKeyAndScopePersistence() {
        SubscriptionPlan plan = planRepository.findByName("PRO").orElseThrow();
        ApiConsumer consumer = consumerRepository.saveAndFlush(new ApiConsumer("Beta Labs", "contact@beta.com", plan));

        ApiKey key = new ApiKey(consumer, "Production Key", "gk_live_1122334455667788", "e3b0c44298fc1c149afb4c72e0500001e3b0c44298fc1c149afb4c72e0500001");
        key.addScope("gateway:invoke");
        key.addScope("read:orders");

        ApiKey savedKey = apiKeyRepository.saveAndFlush(key);
        assertThat(savedKey.getId()).isNotNull();
        assertThat(savedKey.getVersion()).isEqualTo(0L);

        Optional<ApiKey> lookedUp = apiKeyRepository.findByKeyPrefix("gk_live_1122334455667788");
        assertThat(lookedUp).isPresent();
        assertThat(lookedUp.get().getName()).isEqualTo("Production Key");
        assertThat(lookedUp.get().getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);

        List<ApiKeyScope> scopes = scopeRepository.findByIdApiKeyId(savedKey.getId());
        assertThat(scopes).hasSize(2);
        assertThat(scopes).extracting(ApiKeyScope::getScope).containsExactlyInAnyOrder("gateway:invoke", "read:orders");

        ApiKey duplicatePrefix = new ApiKey(consumer, "Duplicate Key", "gk_live_1122334455667788", "digest2");
        assertThatThrownBy(() -> apiKeyRepository.saveAndFlush(duplicatePrefix))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Verify ApiKey digest-only persistence, expiration, and revocation lifecycle")
    void testApiKeyDigestOnlyAndLifecycle() {
        SubscriptionPlan plan = planRepository.findByName("FREE").orElseThrow();
        ApiConsumer consumer = consumerRepository.saveAndFlush(new ApiConsumer("Epsilon Corp", "eps@corp.com", plan));

        Instant futureExpiry = Instant.now().plus(90, ChronoUnit.DAYS);
        String digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        ApiKey key = new ApiKey(consumer, "Expiring Key", "gk_live_9988776655443322", digest);
        key.setExpiresAt(futureExpiry);
        key.revoke("Scheduled rotation");

        ApiKey saved = apiKeyRepository.saveAndFlush(key);

        assertThat(saved.getSecretDigest()).isEqualTo(digest);
        assertThat(saved.getSecretDigest()).hasSize(64);
        assertThat(saved.getExpiresAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(saved.getRevocationReason()).isEqualTo("Scheduled rotation");
        assertThat(saved.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("Verify optimistic locking on ApiKey updates")
    void testOptimisticLockingOnApiKey() {
        SubscriptionPlan plan = planRepository.findByName("FREE").orElseThrow();
        ApiConsumer consumer = consumerRepository.saveAndFlush(new ApiConsumer("Zeta Corp", "zeta@corp.com", plan));

        ApiKey key = new ApiKey(consumer, "Version Key", "gk_live_0011223344556677", "a".repeat(64));
        ApiKey saved = apiKeyRepository.saveAndFlush(key);
        assertThat(saved.getVersion()).isEqualTo(0L);

        saved.setName("Updated Version Key");
        ApiKey updated = apiKeyRepository.saveAndFlush(saved);
        assertThat(updated.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Verify UsageEvent with both nullable and associated foreign keys")
    void testUsageEventNullableAssociations() {
        SubscriptionPlan plan = planRepository.findByName("FREE").orElseThrow();
        ApiConsumer consumer = consumerRepository.saveAndFlush(new ApiConsumer("Gamma Corp", "ops@gamma.com", plan));
        ApiKey key = apiKeyRepository.saveAndFlush(new ApiKey(consumer, "Default Key", "gk_live_aabbccddeeff0011", "digest123"));

        UsageEvent authorizedEvent = UsageEvent.forAuthorized(consumer, key, "/api/v1/data", "GET", 200, 15);
        UsageEvent savedAuth = usageEventRepository.saveAndFlush(authorizedEvent);
        assertThat(savedAuth.getId()).isNotNull();
        assertThat(savedAuth.getConsumer()).isNotNull();
        assertThat(savedAuth.getApiKey()).isNotNull();
        assertThat(savedAuth.getConsumerEmailSnapshot()).isEqualTo("ops@gamma.com");
        assertThat(savedAuth.getKeyPrefixSnapshot()).isEqualTo("gk_live_aabbccddeeff0011");

        UsageEvent unauthEvent = UsageEvent.forRejected("/api/v1/data", "POST", 401, 2, false, "MISSING_KEY", null);
        UsageEvent savedUnauth = usageEventRepository.saveAndFlush(unauthEvent);
        assertThat(savedUnauth.getId()).isNotNull();
        assertThat(savedUnauth.getConsumer()).isNull();
        assertThat(savedUnauth.getApiKey()).isNull();
        assertThat(savedUnauth.getHttpStatus()).isEqualTo(401);

        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        List<UsageEvent> events = usageEventRepository.findByConsumerIdAndTimestampBetween(consumer.getId(), start, end);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("Verify AdminUser persistence and uniqueness")
    void testAdminUserPersistence() {
        AdminUser admin = new AdminUser("superadmin", "admin@gatekeeper.io", "$2a$12$e0MYzXy...", "ROLE_SUPER_ADMIN");
        AdminUser saved = adminUserRepository.saveAndFlush(admin);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isMustChangePassword()).isTrue();

        Optional<AdminUser> byUsername = adminUserRepository.findByUsername("superadmin");
        assertThat(byUsername).isPresent();
        assertThat(byUsername.get().getEmail()).isEqualTo("admin@gatekeeper.io");

        AdminUser duplicateUsername = new AdminUser("superadmin", "other@gatekeeper.io", "hash", "ROLE_ADMIN");
        assertThatThrownBy(() -> adminUserRepository.saveAndFlush(duplicateUsername))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
