package com.gatekeeper.plan.controller;

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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class PlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubscriptionPlanRepository planRepository;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private JwtService jwtService;

    private String superAdminToken;
    private String analystToken;
    private SubscriptionPlan freePlan;

    @BeforeEach
    void setUp() {
        AdminUser superAdmin = new AdminUser("super_plan_admin", "super_plan@gatekeeper.local", "$2a$10$hash", "ROLE_SUPER_ADMIN");
        superAdmin.setActive(true);
        superAdmin.setTokenVersion(1L);
        superAdmin.setMustChangePassword(false);
        AdminUser savedSuper = adminUserRepository.saveAndFlush(superAdmin);
        superAdminToken = jwtService.generateToken(savedSuper.getId(), savedSuper.getUsername(), savedSuper.getRole(), 1L);

        AdminUser analyst = new AdminUser("analyst_plan_admin", "analyst_plan@gatekeeper.local", "$2a$10$hash", "ROLE_ANALYST");
        analyst.setActive(true);
        analyst.setTokenVersion(1L);
        analyst.setMustChangePassword(false);
        AdminUser savedAnalyst = adminUserRepository.saveAndFlush(analyst);
        analystToken = jwtService.generateToken(savedAnalyst.getId(), savedAnalyst.getUsername(), savedAnalyst.getRole(), 1L);

        freePlan = planRepository.findByName("FREE").orElseGet(() -> {
            SubscriptionPlan plan = new SubscriptionPlan("FREE", "Free tier", 60L, 60L, 60);
            return planRepository.saveAndFlush(plan);
        });
    }

    @Test
    @DisplayName("GET /api/v1/plans returns all active plans for SUPER_ADMIN")
    void testListPlansSuperAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/plans")
                .header("Authorization", "Bearer " + superAdminToken)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$[*].name", hasItem("FREE")));
    }

    @Test
    @DisplayName("GET /api/v1/plans allows ANALYST read access")
    void testListPlansAnalyst() throws Exception {
        mockMvc.perform(get("/api/v1/plans")
                .header("Authorization", "Bearer " + analystToken)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("GET /api/v1/plans without token returns 401 Unauthorized")
    void testListPlansMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/plans")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status", is(401)));
    }

    @Test
    @DisplayName("GET /api/v1/plans/{planId} returns single plan details")
    void testGetPlanByIdSuccess() throws Exception {
        mockMvc.perform(get("/api/v1/plans/{planId}", freePlan.getId())
                .header("Authorization", "Bearer " + superAdminToken)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id", is(freePlan.getId().toString())))
            .andExpect(jsonPath("$.name", is("FREE")))
            .andExpect(jsonPath("$.bucketCapacity", is((int) freePlan.getBucketCapacity())));
    }

    @Test
    @DisplayName("GET /api/v1/plans/{planId} for nonexistent ID returns 404 Not Found")
    void testGetPlanByIdNotFound() throws Exception {
        UUID randomId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/plans/{planId}", randomId)
                .header("Authorization", "Bearer " + superAdminToken)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status", is(404)))
            .andExpect(jsonPath("$.error", is("Not Found")));
    }

    @Test
    @DisplayName("GET /api/v1/plans/{planId} with malformed UUID returns 400 Bad Request")
    void testGetPlanByIdMalformedUuid() throws Exception {
        mockMvc.perform(get("/api/v1/plans/not-a-valid-uuid")
                .header("Authorization", "Bearer " + superAdminToken)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status", is(400)))
            .andExpect(jsonPath("$.error", is("Bad Request")));
    }
}
