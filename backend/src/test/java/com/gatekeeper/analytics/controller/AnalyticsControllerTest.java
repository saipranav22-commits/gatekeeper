package com.gatekeeper.analytics.controller;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class AnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private ApiConsumerRepository consumerRepository;

    @Autowired
    private SubscriptionPlanRepository planRepository;

    @Autowired
    private JwtService jwtService;

    private String superAdminToken;
    private String adminToken;
    private String analystToken;
    private ApiConsumer testConsumer;

    @BeforeEach
    void setUp() {
        AdminUser superAdmin = new AdminUser("super_analytics", "super_ana@test.local", "$2a$10$hash", "ROLE_SUPER_ADMIN");
        superAdmin.setActive(true);
        superAdmin.setTokenVersion(1L);
        superAdmin.setMustChangePassword(false);
        AdminUser savedSuper = adminUserRepository.saveAndFlush(superAdmin);
        superAdminToken = jwtService.generateToken(savedSuper.getId(), savedSuper.getUsername(), savedSuper.getRole(), 1L);

        AdminUser admin = new AdminUser("lead_analytics", "admin_ana@test.local", "$2a$10$hash", "ROLE_ADMIN");
        admin.setActive(true);
        admin.setTokenVersion(1L);
        admin.setMustChangePassword(false);
        AdminUser savedAdmin = adminUserRepository.saveAndFlush(admin);
        adminToken = jwtService.generateToken(savedAdmin.getId(), savedAdmin.getUsername(), savedAdmin.getRole(), 1L);

        AdminUser analyst = new AdminUser("analyst_analytics", "analyst_ana@test.local", "$2a$10$hash", "ROLE_ANALYST");
        analyst.setActive(true);
        analyst.setTokenVersion(1L);
        analyst.setMustChangePassword(false);
        AdminUser savedAnalyst = adminUserRepository.saveAndFlush(analyst);
        analystToken = jwtService.generateToken(savedAnalyst.getId(), savedAnalyst.getUsername(), savedAnalyst.getRole(), 1L);

        SubscriptionPlan plan = planRepository.findAll().get(0);
        testConsumer = new ApiConsumer("Analytics-Controller-Test", "ana-ctrl@test.local", plan);
        testConsumer = consumerRepository.saveAndFlush(testConsumer);
    }

    @Test
    @DisplayName("GET /api/v1/analytics/overview succeeds for SUPER_ADMIN, ADMIN, and ANALYST")
    void testOverviewAuthorization() throws Exception {
        // SUPER_ADMIN
        mockMvc.perform(get("/api/v1/analytics/overview")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalRequests", greaterThanOrEqualTo(0)));

        // ADMIN
        mockMvc.perform(get("/api/v1/analytics/overview")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());

        // ANALYST
        mockMvc.perform(get("/api/v1/analytics/overview")
                .header("Authorization", "Bearer " + analystToken))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/analytics/overview rejected with 401 when unauthenticated")
    void testOverviewUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/analytics/overview rejected with 401 when using Consumer API Key")
    void testOverviewWithConsumerApiKeyRejected() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview")
                .header("X-API-Key", "gk_live_0123456789abcdef_validformatkey"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/analytics/timeseries returns bucketed series for ANALYST")
    void testTimeSeriesEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/timeseries")
                .param("granularity", "hourly")
                .header("Authorization", "Bearer " + analystToken))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/analytics/consumers/{id} returns consumer stats")
    void testConsumerAnalyticsEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/consumers/" + testConsumer.getId())
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.consumerId", is(testConsumer.getId().toString())));
    }

    @Test
    @DisplayName("GET /api/v1/analytics/consumers/{id}/usage returns paginated usage events")
    void testConsumerUsageEventsEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/consumers/" + testConsumer.getId() + "/usage")
                .param("page", "0")
                .param("size", "10")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page.size", is(10)));
    }

    @Test
    @DisplayName("GET /api/v1/analytics/top-consumers returns list ranked by volume")
    void testTopConsumersEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/top-consumers")
                .param("limit", "5")
                .header("Authorization", "Bearer " + analystToken))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/analytics/overview returns 400 Bad Request when start is after end")
    void testInvalidDateRange() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview")
                .param("start", "2026-11-20T00:00:00Z")
                .param("end", "2026-11-10T00:00:00Z")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("cannot be after end timestamp")));
    }

    @Test
    @DisplayName("GET /api/v1/analytics/consumers/{id} returns 404 Not Found for non-existent consumer")
    void testNonExistentConsumer() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/consumers/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNotFound());
    }
}
