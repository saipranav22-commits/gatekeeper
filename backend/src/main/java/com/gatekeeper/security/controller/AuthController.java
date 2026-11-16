package com.gatekeeper.security.controller;

import com.gatekeeper.common.exception.AdminAuthenticationException;
import com.gatekeeper.security.config.LoginProtectionProperties;
import com.gatekeeper.security.dto.AdminPrincipal;
import com.gatekeeper.security.dto.ChangePasswordRequest;
import com.gatekeeper.security.dto.LoginRequest;
import com.gatekeeper.security.dto.LoginResponse;
import com.gatekeeper.security.service.AdminAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("!foundation")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AdminAuthenticationService authenticationService;
    private final LoginProtectionProperties loginProtectionProperties;

    public AuthController(
            AdminAuthenticationService authenticationService,
            LoginProtectionProperties loginProtectionProperties) {
        this.authenticationService = authenticationService;
        this.loginProtectionProperties = loginProtectionProperties;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {

        String clientIp = extractClientIp(servletRequest);
        log.info("Login attempt received for username: '{}' from IP: '{}'", request.username(), clientIp);

        LoginResponse response = authenticationService.authenticate(request, clientIp);

        // Security requirement: Never log tokens or secrets
        log.info("Login successful for username: '{}'", request.username());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/change-password")
    public ResponseEntity<LoginResponse> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest) {

        UUID adminId = extractAdminId(authentication, servletRequest);
        log.info("Password change request received for administrator ID: '{}'", adminId);

        LoginResponse response = authenticationService.changePassword(adminId, request);
        log.info("Password change completed successfully for administrator ID: '{}'", adminId);
        return ResponseEntity.ok(response);
    }

    private UUID extractAdminId(Authentication authentication, HttpServletRequest request) {
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        Object attr = request.getAttribute("adminId");
        if (attr instanceof UUID uuid) {
            return uuid;
        }
        throw new AdminAuthenticationException("Full authentication required to change password");
    }

    private String extractClientIp(HttpServletRequest request) {
        if (loginProtectionProperties.isTrustProxyHeaders()) {
            String xfHeader = request.getHeader("X-Forwarded-For");
            if (xfHeader != null && !xfHeader.isBlank()) {
                return xfHeader.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
