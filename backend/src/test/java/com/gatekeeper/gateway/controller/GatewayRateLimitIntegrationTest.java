package com.gatekeeper.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.apikey.dto.ApiKeyCreationResult;
import com.gatekeeper.apikey.dto.CreateApiKeyRequest;
import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.apikey.entity.ApiKeyStatus;
import com.gatekeeper.apikey.repository.ApiKeyRepository;
import com.gatekeeper.apikey.service.ApiKeyService;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.entity.ConsumerStatus;
import com.gatekeeper.consumer.repository.ApiConsumerRepository;
import com.gatekeeper.gateway.filter.ConsumerApiKeyAuthenticationFilter;
import com.gatekeeper.plan.entity.SubscriptionPlan;
import com.gatekeeper.plan.repository.SubscriptionPlanRepository;
import com.gatekeeper.ratelimit.service.RedisTokenBucketRateLimiter;
import com.gatekeeper.security.dto.AdminJwtClaims;
import com.gatekeeper.security.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class GatewayRateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApiConsumerRepository consumerRepository;

    @Autowired
    private SubscriptionPlanRepository planRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JwtService jwtService;

    private SubscriptionPlan freePlan;
    private SubscriptionPlan proPlan;

    private final List<UUID> createdConsumerIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        createdConsumerIds.clear();
        freePlan = planRepository.findByName("FREE").orElseGet(() ->
            planRepository.save(new SubscriptionPlan("FREE", "Free Tier", 60, 60, 60)));
        proPlan = planRepository.findByName("PRO").orElseGet(() ->
            planRepository.save(new SubscriptionPlan("PRO", "Pro Tier", 600, 600, 60)));
    }

    @AfterEach
    void tearDown() {
        for (UUID consumerId : createdConsumerIds) {
            stringRedisTemplate.delete(RedisTokenBucketRateLimiter.KEY_PREFIX + consumerId);
            List<ApiKey> keys = apiKeyRepository.findByConsumerId(consumerId);
            apiKeyRepository.deleteAll(keys);
            consumerRepository.deleteById(consumerId);
        }
    }

    private ApiConsumer createConsumer(SubscriptionPlan plan) {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        ApiConsumer consumer = new ApiConsumer("Test Consumer " + uniqueSuffix, "consumer_" + uniqueSuffix + "@example.com", plan);
        ApiConsumer saved = consumerRepository.save(consumer);
        createdConsumerIds.add(saved.getId());
        return saved;
    }

    private ApiKeyCreationResult createApiKey(ApiConsumer consumer, Set<String> scopes) {
        CreateApiKeyRequest request = new CreateApiKeyRequest(consumer.getId(), "Test Key", scopes, null);
        return apiKeyService.createApiKey(request);
    }

    @Test
    @DisplayName("Scenario 1: Valid API key authenticates successfully with RateLimit headers")
    void testValidApiKeyAuthenticatesSuccessfully() throws Exception {
        ApiConsumer consumer = createConsumer(freePlan);
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("gateway:invoke"));

        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.service").value("gateway-demo"))
            .andExpect(jsonPath("$.consumerId").value(consumer.getId().toString()))
            .andExpect(jsonPath("$.plan").value("FREE"))
            .andExpect(header().string("RateLimit-Limit", "60"))
            .andExpect(header().string("RateLimit-Remaining", "59"))
            .andExpect(header().exists("RateLimit-Reset"));
    }

    @Test
    @DisplayName("Scenario 2: Missing API key returns HTTP 401 Unauthorized")
    void testMissingApiKeyReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/gateway/demo"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.error").value("Unauthorized"))
            .andExpect(jsonPath("$.message").value(containsString("Missing API key")));
    }

    @Test
    @DisplayName("Scenario 3: Malformed API key returns HTTP 401 Unauthorized")
    void testMalformedApiKeyReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, "invalid_garbage_key"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.error").value("Unauthorized"))
            .andExpect(jsonPath("$.message").value(containsString("Invalid")));
    }

    @Test
    @DisplayName("Scenario 4: Non-existent unknown API key returns HTTP 401 Unauthorized")
    void testUnknownApiKeyReturns401() throws Exception {
        // Correct length (68 chars) but non-existent
        String fakeKey = "gk_live_0000000000000000_1234567890123456789012345678901234567890123";
        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, fakeKey))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Scenario 5: Multiple API key headers returns HTTP 401")
    void testMultipleApiKeyHeadersReturns401() throws Exception {
        ApiConsumer consumer = createConsumer(freePlan);
        ApiKeyCreationResult key = createApiKey(consumer, Set.of("gateway:invoke"));

        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, key.plaintextKey())
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, key.plaintextKey()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value(containsString("Multiple API key headers")));
    }

    @Test
    @DisplayName("Scenario 6: Revoked API key returns HTTP 401 Unauthorized")
    void testRevokedApiKeyReturns401() throws Exception {
        ApiConsumer consumer = createConsumer(freePlan);
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("gateway:invoke"));

        // Revoke the key
        apiKeyService.revokeKey(keyResult.metadata().id(), "Security audit test");

        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Scenario 7: Expired API key returns HTTP 401 Unauthorized")
    void testExpiredApiKeyReturns401() throws Exception {
        ApiConsumer consumer = createConsumer(freePlan);
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("gateway:invoke"));

        // Force expired timestamp in DB
        ApiKey apiKey = apiKeyRepository.findById(keyResult.metadata().id()).orElseThrow();
        apiKey.setExpiresAt(Instant.now().minusSeconds(3600));
        apiKeyRepository.save(apiKey);

        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Scenario 8: Suspended consumer returns HTTP 401 Unauthorized")
    void testSuspendedConsumerReturns401() throws Exception {
        ApiConsumer consumer = createConsumer(freePlan);
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("gateway:invoke"));

        // Suspend consumer
        consumer.setStatus(ConsumerStatus.SUSPENDED);
        consumerRepository.save(consumer);

        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Scenario 9: Deleted consumer returns HTTP 401 Unauthorized")
    void testDeletedConsumerReturns401() throws Exception {
        ApiConsumer consumer = createConsumer(freePlan);
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("gateway:invoke"));

        // Soft delete consumer
        consumer.setDeleted(true);
        consumerRepository.save(consumer);

        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Scenario 10: Valid API key lacking required scope returns HTTP 403 Forbidden without consuming tokens")
    void testValidKeyLackingScopeReturns403() throws Exception {
        ApiConsumer consumer = createConsumer(freePlan);
        // Key created with read:orders scope, NOT gateway:invoke
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("read:orders"));

        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.error").value("Forbidden"))
            .andExpect(jsonPath("$.message").value(containsString("insufficient scope")));

        // Verify Redis bucket was NOT created / decremented
        String bucketKey = RedisTokenBucketRateLimiter.KEY_PREFIX + consumer.getId();
        Boolean exists = stringRedisTemplate.hasKey(bucketKey);
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("Scenario 11: Scope authorization on second endpoint /orders")
    void testOrdersScopeAuthorizedEndpoint() throws Exception {
        ApiConsumer consumer = createConsumer(freePlan);
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("read:orders"));

        // Calling /orders with read:orders scope succeeds
        mockMvc.perform(get("/api/v1/gateway/orders")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.service").value("orders-service"))
            .andExpect(header().string("RateLimit-Remaining", "59"));
    }

    @Test
    @DisplayName("Scenario 12: Sequential consumption drains tokens accurately")
    void testSequentialConsumptionDrainsTokens() throws Exception {
        ApiConsumer consumer = createConsumer(freePlan);
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("gateway:invoke"));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/gateway/demo")
                    .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
                .andExpect(status().isOk())
                .andExpect(header().string("RateLimit-Remaining", String.valueOf(59 - i)));
        }
    }

    @Test
    @DisplayName("Scenario 13: Quota exhaustion returns HTTP 429 Too Many Requests with Retry-After")
    void testQuotaExhaustionReturns429() throws Exception {
        // Create small plan: 3 tokens capacity, 1 hour refill so negligible refill during test
        SubscriptionPlan tinyPlan = planRepository.save(new SubscriptionPlan("TINY_" + UUID.randomUUID().toString().substring(0, 6), "Tiny", 3, 3, 3600));
        ApiConsumer consumer = createConsumer(tinyPlan);
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("gateway:invoke"));

        // Consume all 3 tokens
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/gateway/demo")
                    .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
                .andExpect(status().isOk());
        }

        // 4th request must be rejected with 429
        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.error").value("Too Many Requests"))
            .andExpect(header().string("RateLimit-Remaining", "0"))
            .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("Scenario 14: Multiple API keys for same consumer share rate-limit quota")
    void testMultiKeyConsumerQuotaSharing() throws Exception {
        ApiConsumer consumer = createConsumer(freePlan);
        ApiKeyCreationResult keyA = createApiKey(consumer, Set.of("gateway:invoke"));
        ApiKeyCreationResult keyB = createApiKey(consumer, Set.of("gateway:invoke"));

        // Key A consumes 1 token (remaining = 59)
        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyA.plaintextKey()))
            .andExpect(status().isOk())
            .andExpect(header().string("RateLimit-Remaining", "59"));

        // Key B consumes 1 token: shares bucket, so remaining = 58!
        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyB.plaintextKey()))
            .andExpect(status().isOk())
            .andExpect(header().string("RateLimit-Remaining", "58"));
    }

    @Test
    @DisplayName("Scenario 15: Different consumers have completely isolated quotas")
    void testConsumerQuotaIsolation() throws Exception {
        SubscriptionPlan tinyPlan = planRepository.save(new SubscriptionPlan("TINY_ISO_" + UUID.randomUUID().toString().substring(0, 6), "Tiny", 2, 2, 3600));
        ApiConsumer consumerA = createConsumer(tinyPlan);
        ApiConsumer consumerB = createConsumer(freePlan);

        ApiKeyCreationResult keyA = createApiKey(consumerA, Set.of("gateway:invoke"));
        ApiKeyCreationResult keyB = createApiKey(consumerB, Set.of("gateway:invoke"));

        // Drain Consumer A
        mockMvc.perform(get("/api/v1/gateway/demo").header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyA.plaintextKey())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/gateway/demo").header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyA.plaintextKey())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/gateway/demo").header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyA.plaintextKey())).andExpect(status().isTooManyRequests());

        // Consumer B is completely unaffected
        mockMvc.perform(get("/api/v1/gateway/demo").header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyB.plaintextKey()))
            .andExpect(status().isOk())
            .andExpect(header().string("RateLimit-Remaining", "59"));
    }

    @Test
    @DisplayName("Scenario 16: Admin JWT cannot access consumer gateway endpoints")
    void testAdminJwtCannotAccessGatewayEndpoints() throws Exception {
        UUID adminId = UUID.randomUUID();
        String adminJwt = jwtService.generateToken(adminId, "superadmin", "ROLE_SUPER_ADMIN", 1L);

        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value(containsString("Missing API key")));
    }

    @Test
    @DisplayName("Scenario 17: Consumer API key cannot access administrative management endpoints")
    void testConsumerKeyCannotAccessManagementEndpoints() throws Exception {
        ApiConsumer consumer = createConsumer(freePlan);
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("gateway:invoke"));

        mockMvc.perform(get("/api/v1/consumers")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Scenario 18: Public health endpoint remains accessible without credentials")
    void testHealthEndpointRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Scenario 19: No sensitive credentials leaked in error responses")
    void testNoCredentialLeakageInErrors() throws Exception {
        mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, "malformed_key"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value(not(containsString("password"))))
            .andExpect(jsonPath("$.message").value(not(containsString("secret"))))
            .andExpect(jsonPath("$.message").value(not(containsString("digest"))));
    }

    @Test
    @DisplayName("Scenario 20: Concurrent HTTP requests against capacity 60 do not overspend")
    void testConcurrentHttpRequestsDoNotOverspend() throws Exception {
        int totalRequests = 100;
        int capacity = 60;
        SubscriptionPlan concPlan = planRepository.save(new SubscriptionPlan("CONC_" + UUID.randomUUID().toString().substring(0, 6), "Conc", capacity, capacity, 3600));
        ApiConsumer consumer = createConsumer(concPlan);
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("gateway:invoke"));

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult result = mockMvc.perform(get("/api/v1/gateway/demo")
                            .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
                        .andReturn();

                    int statusCode = result.getResponse().getStatus();
                    if (statusCode == 200) {
                        allowedCount.incrementAndGet();
                    } else if (statusCode == 429) {
                        deniedCount.incrementAndGet();
                    } else {
                        System.err.println("Unexpected status: " + statusCode + ", body: " + result.getResponse().getContentAsString());
                    }
                } catch (Exception e) {
                    System.err.println("Task exception: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(allowedCount.get()).isEqualTo(60);
        assertThat(deniedCount.get()).isEqualTo(40);
    }
}
