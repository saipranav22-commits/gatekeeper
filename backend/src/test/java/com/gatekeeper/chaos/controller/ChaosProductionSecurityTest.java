package com.gatekeeper.chaos.controller;

import com.gatekeeper.chaos.engine.ChaosSafetyGuard;
import com.gatekeeper.security.entity.AdminUser;
import com.gatekeeper.security.repository.AdminUserRepository;
import com.gatekeeper.security.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that Chaos Engineering REST controllers and execution capabilities
 * are strictly disabled and completely inaccessible in production environments.
 */
@SpringBootTest(properties = {
    "gatekeeper.security.jwt.secret-key=prod-security-test-jwt-secret-key-32bytes-entropy!"
})
@AutoConfigureMockMvc
@ActiveProfiles({"foundation", "prod"})
class ChaosProductionSecurityTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private AdminUserRepository adminUserRepository;

    @Autowired(required = false)
    private ChaosSafetyGuard chaosSafetyGuard;

    @Test
    @DisplayName("Chaos beans must NOT be loaded when prod profile is active")
    void chaosBeansNotLoadedInProduction() {
        assertThat(applicationContext.containsBean("chaosAdminController")).isFalse();
        assertThat(applicationContext.containsBean("chaosSafetyGuard")).isFalse();
        assertThat(applicationContext.containsBean("chaosExperimentEngine")).isFalse();

        assertThatThrownBy(() -> applicationContext.getBean(ChaosAdminController.class))
            .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    @DisplayName("ChaosSafetyGuard must strictly forbid execution when prod profile is active")
    void chaosSafetyGuardForbidsProductionExecution() {
        ChaosSafetyGuard guard = new ChaosSafetyGuard(applicationContext.getEnvironment());
        assertThatThrownBy(guard::validateEnvironment)
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("CRITICAL SAFETY VIOLATION: Chaos experiments are strictly forbidden in production profile!");
    }

    @Test
    @DisplayName("GET /api/v1/chaos/experiments must return 404 Not Found even for authenticated SUPER_ADMIN")
    void chaosEndpointsReturn404EvenForSuperAdmin() throws Exception {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser("prod_super", "super@gatekeeper.local", "$2a$12$hash", "ROLE_SUPER_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);
        admin.setMustChangePassword(false);
        admin.setTokenVersion(1L);

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(admin));
        String token = jwtService.generateToken(adminId, "prod_super", "ROLE_SUPER_ADMIN", 1L);

        mockMvc.perform(get("/api/v1/chaos/experiments")
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/chaos/run must return 404 Not Found in production")
    void chaosRunEndpointReturns404InProduction() throws Exception {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser("prod_super", "super@gatekeeper.local", "$2a$12$hash", "ROLE_SUPER_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);
        admin.setMustChangePassword(false);
        admin.setTokenVersion(1L);

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(admin));
        String token = jwtService.generateToken(adminId, "prod_super", "ROLE_SUPER_ADMIN", 1L);

        mockMvc.perform(post("/api/v1/chaos/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"ORDERS_OUTAGE\"}"))
            .andExpect(status().isNotFound());
    }
}
