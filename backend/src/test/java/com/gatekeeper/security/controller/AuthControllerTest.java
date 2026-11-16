package com.gatekeeper.security.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.common.exception.AdminAuthenticationException;
import com.gatekeeper.common.exception.LoginProtectionUnavailableException;
import com.gatekeeper.common.exception.LoginRateLimitExceededException;
import com.gatekeeper.security.dto.AdminProfileDto;
import com.gatekeeper.security.dto.LoginRequest;
import com.gatekeeper.security.dto.LoginResponse;
import com.gatekeeper.security.service.AdminAuthenticationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.gatekeeper.security.service.JwtService jwtService;

    @MockBean
    private com.gatekeeper.security.repository.AdminUserRepository adminUserRepository;

    @MockBean
    private AdminAuthenticationService authenticationService;

    @Test
    @DisplayName("POST /api/v1/auth/login with valid credentials returns 200 and JWT Bearer token")
    void testSuccessfulLogin() throws Exception {
        UUID adminId = UUID.randomUUID();
        AdminProfileDto profile = new AdminProfileDto(adminId, "admin", "admin@gatekeeper.local", "ROLE_ADMIN", false);
        LoginResponse response = new LoginResponse("mock.jwt.token", "Bearer", 900, Instant.now().plusSeconds(900), profile);

        when(authenticationService.authenticate(any(LoginRequest.class), any())).thenReturn(response);

        LoginRequest request = new LoginRequest("admin", "ValidPassword123!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken", is("mock.jwt.token")))
            .andExpect(jsonPath("$.tokenType", is("Bearer")))
            .andExpect(jsonPath("$.expiresIn", is(900)))
            .andExpect(jsonPath("$.admin.username", is("admin")))
            .andExpect(jsonPath("$.admin.role", is("ROLE_ADMIN")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login with invalid credentials returns 401")
    void testInvalidCredentialsReturns401() throws Exception {
        when(authenticationService.authenticate(any(LoginRequest.class), any()))
            .thenThrow(new AdminAuthenticationException("Invalid username or password"));

        LoginRequest request = new LoginRequest("admin", "WrongPassword123!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status", is(401)))
            .andExpect(jsonPath("$.error", is("Unauthorized")))
            .andExpect(jsonPath("$.message", is("Invalid username or password")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login with malformed payload returns 400")
    void testMalformedRequestReturns400() throws Exception {
        LoginRequest request = new LoginRequest("", "");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status", is(400)))
            .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login when rate limited returns 429 with Retry-After header")
    void testRateLimitedReturns429() throws Exception {
        when(authenticationService.authenticate(any(LoginRequest.class), any()))
            .thenThrow(new LoginRateLimitExceededException("Too many failed login attempts", 900));

        LoginRequest request = new LoginRequest("abusive_user", "Password123!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isTooManyRequests())
            .andExpect(status().is(429))
            .andExpect(jsonPath("$.status", is(429)))
            .andExpect(jsonPath("$.error", is("Too Many Requests")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "900"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login when login protection dependency fails returns 503")
    void testDependencyFailureReturns503() throws Exception {
        when(authenticationService.authenticate(any(LoginRequest.class), any()))
            .thenThrow(new LoginProtectionUnavailableException("Login protection failed closed"));

        LoginRequest request = new LoginRequest("admin", "Password123!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status", is(503)))
            .andExpect(jsonPath("$.error", is("Service Unavailable")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password with valid request returns 200 and fresh JWT")
    void testChangePasswordSuccess() throws Exception {
        UUID adminId = UUID.randomUUID();
        com.gatekeeper.security.entity.AdminUser admin =
            new com.gatekeeper.security.entity.AdminUser("admin", "admin@gatekeeper.local", "$2a$12$hash", "ROLE_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);
        admin.setTokenVersion(1L);
        admin.setMustChangePassword(true);

        when(adminUserRepository.findById(adminId)).thenReturn(java.util.Optional.of(admin));
        String token = jwtService.generateToken(adminId, "admin", "ROLE_ADMIN", 1L);

        AdminProfileDto profile = new AdminProfileDto(adminId, "admin", "admin@gatekeeper.local", "ROLE_ADMIN", false);
        LoginResponse response = new LoginResponse("new.jwt.token", "Bearer", 900, Instant.now().plusSeconds(900), profile);

        when(authenticationService.changePassword(eq(adminId), any(com.gatekeeper.security.dto.ChangePasswordRequest.class)))
            .thenReturn(response);

        com.gatekeeper.security.dto.ChangePasswordRequest request =
            new com.gatekeeper.security.dto.ChangePasswordRequest("OldPassword123!", "NewComplexPassword456!");

        mockMvc.perform(post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken", is("new.jwt.token")))
            .andExpect(jsonPath("$.admin.mustChangePassword", is(false)));
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password with wrong old password returns 401")
    void testChangePasswordIncorrectOldPasswordReturns401() throws Exception {
        UUID adminId = UUID.randomUUID();
        com.gatekeeper.security.entity.AdminUser admin =
            new com.gatekeeper.security.entity.AdminUser("admin", "admin@gatekeeper.local", "$2a$12$hash", "ROLE_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);
        admin.setTokenVersion(1L);
        admin.setMustChangePassword(true);

        when(adminUserRepository.findById(adminId)).thenReturn(java.util.Optional.of(admin));
        String token = jwtService.generateToken(adminId, "admin", "ROLE_ADMIN", 1L);

        when(authenticationService.changePassword(eq(adminId), any(com.gatekeeper.security.dto.ChangePasswordRequest.class)))
            .thenThrow(new AdminAuthenticationException("Current password is incorrect"));

        com.gatekeeper.security.dto.ChangePasswordRequest request =
            new com.gatekeeper.security.dto.ChangePasswordRequest("WrongPassword123!", "NewComplexPassword456!");

        mockMvc.perform(post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status", is(401)))
            .andExpect(jsonPath("$.message", is("Current password is incorrect")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login with malformed JSON body returns 400 Bad Request")
    void testLoginWithMalformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-a-valid-json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status", is(400)))
            .andExpect(jsonPath("$.error", is("Bad Request")))
            .andExpect(jsonPath("$.message", is("Malformed or unreadable JSON request body")));
    }
}
