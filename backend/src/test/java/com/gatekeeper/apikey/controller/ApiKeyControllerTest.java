package com.gatekeeper.apikey.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.apikey.dto.ApiKeyCreationRequest;
import com.gatekeeper.apikey.dto.RevokeApiKeyRequest;
import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.apikey.repository.ApiKeyRepository;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.repository.ApiConsumerRepository;
import com.gatekeeper.plan.entity.SubscriptionPlan;
import com.gatekeeper.plan.repository.SubscriptionPlanRepository;
import com.gatekeeper.security.entity.AdminUser;
import com.gatekeeper.security.repository.AdminUserRepository;
import com.gatekeeper.security.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class ApiKeyControllerTest {

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
    private AdminUserRepository adminUserRepository;

    @Autowired
    private JwtService jwtService;

    private String superAdminToken;
    private String adminToken;
    private String analystToken;
    private ApiConsumer testConsumer;
    private ApiConsumer otherConsumer;

    @BeforeEach
    void setUp() {
        AdminUser superAdmin = new AdminUser("super_key_admin", "super_key@gatekeeper.local", "$2a$10$hash", "ROLE_SUPER_ADMIN");
        superAdmin.setActive(true);
        superAdmin.setTokenVersion(1L);
        superAdmin.setMustChangePassword(false);
        AdminUser savedSuper = adminUserRepository.saveAndFlush(superAdmin);
        superAdminToken = jwtService.generateToken(savedSuper.getId(), savedSuper.getUsername(), savedSuper.getRole(), 1L);

        AdminUser admin = new AdminUser("lead_key_admin", "admin_key@gatekeeper.local", "$2a$10$hash", "ROLE_ADMIN");
        admin.setActive(true);
        admin.setTokenVersion(1L);
        admin.setMustChangePassword(false);
        AdminUser savedAdmin = adminUserRepository.saveAndFlush(admin);
        adminToken = jwtService.generateToken(savedAdmin.getId(), savedAdmin.getUsername(), savedAdmin.getRole(), 1L);

        AdminUser analyst = new AdminUser("analyst_key", "analyst_key@gatekeeper.local", "$2a$10$hash", "ROLE_ANALYST");
        analyst.setActive(true);
        analyst.setTokenVersion(1L);
        analyst.setMustChangePassword(false);
        AdminUser savedAnalyst = adminUserRepository.saveAndFlush(analyst);
        analystToken = jwtService.generateToken(savedAnalyst.getId(), savedAnalyst.getUsername(), savedAnalyst.getRole(), 1L);

        SubscriptionPlan plan = planRepository.findByName("PRO").orElseGet(() -> {
            SubscriptionPlan p = new SubscriptionPlan("PRO", "Pro tier", 600L, 600L, 60);
            return planRepository.saveAndFlush(p);
        });

        testConsumer = consumerRepository.saveAndFlush(new ApiConsumer("Key Test Consumer", "keytest@gatekeeper.local", plan));
        otherConsumer = consumerRepository.saveAndFlush(new ApiConsumer("Other Consumer", "other@gatekeeper.local", plan));
    }

    @Test
    @DisplayName("POST /api/v1/consumers/{consumerId}/keys creates key, returns 201, Cache-Control: no-store, and plaintext key")
    void testCreateApiKeySuccess() throws Exception {
        ApiKeyCreationRequest request = new ApiKeyCreationRequest(
            "Primary Integration Key",
            Set.of("gateway:invoke", "read:orders"),
            Instant.now().plus(30, ChronoUnit.DAYS)
        );

        MvcResult result = mockMvc.perform(post("/api/v1/consumers/{consumerId}/keys", testConsumer.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.metadata.id").isNotEmpty())
            .andExpect(jsonPath("$.metadata.name", is("Primary Integration Key")))
            .andExpect(jsonPath("$.metadata.keyPrefix", startsWith("gk_live_")))
            .andExpect(jsonPath("$.metadata.status", is("ACTIVE")))
            .andExpect(jsonPath("$.metadata.scopes.length()", is(2)))
            .andExpect(jsonPath("$.plaintextKey", startsWith("gk_live_")))
            .andExpect(jsonPath("$.metadata.secretDigest").doesNotExist()) // Never expose secret digests
            .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("plaintextKey");
    }

    @Test
    @DisplayName("GET /api/v1/consumers/{consumerId}/keys returns sanitized metadata without plaintext key or secret digests")
    void testListKeysSanitizedMetadata() throws Exception {
        // Create an API key first
        ApiKey key = new ApiKey(testConsumer, "Listed Key", "gk_live_listprefix12", "hashed_digest_value_12345");
        key.addScope("gateway:invoke");
        apiKeyRepository.saveAndFlush(key);

        mockMvc.perform(get("/api/v1/consumers/{consumerId}/keys", testConsumer.getId())
                .header("Authorization", "Bearer " + analystToken)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$[0].name", is("Listed Key")))
            .andExpect(jsonPath("$[0].keyPrefix", is("gk_live_listprefix12")))
            .andExpect(jsonPath("$[0].status", is("ACTIVE")))
            .andExpect(jsonPath("$[0].plaintextKey").doesNotExist())
            .andExpect(jsonPath("$[0].secretDigest").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/consumers/{consumerId}/keys/{keyId} returns single key metadata")
    void testGetKeyByIdSuccess() throws Exception {
        ApiKey key = new ApiKey(testConsumer, "Single Key", "gk_live_singlepref12", "hashed_digest_value_99999");
        key.addScope("gateway:invoke");
        ApiKey saved = apiKeyRepository.saveAndFlush(key);

        mockMvc.perform(get("/api/v1/consumers/{consumerId}/keys/{keyId}", testConsumer.getId(), saved.getId())
                .header("Authorization", "Bearer " + analystToken)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id", is(saved.getId().toString())))
            .andExpect(jsonPath("$.name", is("Single Key")))
            .andExpect(jsonPath("$.plaintextKey").doesNotExist())
            .andExpect(jsonPath("$.secretDigest").doesNotExist());
    }

    @Test
    @DisplayName("Cross-consumer access prevention: accessing key belonging to other consumer returns 404")
    void testCrossConsumerAccessPrevention() throws Exception {
        ApiKey keyBelongingToOther = new ApiKey(otherConsumer, "Other Key", "gk_live_otherprefix1", "digest_val");
        ApiKey saved = apiKeyRepository.saveAndFlush(keyBelongingToOther);

        // Attempting to access otherConsumer's key using testConsumer's URL
        mockMvc.perform(get("/api/v1/consumers/{consumerId}/keys/{keyId}", testConsumer.getId(), saved.getId())
                .header("Authorization", "Bearer " + superAdminToken)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    @DisplayName("POST /api/v1/consumers/{consumerId}/keys/{keyId}/revoke revokes key with audit reason")
    void testRevokeKeySuccess() throws Exception {
        ApiKey key = new ApiKey(testConsumer, "Revocable Key", "gk_live_revokepref1", "digest_val");
        ApiKey saved = apiKeyRepository.saveAndFlush(key);

        RevokeApiKeyRequest request = new RevokeApiKeyRequest("Key compromised during pen-test");

        mockMvc.perform(post("/api/v1/consumers/{consumerId}/keys/{keyId}/revoke", testConsumer.getId(), saved.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("REVOKED")))
            .andExpect(jsonPath("$.revocationReason", is("Key compromised during pen-test")))
            .andExpect(jsonPath("$.revokedAt").isNotEmpty());

        // Repeated revocation is idempotent and returns 200
        mockMvc.perform(post("/api/v1/consumers/{consumerId}/keys/{keyId}/revoke", testConsumer.getId(), saved.getId())
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("REVOKED")));
    }

    @Test
    @DisplayName("Scope validation: invalid scope formats are rejected with 400 Bad Request")
    void testInvalidScopeFormatRejected() throws Exception {
        ApiKeyCreationRequest request = new ApiKeyCreationRequest(
            "Bad Scope Key",
            Set.of("INVALID_SCOPE_UPPERCASE"),
            Instant.now().plus(10, ChronoUnit.DAYS)
        );

        mockMvc.perform(post("/api/v1/consumers/{consumerId}/keys", testConsumer.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status", is(400)))
            .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    @Test
    @DisplayName("Expiration validation: past timestamp is rejected with 400 Bad Request")
    void testPastExpirationRejected() throws Exception {
        ApiKeyCreationRequest request = new ApiKeyCreationRequest(
            "Expired Key",
            Set.of("gateway:invoke"),
            Instant.now().minus(1, ChronoUnit.DAYS)
        );

        mockMvc.perform(post("/api/v1/consumers/{consumerId}/keys", testConsumer.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("ANALYST role attempting key creation is rejected with 403 Forbidden")
    void testAnalystKeyCreationForbidden() throws Exception {
        ApiKeyCreationRequest request = new ApiKeyCreationRequest(
            "Analyst Key",
            Set.of("gateway:invoke"),
            Instant.now().plus(1, ChronoUnit.DAYS)
        );

        mockMvc.perform(post("/api/v1/consumers/{consumerId}/keys", testConsumer.getId())
                .header("Authorization", "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status", is(403)));
    }
}
