package com.gatekeeper.security.service;

import com.gatekeeper.common.exception.AdminAuthenticationException;
import com.gatekeeper.common.exception.LoginRateLimitExceededException;
import com.gatekeeper.security.config.JwtProperties;
import com.gatekeeper.security.dto.AdminProfileDto;
import com.gatekeeper.security.dto.LoginRequest;
import com.gatekeeper.security.dto.LoginResponse;
import com.gatekeeper.security.entity.AdminUser;
import com.gatekeeper.security.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Profile("!foundation")
@Transactional(readOnly = true)
public class AdminAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthenticationService.class);

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;
    private final JwtProperties jwtProperties;

    public AdminAuthenticationService(
            AdminUserRepository adminUserRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginRateLimiter loginRateLimiter,
            JwtProperties jwtProperties) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
        this.jwtProperties = jwtProperties;
    }

    public LoginResponse authenticate(LoginRequest request, String clientIp) {
        Objects.requireNonNull(request, "Login request must not be null");
        String username = request.username() != null ? request.username().trim() : "";
        String rateLimitKey = (clientIp != null ? clientIp : "unknown") + ":" + username;

        // Login abuse prevention check
        if (loginRateLimiter.isBlocked(rateLimitKey)) {
            long retryAfter = loginRateLimiter.getRemainingLockoutSeconds(rateLimitKey);
            log.warn("Blocked login attempt for user '{}' from IP '{}' due to rate limiting (retry-after: {}s)",
                username, clientIp, retryAfter);
            throw new LoginRateLimitExceededException(
                "Too many failed login attempts. Account temporarily locked for security. Please try again later.",
                retryAfter
            );
        }

        Optional<AdminUser> adminOpt = adminUserRepository.findByUsername(username);
        if (adminOpt.isEmpty()) {
            adminOpt = adminUserRepository.findByEmail(username);
        }

        // Constant-time-like generic rejection for non-existent users
        if (adminOpt.isEmpty()) {
            loginRateLimiter.recordFailure(rateLimitKey);
            log.warn("Authentication failed: unknown user or email '{}'", username);
            throw new AdminAuthenticationException("Invalid username or password");
        }

        AdminUser admin = adminOpt.get();

        // Password verification via BCrypt
        if (!passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            loginRateLimiter.recordFailure(rateLimitKey);
            log.warn("Authentication failed: invalid password for user '{}'", username);
            throw new AdminAuthenticationException("Invalid username or password");
        }

        // Inactive administrator check
        if (!admin.isActive()) {
            loginRateLimiter.recordFailure(rateLimitKey);
            log.warn("Authentication failed: inactive account for user '{}'", username);
            throw new AdminAuthenticationException("Invalid username or password");
        }

        // Authentication succeeded: clear rate limiter tracking
        loginRateLimiter.recordSuccess(rateLimitKey);

        String token = jwtService.generateToken(admin.getId(), admin.getUsername(), admin.getRole(), admin.getTokenVersion());
        Instant expiresAt = Instant.now().plusSeconds(jwtProperties.getAccessTokenExpirationSeconds());

        AdminProfileDto profileDto = new AdminProfileDto(
            admin.getId(),
            admin.getUsername(),
            admin.getEmail(),
            admin.getRole(),
            admin.isMustChangePassword()
        );

        log.info("Successful login for administrator '{}' with role '{}'", admin.getUsername(), admin.getRole());
        return new LoginResponse(token, "Bearer", jwtProperties.getAccessTokenExpirationSeconds(), expiresAt, profileDto);
    }

    @Transactional
    public LoginResponse changePassword(UUID adminId, com.gatekeeper.security.dto.ChangePasswordRequest request) {
        Objects.requireNonNull(adminId, "adminId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        AdminUser admin = adminUserRepository.findById(adminId)
            .orElseThrow(() -> new AdminAuthenticationException("Administrator not found"));

        if (!admin.isActive()) {
            throw new AdminAuthenticationException("Administrator account is inactive or revoked");
        }

        // Verify old password
        if (!passwordEncoder.matches(request.oldPassword(), admin.getPasswordHash())) {
            throw new AdminAuthenticationException("Current password is incorrect");
        }

        // Prevent identical password replacement
        if (request.oldPassword().equals(request.newPassword())) {
            throw new IllegalArgumentException("New password cannot be identical to current password");
        }

        // Validate password complexity
        AdminBootstrapService.validatePasswordComplexity(request.newPassword().toCharArray());

        // Update password hash, clear mustChangePassword flag, and increment tokenVersion
        admin.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        admin.setMustChangePassword(false);
        admin.setTokenVersion(admin.getTokenVersion() + 1);
        admin.setPasswordChangedAt(Instant.now());

        adminUserRepository.saveAndFlush(admin);
        log.info("Password changed successfully; token version incremented to {} for admin '{}'",
            admin.getTokenVersion(), admin.getUsername());

        // Issue fresh token with new tokenVersion
        String newToken = jwtService.generateToken(admin.getId(), admin.getUsername(), admin.getRole(), admin.getTokenVersion());
        Instant expiresAt = Instant.now().plusSeconds(jwtProperties.getAccessTokenExpirationSeconds());

        AdminProfileDto profileDto = new AdminProfileDto(
            admin.getId(),
            admin.getUsername(),
            admin.getEmail(),
            admin.getRole(),
            admin.isMustChangePassword()
        );

        return new LoginResponse(newToken, "Bearer", jwtProperties.getAccessTokenExpirationSeconds(), expiresAt, profileDto);
    }
}
