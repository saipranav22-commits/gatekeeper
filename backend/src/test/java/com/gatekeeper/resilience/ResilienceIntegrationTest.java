package com.gatekeeper.resilience;

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
import com.gatekeeper.resilience.bulkhead.RouteBulkhead;
import com.gatekeeper.resilience.circuitbreaker.RouteCircuitBreaker;
import com.gatekeeper.resilience.engine.RouteResilienceEngine;
import com.gatekeeper.security.entity.AdminUser;
import com.gatekeeper.security.repository.AdminUserRepository;
import com.gatekeeper.security.service.JwtService;
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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@DisplayName("ResilienceIntegrationTest - Phase 5B")
class ResilienceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RouteResilienceEngine resilienceEngine;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private ApiConsumerRepository consumerRepository;

    @Autowired
    private SubscriptionPlanRepository planRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private String adminToken;
    private String consumerApiKey;
    private final List<UUID> createdConsumerIds = new ArrayList<>();
    private final List<UUID> createdAdminIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        createdConsumerIds.clear();
        createdAdminIds.clear();

        // 1. Create Admin user & token
        String adminSuffix = UUID.randomUUID().toString().substring(0, 8);
        AdminUser admin = new AdminUser("resilience_admin_" + adminSuffix, "admin_" + adminSuffix + "@test.local", "$2a$10$hash", "ROLE_ADMIN");
        admin.setActive(true);
        admin.setTokenVersion(1L);
        admin.setMustChangePassword(false);
        AdminUser savedAdmin = adminUserRepository.saveAndFlush(admin);
        createdAdminIds.add(savedAdmin.getId());
        adminToken = jwtService.generateToken(savedAdmin.getId(), savedAdmin.getUsername(), savedAdmin.getRole(), 1L);

        // 2. Create Consumer & API Key
        SubscriptionPlan plan = planRepository.findByName("PRO").orElseGet(() ->
            planRepository.save(new SubscriptionPlan("PRO", "Pro Tier", 100, 100, 60)));

        String consumerSuffix = UUID.randomUUID().toString().substring(0, 8);
        ApiConsumer consumer = new ApiConsumer("Resilience Test " + consumerSuffix, "resilience_" + consumerSuffix + "@test.local", plan);
        consumer = consumerRepository.save(consumer);
        createdConsumerIds.add(consumer.getId());

        CreateApiKeyRequest keyRequest = new CreateApiKeyRequest(
            consumer.getId(),
            "Resilience Test Key",
            Set.of("orders:read", "orders:write", "inventory:read"),
            null
        );
        ApiKeyCreationResult keyResult = apiKeyService.createApiKey(keyRequest);
        consumerApiKey = keyResult.plaintextKey();

        // 3. Ensure circuit breakers are clean
        RouteCircuitBreaker cb = resilienceEngine.getCircuitBreaker("orders-service-v1");
        if (cb != null) {
            cb.reset();
        }
    }

    @AfterEach
    void tearDown() {
        // Reset breakers
        RouteCircuitBreaker cb = resilienceEngine.getCircuitBreaker("orders-service-v1");
        if (cb != null) {
            cb.reset();
        }

        for (UUID consumerId : createdConsumerIds) {
            try {
                stringRedisTemplate.delete(RedisTokenBucketRateLimiter.KEY_PREFIX + consumerId);
                apiKeyRepository.deleteAll(apiKeyRepository.findByConsumerId(consumerId));
                consumerRepository.deleteById(consumerId);
            } catch (Exception ignored) {
            }
        }

        for (UUID adminId : createdAdminIds) {
            try {
                adminUserRepository.deleteById(adminId);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("GET /api/v1/resilience/routes without token returns HTTP 401")
    void getRoutesUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/resilience/routes"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/resilience/routes with admin token returns HTTP 200 and route list")
    void getRoutesAuthorized() throws Exception {
        mockMvc.perform(get("/api/v1/resilience/routes")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].routeId", hasItem("orders-service-v1")))
            .andExpect(jsonPath("$[*].routeId", hasItem("inventory-service-v1")));
    }

    @Test
    @DisplayName("GET /api/v1/resilience/routes/{routeId} returns detailed metrics and configuration")
    void getRouteDetail() throws Exception {
        mockMvc.perform(get("/api/v1/resilience/routes/orders-service-v1")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routeId", is("orders-service-v1")))
            .andExpect(jsonPath("$.circuitBreaker.state", is("CLOSED")))
            .andExpect(jsonPath("$.bulkhead.maxConcurrentCalls").isNumber())
            .andExpect(jsonPath("$.retry.maxAttempts").isNumber());
    }

    @Test
    @DisplayName("GET /api/v1/resilience/routes/{routeId} for non-existent route returns HTTP 404")
    void getRouteDetailNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/resilience/routes/non-existent-route")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Circuit Breaker OPEN fast-rejects requests with HTTP 503 CIRCUIT_OPEN")
    void circuitBreakerFastRejection() throws Exception {
        RouteCircuitBreaker cb = resilienceEngine.getCircuitBreaker("orders-service-v1");
        cb.transitionToOpen();

        mockMvc.perform(get("/api/v1/proxy/orders")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, consumerApiKey))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.message").value(containsString("Circuit breaker is OPEN")));

        // Verify rejected calls count incremented
        assertThat(cb.getMetrics().rejectedCalls()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/v1/resilience/routes/{routeId}/reset resets breaker to CLOSED")
    void resetCircuitBreaker() throws Exception {
        RouteCircuitBreaker cb = resilienceEngine.getCircuitBreaker("orders-service-v1");
        cb.transitionToOpen();

        mockMvc.perform(post("/api/v1/resilience/routes/orders-service-v1/reset")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.circuitState", is("CLOSED")));

        // After reset, request is admitted through circuit breaker
        // (will get 200 if upstream is live on 8081, or 502 if not live, but not 503 fast rejection)
        mockMvc.perform(get("/api/v1/proxy/orders")
                .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, consumerApiKey))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                org.assertj.core.api.Assertions.assertThat(status)
                    .as("Expected request to pass through circuit breaker (got 200 or 502, not 503 circuit open)")
                    .isIn(200, 502);
            });
    }

    @Test
    @DisplayName("Bulkhead saturation immediately fast-rejects with HTTP 503 BULKHEAD_REJECTED")
    void bulkheadSaturationRejection() throws Exception {
        RouteBulkhead bh = resilienceEngine.getBulkhead("orders-service-v1");
        int maxCalls = bh.getMaxConcurrentCalls();

        // Exhaust all permits manually
        List<Boolean> acquiredPermits = new ArrayList<>();
        for (int i = 0; i < maxCalls; i++) {
            acquiredPermits.add(bh.tryAcquirePermission());
        }

        try {
            // Next incoming request should be rejected by bulkhead
            mockMvc.perform(get("/api/v1/proxy/orders")
                    .header(ConsumerApiKeyAuthenticationFilter.API_KEY_HEADER, consumerApiKey))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(containsString("Bulkhead rejection applied")));
        } finally {
            // Release permits
            for (int i = 0; i < maxCalls; i++) {
                bh.releasePermission();
            }
        }
    }

    private static org.assertj.core.api.AbstractLongAssert<?> assertThat(long actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
