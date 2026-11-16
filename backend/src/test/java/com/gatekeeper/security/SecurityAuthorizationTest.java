package com.gatekeeper.security;

import com.gatekeeper.security.entity.AdminUser;
import com.gatekeeper.security.repository.AdminUserRepository;
import com.gatekeeper.security.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SecurityAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private AdminUserRepository adminUserRepository;

    @TestConfiguration
    static class TestSecurityEndpointsConfiguration {
        @RestController
        @RequestMapping("/api/v1/test")
        static class TestSecurityController {

            @GetMapping("/super-admin")
            @PreAuthorize("hasRole('SUPER_ADMIN')")
            public ResponseEntity<String> superAdminOnly() {
                return ResponseEntity.ok("super-admin-data");
            }

            @GetMapping("/admin")
            @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
            public ResponseEntity<String> adminOrSuper() {
                return ResponseEntity.ok("admin-data");
            }

            @GetMapping("/analyst")
            @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
            public ResponseEntity<String> analystAccess() {
                return ResponseEntity.ok("analyst-data");
            }
        }
    }

    @Test
    @DisplayName("Public health endpoint is accessible without authentication")
    void testPublicHealthEndpointAccessible() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    @DisplayName("Protected endpoint without token returns 401 Unauthorized")
    void testMissingTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/test/admin"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status", is(401)))
            .andExpect(jsonPath("$.error", is("Unauthorized")));
    }

    @Test
    @DisplayName("Protected endpoint with malformed token returns 401 Unauthorized")
    void testMalformedTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/test/admin")
                .header("Authorization", "Bearer invalid.jwt.token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status", is(401)))
            .andExpect(jsonPath("$.error", is("Unauthorized")));
    }

    @Test
    @DisplayName("Protected endpoint with valid token and role grants access (200)")
    void testValidRoleGrantsAccess() throws Exception {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser("lead_admin", "lead@gatekeeper.local", "$2a$12$hash", "ROLE_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(admin));

        String token = jwtService.generateToken(adminId, "lead_admin", "ROLE_ADMIN");

        mockMvc.perform(get("/api/v1/test/admin")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Protected endpoint with insufficient role returns 403 Forbidden")
    void testInsufficientRoleReturns403() throws Exception {
        UUID adminId = UUID.randomUUID();
        AdminUser analyst = new AdminUser("analyst1", "analyst@gatekeeper.local", "$2a$12$hash", "ROLE_ANALYST");
        analyst.setId(adminId);
        analyst.setActive(true);

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(analyst));

        String token = jwtService.generateToken(adminId, "analyst1", "ROLE_ANALYST");

        // Analyst attempting to access super-admin endpoint
        mockMvc.perform(get("/api/v1/test/super-admin")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status", is(403)))
            .andExpect(jsonPath("$.error", is("Forbidden")))
            .andExpect(jsonPath("$.message", is("Access is denied: insufficient role privileges")));
    }

    @Test
    @DisplayName("Deactivated administrator holding valid JWT is rejected with 401 Unauthorized")
    void testDeactivatedAdminRejectedEvenWithValidToken() throws Exception {
        UUID adminId = UUID.randomUUID();
        AdminUser inactiveAdmin = new AdminUser("former_admin", "former@gatekeeper.local", "$2a$12$hash", "ROLE_ADMIN");
        inactiveAdmin.setId(adminId);
        inactiveAdmin.setActive(false); // Inactive

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(inactiveAdmin));

        String token = jwtService.generateToken(adminId, "former_admin", "ROLE_ADMIN");

        mockMvc.perform(get("/api/v1/test/admin")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status", is(401)))
            .andExpect(jsonPath("$.error", is("Unauthorized")));
    }
}
