package com.gatekeeper.gateway.filter;

import com.gatekeeper.analytics.entity.UsageEvent;
import com.gatekeeper.analytics.pipeline.UsageEventBatchConsumer;
import com.gatekeeper.analytics.repository.UsageEventRepository;
import com.gatekeeper.apikey.dto.ApiKeyCreationResult;
import com.gatekeeper.apikey.dto.CreateApiKeyRequest;
import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.apikey.repository.ApiKeyRepository;
import com.gatekeeper.apikey.service.ApiKeyService;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.repository.ApiConsumerRepository;
import com.gatekeeper.plan.entity.SubscriptionPlan;
import com.gatekeeper.plan.repository.SubscriptionPlanRepository;
import com.gatekeeper.ratelimit.service.RedisTokenBucketRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class GatewayUsageTrackingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ApiConsumerRepository consumerRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private SubscriptionPlanRepository planRepository;

    @Autowired
    private UsageEventRepository usageEventRepository;

    @Autowired
    private UsageEventBatchConsumer batchConsumer;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private SubscriptionPlan testPlan;
    private final List<UUID> createdConsumerIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        testPlan = planRepository.findByName("FREE").orElseGet(() ->
            planRepository.save(new SubscriptionPlan("FREE", "Free Tier", 60, 60, 60)));
    }

    @AfterEach
    void tearDown() {
        for (UUID consumerId : createdConsumerIds) {
            stringRedisTemplate.delete(RedisTokenBucketRateLimiter.KEY_PREFIX + consumerId);
            List<ApiKey> keys = apiKeyRepository.findByConsumerId(consumerId);
            apiKeyRepository.deleteAll(keys);
            List<UsageEvent> events = usageEventRepository.findByConsumerIdAndTimestampBetween(
                consumerId, Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS));
            usageEventRepository.deleteAll(events);
            consumerRepository.deleteById(consumerId);
        }
    }

    private ApiConsumer createConsumer() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        ApiConsumer consumer = new ApiConsumer("Telemetry Consumer " + uniqueSuffix, "telemetry_" + uniqueSuffix + "@example.com", testPlan);
        ApiConsumer saved = consumerRepository.save(consumer);
        createdConsumerIds.add(saved.getId());
        return saved;
    }

    private ApiKeyCreationResult createApiKey(ApiConsumer consumer, Set<String> scopes) {
        CreateApiKeyRequest request = new CreateApiKeyRequest(consumer.getId(), "Telemetry Key", scopes, null);
        return apiKeyService.createApiKey(request);
    }

    @Test
    @DisplayName("Successful gateway request captures ALLOWED event and returns correlation ID header")
    void testSuccessfulGatewayRequestTelemetry() throws Exception {
        ApiConsumer consumer = createConsumer();
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("gateway:invoke"));

        MvcResult result = mockMvc.perform(get("/api/v1/gateway/demo")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
            .andExpect(status().isOk())
            .andExpect(header().exists(GatewayUsageTrackingFilter.CORRELATION_ID_HEADER))
            .andReturn();

        String correlationId = result.getResponse().getHeader(GatewayUsageTrackingFilter.CORRELATION_ID_HEADER);
        assertThat(correlationId).isNotBlank();

        // Allow async publisher to enqueue and consume batch
        TimeUnit.MILLISECONDS.sleep(250);
        batchConsumer.pollAndPersistBatch();

        List<UsageEvent> events = usageEventRepository.findByConsumerIdAndTimestampBetween(
            consumer.getId(), Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(events).isNotEmpty();
        UsageEvent event = events.get(0);
        assertThat(event.getEndpointPath()).isEqualTo("/api/v1/gateway/demo");
        assertThat(event.getHttpStatus()).isEqualTo(200);
        assertThat(event.getOutcomeCategory()).isEqualTo("ALLOWED");
        assertThat(event.getCorrelationId()).isEqualTo(correlationId);
        assertThat(event.getRateLimitPlan()).isEqualTo("FREE");

        // Verify API key attribution and asynchronous lastUsedAt update
        assertThat(keyResult.metadata().id()).isNotNull();
        assertThat(event.getApiKey()).isNotNull();
        assertThat(event.getApiKey().getId()).isEqualTo(keyResult.metadata().id());

        ApiKey updatedKey = apiKeyRepository.findById(keyResult.metadata().id()).orElseThrow();
        assertThat(updatedKey.getLastUsedAt()).isNotNull();

        List<com.gatekeeper.apikey.dto.ApiKeyMetadataDto> keyDtos = apiKeyService.listKeysForConsumer(consumer.getId());
        assertThat(keyDtos).isNotEmpty();
        assertThat(keyDtos.get(0).lastUsedAt()).isNotNull();
        assertThat(keyDtos.get(0).lastUsedAt()).isEqualTo(updatedKey.getLastUsedAt());
    }

    @Test
    @DisplayName("Scope denied request captures SCOPE_DENIED event with HTTP 403")
    void testScopeDeniedGatewayRequestTelemetry() throws Exception {
        ApiConsumer consumer = createConsumer();
        // Missing 'read:orders' scope for /api/v1/gateway/orders
        ApiKeyCreationResult keyResult = createApiKey(consumer, Set.of("gateway:invoke"));

        MvcResult result = mockMvc.perform(get("/api/v1/gateway/orders")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, keyResult.plaintextKey()))
            .andExpect(status().isForbidden())
            .andReturn();

        String correlationId = result.getResponse().getHeader(GatewayUsageTrackingFilter.CORRELATION_ID_HEADER);

        TimeUnit.MILLISECONDS.sleep(250);
        batchConsumer.pollAndPersistBatch();

        List<UsageEvent> events = usageEventRepository.findByConsumerIdAndTimestampBetween(
            consumer.getId(), Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(events).isNotEmpty();
        UsageEvent event = events.get(0);
        assertThat(event.getEndpointPath()).isEqualTo("/api/v1/gateway/orders");
        assertThat(event.getHttpStatus()).isEqualTo(403);
        assertThat(event.getOutcomeCategory()).isEqualTo("SCOPE_DENIED");
        assertThat(event.getCorrelationId()).isEqualTo(correlationId);
    }

    @Test
    @DisplayName("Anonymous request with missing API key does not fabricate consumer event")
    void testMissingApiKeyTelemetry() throws Exception {
        long beforeCount = usageEventRepository.count();

        mockMvc.perform(get("/api/v1/gateway/demo"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().exists(GatewayUsageTrackingFilter.CORRELATION_ID_HEADER));

        TimeUnit.MILLISECONDS.sleep(250);
        batchConsumer.pollAndPersistBatch();

        long afterCount = usageEventRepository.count();
        // Must NOT fabricate a consumer event
        assertThat(afterCount).isEqualTo(beforeCount);
    }
}
