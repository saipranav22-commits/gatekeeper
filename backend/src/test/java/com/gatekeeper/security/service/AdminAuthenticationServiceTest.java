package com.gatekeeper.security.service;

import com.gatekeeper.common.exception.AdminAuthenticationException;
import com.gatekeeper.common.exception.LoginRateLimitExceededException;
import com.gatekeeper.security.config.JwtProperties;
import com.gatekeeper.security.dto.LoginRequest;
import com.gatekeeper.security.dto.LoginResponse;
import com.gatekeeper.security.entity.AdminUser;
import com.gatekeeper.security.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuthenticationServiceTest {

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private LoginRateLimiter loginRateLimiter;

    private JwtProperties jwtProperties;
    private AdminAuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setAccessTokenExpirationSeconds(900);
        authenticationService = new AdminAuthenticationService(
            adminUserRepository,
            passwordEncoder,
            jwtService,
            loginRateLimiter,
            jwtProperties
        );
    }

    @Test
    @DisplayName("Successful administrator authentication returns valid LoginResponse")
    void testSuccessfulAuthentication() {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser("superadmin", "admin@gatekeeper.local", "$2a$12$hash", "ROLE_SUPER_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);

        when(loginRateLimiter.isBlocked("192.168.1.100:superadmin")).thenReturn(false);
        when(adminUserRepository.findByUsername("superadmin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("ComplexPass123!", "$2a$12$hash")).thenReturn(true);
        when(jwtService.generateToken(adminId, "superadmin", "ROLE_SUPER_ADMIN", 1L)).thenReturn("mock.jwt.token");

        LoginRequest request = new LoginRequest("superadmin", "ComplexPass123!");
        LoginResponse response = authenticationService.authenticate(request, "192.168.1.100");

        assertThat(response.accessToken()).isEqualTo("mock.jwt.token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(response.admin().username()).isEqualTo("superadmin");
        assertThat(response.admin().role()).isEqualTo("ROLE_SUPER_ADMIN");

        verify(loginRateLimiter).recordSuccess("192.168.1.100:superadmin");
    }

    @Test
    @DisplayName("Successful administrator authentication via email returns valid LoginResponse")
    void testSuccessfulAuthenticationViaEmail() {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser("admin", "admin123@gmail.com", "$2a$12$hash", "ROLE_SUPER_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);

        when(loginRateLimiter.isBlocked("192.168.1.100:admin123@gmail.com")).thenReturn(false);
        when(adminUserRepository.findByUsername("admin123@gmail.com")).thenReturn(Optional.empty());
        when(adminUserRepository.findByEmail("admin123@gmail.com")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("ComplexPass123!", "$2a$12$hash")).thenReturn(true);
        when(jwtService.generateToken(adminId, "admin", "ROLE_SUPER_ADMIN", 1L)).thenReturn("mock.jwt.token");

        LoginRequest request = new LoginRequest("admin123@gmail.com", "ComplexPass123!");
        LoginResponse response = authenticationService.authenticate(request, "192.168.1.100");

        assertThat(response.accessToken()).isEqualTo("mock.jwt.token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.admin().username()).isEqualTo("admin");
        assertThat(response.admin().email()).isEqualTo("admin123@gmail.com");

        verify(loginRateLimiter).recordSuccess("192.168.1.100:admin123@gmail.com");
    }

    @Test
    @DisplayName("Reject unknown username with generic error and record rate limit failure")
    void testUnknownUsername() {
        when(loginRateLimiter.isBlocked("192.168.1.100:unknown")).thenReturn(false);
        when(adminUserRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest("unknown", "Password123!");

        assertThatThrownBy(() -> authenticationService.authenticate(request, "192.168.1.100"))
            .isInstanceOf(AdminAuthenticationException.class)
            .hasMessage("Invalid username or password");

        verify(loginRateLimiter).recordFailure("192.168.1.100:unknown");
        verify(jwtService, never()).generateToken(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Reject incorrect password with generic error and record rate limit failure")
    void testIncorrectPassword() {
        AdminUser admin = new AdminUser("admin", "admin@gatekeeper.local", "$2a$12$hash", "ROLE_ADMIN");
        admin.setActive(true);

        when(loginRateLimiter.isBlocked("192.168.1.100:admin")).thenReturn(false);
        when(adminUserRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("WrongPassword123!", "$2a$12$hash")).thenReturn(false);

        LoginRequest request = new LoginRequest("admin", "WrongPassword123!");

        assertThatThrownBy(() -> authenticationService.authenticate(request, "192.168.1.100"))
            .isInstanceOf(AdminAuthenticationException.class)
            .hasMessage("Invalid username or password");

        verify(loginRateLimiter).recordFailure("192.168.1.100:admin");
        verify(jwtService, never()).generateToken(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Reject inactive administrator account with generic error")
    void testInactiveAdministrator() {
        AdminUser admin = new AdminUser("deactivated", "deact@gatekeeper.local", "$2a$12$hash", "ROLE_ADMIN");
        admin.setActive(false);

        when(loginRateLimiter.isBlocked("192.168.1.100:deactivated")).thenReturn(false);
        when(adminUserRepository.findByUsername("deactivated")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("Password123!", "$2a$12$hash")).thenReturn(true);

        LoginRequest request = new LoginRequest("deactivated", "Password123!");

        assertThatThrownBy(() -> authenticationService.authenticate(request, "192.168.1.100"))
            .isInstanceOf(AdminAuthenticationException.class)
            .hasMessage("Invalid username or password");

        verify(loginRateLimiter).recordFailure("192.168.1.100:deactivated");
        verify(jwtService, never()).generateToken(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Reject login when rate limiter blocks abusive IP/user")
    void testRateLimitBlocked() {
        when(loginRateLimiter.isBlocked("192.168.1.100:attacker")).thenReturn(true);

        LoginRequest request = new LoginRequest("attacker", "GuessPassword123!");

        assertThatThrownBy(() -> authenticationService.authenticate(request, "192.168.1.100"))
            .isInstanceOf(LoginRateLimitExceededException.class)
            .hasMessageContaining("temporarily locked");

        verify(adminUserRepository, never()).findByUsername(anyString());
    }

    @Test
    @DisplayName("Successfully change password: verifies old password, clears mustChangePassword, increments tokenVersion")
    void testChangePasswordSuccess() {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser("admin", "admin@gatekeeper.local", "$2a$12$oldhash", "ROLE_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);
        admin.setMustChangePassword(true);
        admin.setTokenVersion(1L);

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("OldPassword123!", "$2a$12$oldhash")).thenReturn(true);
        when(passwordEncoder.encode("NewComplexPassword456!")).thenReturn("$2a$12$newhash");
        when(jwtService.generateToken(adminId, "admin", "ROLE_ADMIN", 2L)).thenReturn("new.version.jwt");

        com.gatekeeper.security.dto.ChangePasswordRequest request =
            new com.gatekeeper.security.dto.ChangePasswordRequest("OldPassword123!", "NewComplexPassword456!");

        LoginResponse response = authenticationService.changePassword(adminId, request);

        assertThat(response.accessToken()).isEqualTo("new.version.jwt");
        assertThat(admin.isMustChangePassword()).isFalse();
        assertThat(admin.getTokenVersion()).isEqualTo(2L);
        assertThat(admin.getPasswordHash()).isEqualTo("$2a$12$newhash");
        assertThat(admin.getPasswordChangedAt()).isNotNull();

        verify(adminUserRepository).saveAndFlush(admin);
    }

    @Test
    @DisplayName("Reject password change when current password is incorrect")
    void testChangePasswordIncorrectOldPassword() {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser("admin", "admin@gatekeeper.local", "$2a$12$oldhash", "ROLE_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("WrongOldPassword123!", "$2a$12$oldhash")).thenReturn(false);

        com.gatekeeper.security.dto.ChangePasswordRequest request =
            new com.gatekeeper.security.dto.ChangePasswordRequest("WrongOldPassword123!", "NewComplexPassword456!");

        assertThatThrownBy(() -> authenticationService.changePassword(adminId, request))
            .isInstanceOf(AdminAuthenticationException.class)
            .hasMessage("Current password is incorrect");
    }

    @Test
    @DisplayName("Reject password change when new password is identical to current password")
    void testChangePasswordIdenticalPassword() {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser("admin", "admin@gatekeeper.local", "$2a$12$oldhash", "ROLE_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("SamePassword123!", "$2a$12$oldhash")).thenReturn(true);

        com.gatekeeper.security.dto.ChangePasswordRequest request =
            new com.gatekeeper.security.dto.ChangePasswordRequest("SamePassword123!", "SamePassword123!");

        assertThatThrownBy(() -> authenticationService.changePassword(adminId, request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("identical");
    }

    @Test
    @DisplayName("Reject password change when new password fails complexity requirements")
    void testChangePasswordWeakNewPassword() {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser("admin", "admin@gatekeeper.local", "$2a$12$oldhash", "ROLE_ADMIN");
        admin.setId(adminId);
        admin.setActive(true);

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("OldPassword123!", "$2a$12$oldhash")).thenReturn(true);

        com.gatekeeper.security.dto.ChangePasswordRequest request =
            new com.gatekeeper.security.dto.ChangePasswordRequest("OldPassword123!", "weak");

        assertThatThrownBy(() -> authenticationService.changePassword(adminId, request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 12 characters");
    }
}
