package com.gatekeeper.security.service;

import com.gatekeeper.common.exception.DuplicateResourceException;
import com.gatekeeper.security.dto.AdminProfileDto;
import com.gatekeeper.security.entity.AdminUser;
import com.gatekeeper.security.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Profile("!foundation")
public class AdminBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,50}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    private static final Set<String> ALLOWED_ROLES = Set.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN", "ROLE_ANALYST");

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapService(AdminUserRepository adminUserRepository, PasswordEncoder passwordEncoder) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AdminProfileDto bootstrapAdmin(String username, String email, char[] password, String role) {
        Objects.requireNonNull(username, "Username must not be null");
        Objects.requireNonNull(email, "Email must not be null");
        Objects.requireNonNull(password, "Password must not be null");

        // Guard: One-time bootstrap only
        if (adminUserRepository.count() > 0) {
            log.warn("Bootstrap rejected: one or more administrators already exist in the database");
            throw new IllegalStateException("Initial administrator has already been bootstrapped. Setup is disabled.");
        }

        String trimmedUsername = username.trim();
        String trimmedEmail = email.trim().toLowerCase();
        String chosenRole = role != null && !role.isBlank() ? role.trim() : "ROLE_SUPER_ADMIN";

        if (!USERNAME_PATTERN.matcher(trimmedUsername).matches()) {
            throw new IllegalArgumentException("Username must be 3-50 alphanumeric characters (allowing hyphens and underscores)");
        }

        if (!EMAIL_PATTERN.matcher(trimmedEmail).matches()) {
            throw new IllegalArgumentException("Invalid email format");
        }

        if (!ALLOWED_ROLES.contains(chosenRole)) {
            throw new IllegalArgumentException("Invalid role. Allowed roles: " + ALLOWED_ROLES);
        }

        validatePasswordComplexity(password);

        if (adminUserRepository.existsByUsername(trimmedUsername)) {
            throw new DuplicateResourceException("Username '" + trimmedUsername + "' is already registered");
        }

        if (adminUserRepository.existsByEmail(trimmedEmail)) {
            throw new DuplicateResourceException("Email '" + trimmedEmail + "' is already registered");
        }

        String passwordHash;
        try {
            passwordHash = passwordEncoder.encode(CharBuffer.wrap(password));
        } finally {
            // Memory safety: zero out the plaintext password array immediately
            Arrays.fill(password, '\0');
        }

        AdminUser admin = new AdminUser(trimmedUsername, trimmedEmail, passwordHash, chosenRole);
        admin.setActive(true);
        admin.setMustChangePassword(false);

        AdminUser saved;
        try {
            saved = adminUserRepository.saveAndFlush(admin);
        } catch (DataIntegrityViolationException e) {
            log.error("Concurrent bootstrap conflict on unique constraint", e);
            throw new IllegalStateException("Initial administrator bootstrap conflict. Administrator already exists.", e);
        }

        log.info("Initial administrator successfully bootstrapped: username='{}', role='{}'",
            saved.getUsername(), saved.getRole());

        return new AdminProfileDto(
            saved.getId(),
            saved.getUsername(),
            saved.getEmail(),
            saved.getRole(),
            saved.isMustChangePassword()
        );
    }

    public static void validatePasswordComplexity(char[] password) {
        if (password == null || password.length < 12) {
            throw new IllegalArgumentException("Password must contain at least 12 characters");
        }

        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (char c : password) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSpecial = true;
        }

        if (!hasUpper || !hasLower || !hasDigit || !hasSpecial) {
            throw new IllegalArgumentException(
                "Password must contain at least one uppercase letter, one lowercase letter, one digit, and one special character"
            );
        }
    }
}
