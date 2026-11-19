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
 * Proves that when running with default profile (no explicit local/test/dev profile),
 * all chaos engineering endpoints, controllers, and beans are strictly absent and return 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"foundation"}) // Foundation excludes DB, but does NOT activate local/test/dev
class ChaosDefaultProfileSecurityTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private AdminUserRepository adminUserRepository;

    @Test
    @DisplayName("Default profile: Chaos beans must NOT be loaded when local/test/dev is not explicitly enabled")
    void chaosBeansNotLoadedInDefaultProfile() {
        assertThat(applicationContext.containsBean("chaosAdminController")).isFalse();
        assertThat(applicationContext.containsBean("chaosSafetyGuard")).isFalse();
        assertThat(applicationContext.containsBean("chaosExperimentEngine")).isFalse();

        assertThatThrownBy(() -> applicationContext.getBean(ChaosAdminController.class))
            .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    @DisplayName("Default profile: ChaosSafetyGuard must strictly forbid execution when local/test/dev is not active")
    void chaosSafetyGuardForbidsDefaultProfileExecution() {
        ChaosSafetyGuard guard = new ChaosSafetyGuard(applicationContext.getEnvironment());
        assertThatThrownBy(guard::validateEnvironment)
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Chaos experiments can only run in explicit local, test, or dev profiles");
    }

    @Test
    @DisplayName("Default profile: GET /api/v1/chaos/experiments must return 404 Not Found even for authenticated SUPER_ADMIN")
    void chaosEndpointsReturn404InDefaultProfile() throws Exception {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser("default_super", "super@gatekeeper.local", "$2a$12$hash", "ROLE_SUPER_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);
        admin.setMustChangePassword(false);
        admin.setTokenVersion(1L);

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(admin));
        String token = jwtService.generateToken(adminId, "default_super", "ROLE_SUPER_ADMIN", 1L);

        mockMvc.perform(get("/api/v1/chaos/experiments")
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Default profile: POST /api/v1/chaos/run must return 404 Not Found")
    void chaosRunEndpointReturns404InDefaultProfile() throws Exception {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser("default_super", "super@gatekeeper.local", "$2a$12$hash", "ROLE_SUPER_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);
        admin.setMustChangePassword(false);
        admin.setTokenVersion(1L);

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(admin));
        String token = jwtService.generateToken(adminId, "default_super", "ROLE_SUPER_ADMIN", 1L);

        mockMvc.perform(post("/api/v1/chaos/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"ORDERS_OUTAGE\"}"))
            .andExpect(status().isNotFound());
    }
}
