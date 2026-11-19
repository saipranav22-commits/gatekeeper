package com.gatekeeper.chaos.controller;

import com.gatekeeper.chaos.engine.ChaosExperimentEngine;
import com.gatekeeper.chaos.engine.ChaosSafetyGuard;
import com.gatekeeper.security.entity.AdminUser;
import com.gatekeeper.security.repository.AdminUserRepository;
import com.gatekeeper.security.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validates security behavior when chaos engineering is explicitly enabled
 * in non-production environments (test/local/dev profile).
 *
 * Verifies:
 * - Beans are loaded
 * - Unauthenticated requests are rejected with HTTP 401
 * - Consumer API keys are rejected with HTTP 401 (management chain requires JWT)
 * - Administrative JWT is required and authorized (HTTP 200)
 * - Non-admin roles (e.g. insufficient privileges) are rejected with HTTP 403
 * - URL normalization / traversal cannot bypass authentication
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"foundation", "test"})
class ChaosExplicitProfileAccessTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private AdminUserRepository adminUserRepository;

    @Autowired
    private ChaosSafetyGuard chaosSafetyGuard;

    @Autowired
    private ChaosExperimentEngine chaosExperimentEngine;

    @Test
    @DisplayName("Explicit test profile: Chaos beans ARE loaded")
    void chaosBeansAreLoadedInTestProfile() {
        assertThat(applicationContext.containsBean("chaosAdminController")).isTrue();
        assertThat(applicationContext.containsBean("chaosSafetyGuard")).isTrue();
        assertThat(applicationContext.containsBean("chaosExperimentEngine")).isTrue();

        assertThatCode(() -> chaosSafetyGuard.validateEnvironment())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Explicit test profile: Unauthenticated request returns 401 Unauthorized")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/chaos/experiments")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Explicit test profile: Consumer API key (X-API-Key) returns 401 on management chaos endpoints")
    void consumerApiKeyCannotAccessChaosEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/chaos/experiments")
                .header("X-API-Key", "gk_live_abcdef1234567890abcdef1234567890")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Explicit test profile: Valid Admin JWT grants access (200 OK)")
    void adminJwtGrantsAccessToChaosEndpoints() throws Exception {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser("test_admin", "admin@gatekeeper.local", "$2a$12$hash", "ROLE_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);
        admin.setMustChangePassword(false);
        admin.setTokenVersion(1L);

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(admin));
        String token = jwtService.generateToken(adminId, "test_admin", "ROLE_ADMIN", 1L);

        mockMvc.perform(get("/api/v1/chaos/experiments")
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Explicit test profile: Insufficient role (e.g. ROLE_ANALYST) returns 403 Forbidden on execution endpoint")
    void analystRoleCannotExecuteChaosRuns() throws Exception {
        UUID analystId = UUID.randomUUID();
        AdminUser analyst = new AdminUser("analyst1", "analyst@gatekeeper.local", "$2a$12$hash", "ROLE_ANALYST");
        analyst.setId(analystId);
        analyst.setActive(true);
        analyst.setMustChangePassword(false);
        analyst.setTokenVersion(1L);

        when(adminUserRepository.findById(analystId)).thenReturn(Optional.of(analyst));
        String token = jwtService.generateToken(analystId, "analyst1", "ROLE_ANALYST", 1L);

        mockMvc.perform(post("/api/v1/chaos/experiments/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"ORDERS_OUTAGE\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Explicit test profile: Path traversal / normalization attempts are blocked by firewall (400 Bad Request)")
    void pathNormalizationAttemptsEnforceAuthentication() throws Exception {
        // Attempt path traversal: Spring Security StrictHttpFirewall rejects this immediately with 400 Bad Request
        mockMvc.perform(get("/api/v1/chaos/../chaos/experiments")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
    }
}
