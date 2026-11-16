package com.gatekeeper.security.service;

import com.gatekeeper.security.dto.AdminProfileDto;
import com.gatekeeper.security.entity.AdminUser;
import com.gatekeeper.security.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapServiceTest {

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AdminBootstrapService bootstrapService;

    @BeforeEach
    void setUp() {
        bootstrapService = new AdminBootstrapService(adminUserRepository, passwordEncoder);
    }

    @Test
    @DisplayName("Successfully bootstrap initial administrator and wipe password array")
    void testSuccessfulBootstrap() {
        when(adminUserRepository.count()).thenReturn(0L);
        when(adminUserRepository.existsByUsername("superadmin")).thenReturn(false);
        when(adminUserRepository.existsByEmail("admin@gatekeeper.local")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("$2a$12$securehash");

        when(adminUserRepository.saveAndFlush(any(AdminUser.class))).thenAnswer(invocation -> {
            AdminUser user = invocation.getArgument(0);
            return user;
        });

        char[] password = "ComplexPassword123!".toCharArray();
        AdminProfileDto result = bootstrapService.bootstrapAdmin(
            "superadmin",
            "admin@gatekeeper.local",
            password,
            "ROLE_SUPER_ADMIN"
        );

        assertThat(result.username()).isEqualTo("superadmin");
        assertThat(result.email()).isEqualTo("admin@gatekeeper.local");
        assertThat(result.role()).isEqualTo("ROLE_SUPER_ADMIN");

        // Verify password memory wiping: all characters should be '\0'
        for (char c : password) {
            assertThat(c).isEqualTo('\0');
        }

        verify(adminUserRepository).saveAndFlush(argThat(user ->
            user.getUsername().equals("superadmin") &&
            user.getPasswordHash().equals("$2a$12$securehash") &&
            user.getRole().equals("ROLE_SUPER_ADMIN") &&
            user.isActive()
        ));
    }

    @Test
    @DisplayName("Reject bootstrap if any administrator already exists")
    void testRejectWhenAdminAlreadyExists() {
        when(adminUserRepository.count()).thenReturn(1L);

        char[] password = "ComplexPassword123!".toCharArray();
        assertThatThrownBy(() -> bootstrapService.bootstrapAdmin(
            "superadmin",
            "admin@gatekeeper.local",
            password,
            "ROLE_SUPER_ADMIN"
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already been bootstrapped");
    }

    @Test
    @DisplayName("Reject weak passwords failing complexity requirements")
    void testPasswordComplexityRejection() {
        when(adminUserRepository.count()).thenReturn(0L);

        // Too short (< 12 chars)
        assertThatThrownBy(() -> bootstrapService.bootstrapAdmin(
            "admin", "admin@local.com", "Short1!".toCharArray(), "ROLE_ADMIN"
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 12 characters");

        // Missing uppercase
        assertThatThrownBy(() -> bootstrapService.bootstrapAdmin(
            "admin", "admin@local.com", "lowercase1234!".toCharArray(), "ROLE_ADMIN"
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("uppercase");

        // Missing lowercase
        assertThatThrownBy(() -> bootstrapService.bootstrapAdmin(
            "admin", "admin@local.com", "UPPERCASE1234!".toCharArray(), "ROLE_ADMIN"
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lowercase");

        // Missing digit
        assertThatThrownBy(() -> bootstrapService.bootstrapAdmin(
            "admin", "admin@local.com", "UppercaseOnly!!".toCharArray(), "ROLE_ADMIN"
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digit");

        // Missing special character
        assertThatThrownBy(() -> bootstrapService.bootstrapAdmin(
            "admin", "admin@local.com", "UppercaseDigit1234".toCharArray(), "ROLE_ADMIN"
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("special character");
    }

    @Test
    @DisplayName("Handle concurrent bootstrap race conditions via database integrity check")
    void testConcurrentBootstrapConflict() {
        when(adminUserRepository.count()).thenReturn(0L);
        when(adminUserRepository.existsByUsername("admin")).thenReturn(false);
        when(adminUserRepository.existsByEmail("admin@local.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("$2a$12$hash");
        when(adminUserRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique constraint"));

        char[] password = "ValidPassword123!".toCharArray();
        assertThatThrownBy(() -> bootstrapService.bootstrapAdmin(
            "admin", "admin@local.com", password, "ROLE_SUPER_ADMIN"
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bootstrap conflict");
    }
}
