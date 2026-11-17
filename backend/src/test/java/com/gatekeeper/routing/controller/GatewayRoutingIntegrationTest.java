package com.gatekeeper.routing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.apikey.dto.ApiKeyCreationResult;
import com.gatekeeper.apikey.dto.CreateApiKeyRequest;
import com.gatekeeper.apikey.repository.ApiKeyRepository;
import com.gatekeeper.apikey.service.ApiKeyService;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.repository.ApiConsumerRepository;
import com.gatekeeper.gateway.filter.ConsumerApiKeyAuthenticationFilter;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@DisplayName("GatewayRoutingIntegrationTest - Phase 5A")
class GatewayRoutingIntegrationTest {

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

    private final List<UUID> createdConsumerIds = new ArrayList<>();
    private SubscriptionPlan testPlan;
    private ApiConsumer testConsumer;
    private String ordersReadKey;
    private String fullAccessKey;

    @BeforeEach
    void setUp() {
        createdConsumerIds.clear();
        testPlan = planRepository.findByName("PRO").orElseGet(() ->
            planRepository.save(new SubscriptionPlan("PRO", "Pro Tier", 100, 100, 60)));

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        testConsumer = new ApiConsumer(
            "Routing Test " + suffix,
            "routing_" + suffix + "@example.com",
            testPlan
        );
        testConsumer = consumerRepository.save(testConsumer);
        createdConsumerIds.add(testConsumer.getId());

        // Create key with read-only scope: orders:read, inventory:read
        CreateApiKeyRequest readRequest = new CreateApiKeyRequest(
            testConsumer.getId(),
            "Orders Read Key",
            Set.of("orders:read", "inventory:read"),
            null
        );
        ApiKeyCreationResult readKeyResult = apiKeyService.createApiKey(readRequest);
        ordersReadKey = readKeyResult.plaintextKey();

        // Create key with full access: orders:read, orders:write, inventory:read
        CreateApiKeyRequest fullRequest = new CreateApiKeyRequest(
            testConsumer.getId(),
            "Full Access Key",
            Set.of("orders:read", "orders:write", "inventory:read"),
            null
        );
        ApiKeyCreationResult fullKeyResult = apiKeyService.createApiKey(fullRequest);
        fullAccessKey = fullKeyResult.plaintextKey();
    }

    @AfterEach
    void tearDown() {
        for (UUID consumerId : createdConsumerIds) {
            try {
                stringRedisTemplate.delete(RedisTokenBucketRateLimiter.KEY_PREFIX + consumerId);
                apiKeyRepository.deleteAll(apiKeyRepository.findByConsumerId(consumerId));
                consumerRepository.deleteById(consumerId);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("Missing API key on /api/v1/proxy/orders returns HTTP 401")
    void shouldReturn401WhenApiKeyMissing() throws Exception {
        mockMvc.perform(get("/api/v1/proxy/orders"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.message").value(containsString("Missing API key")));
    }

    @Test
    @DisplayName("Invalid API key returns HTTP 401")
    void shouldReturn401WhenApiKeyInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/proxy/orders")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, "gk_live_00000000000000000000000000000000"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Non-existent proxy route returns HTTP 404")
    void shouldReturn404ForUnknownRoute() throws Exception {
        mockMvc.perform(get("/api/v1/proxy/nonexistent/service")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, ordersReadKey))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.message").value(containsString("No matching route found")));
    }

    @Test
    @DisplayName("Path traversal attack on proxy returns HTTP 400")
    void shouldReturn400ForPathTraversal() throws Exception {
        mockMvc.perform(get("/api/v1/proxy/orders/..%2fadmin")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, ordersReadKey))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Scope enforcement: POST /api/v1/proxy/orders requires orders:write, returns 403 with orders:read key")
    void shouldReturn403WhenScopeInsufficient() throws Exception {
        mockMvc.perform(post("/api/v1/proxy/orders")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, ordersReadKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"CUST-1\",\"items\":[]}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.message").value(containsString("insufficient scope")));
    }

    @Test
    @DisplayName("Method enforcement: DELETE on Inventory route returns HTTP 405")
    void shouldReturn405WhenMethodDisallowed() throws Exception {
        mockMvc.perform(delete("/api/v1/proxy/inventory/SKU-1001")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, ordersReadKey))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.status").value(405))
            .andExpect(jsonPath("$.message").value(containsString("HTTP method 'DELETE' not allowed")));
    }

    @Test
    @DisplayName("Rate-limit headers and upstream dispatch with valid scope")
    void shouldEnforceRateLimitAndAttemptForwarding() throws Exception {
        // Since upstream orders service is not running on 8081 during this isolated test,
        // it should pass rate limit checks, attach rate-limit headers, and return HTTP 502 Bad Gateway
        mockMvc.perform(get("/api/v1/proxy/orders")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, ordersReadKey))
            .andExpect(header().exists("RateLimit-Limit"))
            .andExpect(header().exists("RateLimit-Remaining"))
            .andExpect(header().exists("RateLimit-Reset"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.status").value(502));
    }
}
