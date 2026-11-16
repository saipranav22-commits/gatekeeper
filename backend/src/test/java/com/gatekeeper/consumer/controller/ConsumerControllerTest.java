package com.gatekeeper.consumer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.apikey.entity.ApiKeyStatus;
import com.gatekeeper.apikey.repository.ApiKeyRepository;
import com.gatekeeper.consumer.dto.ChangePlanRequest;
import com.gatekeeper.consumer.dto.CreateConsumerRequest;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.entity.ConsumerStatus;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class ConsumerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApiConsumerRepository consumerRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private SubscriptionPlanRepository planRepository;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private JwtService jwtService;

    private String superAdminToken;
    private String adminToken;
    private String analystToken;
    private SubscriptionPlan freePlan;
    private SubscriptionPlan proPlan;

    @BeforeEach
    void setUp() {
        AdminUser superAdmin = new AdminUser("super_consumer_admin", "super_cons@gatekeeper.local", "$2a$10$hash", "ROLE_SUPER_ADMIN");
        superAdmin.setActive(true);
        superAdmin.setTokenVersion(1L);
        superAdmin.setMustChangePassword(false);
        AdminUser savedSuper = adminUserRepository.saveAndFlush(superAdmin);
        superAdminToken = jwtService.generateToken(savedSuper.getId(), savedSuper.getUsername(), savedSuper.getRole(), 1L);

        AdminUser admin = new AdminUser("lead_consumer_admin", "admin_cons@gatekeeper.local", "$2a$10$hash", "ROLE_ADMIN");
        admin.setActive(true);
        admin.setTokenVersion(1L);
        admin.setMustChangePassword(false);
        AdminUser savedAdmin = adminUserRepository.saveAndFlush(admin);
        adminToken = jwtService.generateToken(savedAdmin.getId(), savedAdmin.getUsername(), savedAdmin.getRole(), 1L);

        AdminUser analyst = new AdminUser("analyst_consumer", "analyst_cons@gatekeeper.local", "$2a$10$hash", "ROLE_ANALYST");
        analyst.setActive(true);
        analyst.setTokenVersion(1L);
        analyst.setMustChangePassword(false);
        AdminUser savedAnalyst = adminUserRepository.saveAndFlush(analyst);
        analystToken = jwtService.generateToken(savedAnalyst.getId(), savedAnalyst.getUsername(), savedAnalyst.getRole(), 1L);

        freePlan = planRepository.findByName("FREE").orElseGet(() -> {
            SubscriptionPlan plan = new SubscriptionPlan("FREE", "Free tier", 60L, 60L, 60);
            return planRepository.saveAndFlush(plan);
        });

        proPlan = planRepository.findByName("PRO").orElseGet(() -> {
            SubscriptionPlan plan = new SubscriptionPlan("PRO", "Pro tier", 600L, 600L, 60);
            return planRepository.saveAndFlush(plan);
        });
    }

    @Test
    @DisplayName("POST /api/v1/consumers succeeds for ADMIN and returns 201 with Location header")
    void testCreateConsumerSuccess() throws Exception {
        CreateConsumerRequest request = new CreateConsumerRequest("Acme Test Corp", "acme_test@gatekeeper.local", freePlan.getId());

        mockMvc.perform(post("/api/v1/consumers")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.name", is("Acme Test Corp")))
            .andExpect(jsonPath("$.email", is("acme_test@gatekeeper.local")))
            .andExpect(jsonPath("$.status", is("ACTIVE")))
            .andExpect(jsonPath("$.plan.name", is("FREE")));
    }

    @Test
    @DisplayName("POST /api/v1/consumers with invalid input returns 400 Bad Request")
    void testCreateConsumerValidationFailure() throws Exception {
        CreateConsumerRequest invalid = new CreateConsumerRequest("", "invalid-email-string", null);

        mockMvc.perform(post("/api/v1/consumers")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalid)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status", is(400)))
            .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    @Test
    @DisplayName("POST /api/v1/consumers with duplicate email returns 409 Conflict")
    void testCreateConsumerDuplicateEmail() throws Exception {
        ApiConsumer existing = new ApiConsumer("Existing Corp", "dup_test@gatekeeper.local", freePlan);
        consumerRepository.saveAndFlush(existing);

        CreateConsumerRequest duplicate = new CreateConsumerRequest("Dup Corp", "dup_test@gatekeeper.local", freePlan.getId());

        mockMvc.perform(post("/api/v1/consumers")
                .header("Authorization", "Bearer " + superAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(duplicate)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status", is(409)))
            .andExpect(jsonPath("$.error", is("Conflict")));
    }

    @Test
    @DisplayName("POST /api/v1/consumers by ANALYST is rejected with 403 Forbidden")
    void testCreateConsumerAnalystForbidden() throws Exception {
        CreateConsumerRequest request = new CreateConsumerRequest("Forbidden Corp", "forbidden@gatekeeper.local", freePlan.getId());

        mockMvc.perform(post("/api/v1/consumers")
                .header("Authorization", "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status", is(403)));
    }

    @Test
    @DisplayName("GET /api/v1/consumers returns paginated list with deterministic ordering")
    void testListConsumersPagination() throws Exception {
        ApiConsumer c1 = new ApiConsumer("Corp One", "one@test.local", freePlan);
        ApiConsumer c2 = new ApiConsumer("Corp Two", "two@test.local", freePlan);
        consumerRepository.saveAndFlush(c1);
        consumerRepository.saveAndFlush(c2);

        mockMvc.perform(get("/api/v1/consumers")
                .param("page", "0")
                .param("size", "10")
                .param("sortBy", "name")
                .param("direction", "asc")
                .header("Authorization", "Bearer " + analystToken)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content.length()", greaterThanOrEqualTo(2)))
            .andExpect(jsonPath("$.page.size", is(10)))
            .andExpect(jsonPath("$.page.number", is(0)));
    }

    @Test
    @DisplayName("GET /api/v1/consumers/{consumerId} returns consumer details")
    void testGetConsumerById() throws Exception {
        ApiConsumer consumer = consumerRepository.saveAndFlush(new ApiConsumer("Lookup Corp", "lookup@test.local", freePlan));

        mockMvc.perform(get("/api/v1/consumers/{consumerId}", consumer.getId())
                .header("Authorization", "Bearer " + analystToken)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id", is(consumer.getId().toString())))
            .andExpect(jsonPath("$.name", is("Lookup Corp")));
    }

    @Test
    @DisplayName("PATCH /api/v1/consumers/{consumerId}/plan updates subscription plan")
    void testChangeConsumerPlan() throws Exception {
        ApiConsumer consumer = consumerRepository.saveAndFlush(new ApiConsumer("Plan Migrator", "migrate@test.local", freePlan));
        ChangePlanRequest request = new ChangePlanRequest(proPlan.getId());

        mockMvc.perform(patch("/api/v1/consumers/{consumerId}/plan", consumer.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plan.name", is("PRO")));
    }

    @Test
    @DisplayName("PATCH /api/v1/consumers/{consumerId}/suspend and /reactivate enforce lifecycle")
    void testSuspendAndReactivateLifecycle() throws Exception {
        ApiConsumer consumer = consumerRepository.saveAndFlush(new ApiConsumer("Lifecycle Corp", "lifecycle@test.local", freePlan));

        // 1. Suspend
        mockMvc.perform(patch("/api/v1/consumers/{consumerId}/suspend", consumer.getId())
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("SUSPENDED")));

        // 2. Reactivate
        mockMvc.perform(patch("/api/v1/consumers/{consumerId}/reactivate", consumer.getId())
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("DELETE /api/v1/consumers/{consumerId} performs soft delete, invalidates API keys, and subsequent lookup returns 404")
    void testSoftDeleteConsumer() throws Exception {
        ApiConsumer consumer = consumerRepository.saveAndFlush(new ApiConsumer("Delete Corp", "delete@test.local", freePlan));
        ApiKey key1 = apiKeyRepository.saveAndFlush(new ApiKey(consumer, "Key 1", "gk_live_1111222233334444", "digest1"));
        ApiKey key2 = apiKeyRepository.saveAndFlush(new ApiKey(consumer, "Key 2", "gk_live_5555666677778888", "digest2"));

        // Delete
        mockMvc.perform(delete("/api/v1/consumers/{consumerId}", consumer.getId())
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isNoContent());

        // Verify consumer in PostgreSQL
        ApiConsumer deletedConsumer = consumerRepository.findById(consumer.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(deletedConsumer.isDeleted()).isTrue();
        org.assertj.core.api.Assertions.assertThat(deletedConsumer.getStatus()).isEqualTo(com.gatekeeper.consumer.entity.ConsumerStatus.DEACTIVATED);
        org.assertj.core.api.Assertions.assertThat(deletedConsumer.getDeletedAt()).isNotNull();

        // Verify associated API keys in PostgreSQL
        ApiKey key1After = apiKeyRepository.findById(key1.getId()).orElseThrow();
        ApiKey key2After = apiKeyRepository.findById(key2.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(key1After.isDeleted()).isTrue();
        org.assertj.core.api.Assertions.assertThat(key1After.getStatus()).isEqualTo(ApiKeyStatus.DEACTIVATED);
        org.assertj.core.api.Assertions.assertThat(key2After.isDeleted()).isTrue();
        org.assertj.core.api.Assertions.assertThat(key2After.getStatus()).isEqualTo(ApiKeyStatus.DEACTIVATED);

        // Subsequent lookup must return 404
        mockMvc.perform(get("/api/v1/consumers/{consumerId}", consumer.getId())
                .header("Authorization", "Bearer " + analystToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    @DisplayName("Repeated DELETE /api/v1/consumers/{consumerId} on already soft-deleted consumer returns 404")
    void testRepeatedSoftDeleteReturns404() throws Exception {
        ApiConsumer consumer = consumerRepository.saveAndFlush(new ApiConsumer("Repeat Delete Corp", "repeat_delete@test.local", freePlan));

        // 1st delete succeeds with 204
        mockMvc.perform(delete("/api/v1/consumers/{consumerId}", consumer.getId())
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isNoContent());

        // 2nd delete on already deleted consumer must return 404
        mockMvc.perform(delete("/api/v1/consumers/{consumerId}", consumer.getId())
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    @DisplayName("DELETE /api/v1/consumers/{consumerId} forbidden for ROLE_ANALYST (HTTP 403)")
    void testSoftDeleteForbiddenForAnalyst() throws Exception {
        ApiConsumer consumer = consumerRepository.saveAndFlush(new ApiConsumer("Auth Delete Corp", "auth_delete@test.local", freePlan));

        mockMvc.perform(delete("/api/v1/consumers/{consumerId}", consumer.getId())
                .header("Authorization", "Bearer " + analystToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status", is(403)));
    }

    @Test
    @DisplayName("DELETE /api/v1/consumers/{consumerId} unauthorized without token (HTTP 401)")
    void testSoftDeleteUnauthorizedWithoutToken() throws Exception {
        ApiConsumer consumer = consumerRepository.saveAndFlush(new ApiConsumer("Unauth Delete Corp", "unauth_delete@test.local", freePlan));

        mockMvc.perform(delete("/api/v1/consumers/{consumerId}", consumer.getId()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status", is(401)));
    }
}
