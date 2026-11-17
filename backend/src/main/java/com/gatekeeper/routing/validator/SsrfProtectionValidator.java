package com.gatekeeper.routing.validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates upstream target URIs and request paths to prevent Server-Side Request Forgery (SSRF),
 * open proxying, loopback infrastructure attacks, and directory traversal.
 */
@Component
public class SsrfProtectionValidator {

    private static final Logger log = LoggerFactory.getLogger(SsrfProtectionValidator.class);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private static final Set<String> BLOCKED_HOSTS = Set.of(
        "169.254.169.254",
        "metadata.google.internal",
        "metadata",
        "100.100.100.200"
    );

    private static final Set<Integer> BLOCKED_PORTS = Set.of(
        5432, 5434, // PostgreSQL
        6379, 6380  // Redis
    );

    private final Set<String> allowedHosts;

    @org.springframework.beans.factory.annotation.Autowired
    public SsrfProtectionValidator() {
        this(Set.of("localhost", "127.0.0.1", "::1"));
    }

    public SsrfProtectionValidator(Set<String> customAllowedHosts) {
        if (customAllowedHosts == null || customAllowedHosts.isEmpty()) {
            this.allowedHosts = Set.of("localhost", "127.0.0.1", "::1");
        } else {
            this.allowedHosts = customAllowedHosts.stream()
                .map(h -> h.toLowerCase(Locale.ROOT).trim())
                .collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * Validates an upstream base URI.
     *
     * @param uri the URI to validate
     * @throws SecurityException if the URI violates SSRF rules
     */
    public void validateUpstreamUri(URI uri) {
        if (uri == null) {
            throw new SecurityException("Upstream URI cannot be null");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            log.warn("SSRF attempt blocked: disallowed scheme '{}' in URI '{}'", scheme, uri);
            throw new SecurityException("Disallowed URI scheme: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            log.warn("SSRF attempt blocked: missing host in URI '{}'", uri);
            throw new SecurityException("Missing host in upstream URI");
        }

        String lowerHost = host.toLowerCase(Locale.ROOT).trim();
        if (BLOCKED_HOSTS.contains(lowerHost)) {
            log.warn("SSRF attempt blocked: access to restricted host '{}' is prohibited", host);
            throw new SecurityException("Access to restricted host is prohibited: " + host);
        }

        if (!allowedHosts.contains(lowerHost)) {
            log.warn("SSRF attempt blocked: host '{}' is not in allowed upstream hosts list", host);
            throw new SecurityException("Host is not allowed: " + host);
        }

        int port = uri.getPort();
        if (port != -1 && BLOCKED_PORTS.contains(port)) {
            log.warn("SSRF attempt blocked: target port '{}' is prohibited", port);
            throw new SecurityException("Access to restricted internal port is prohibited: " + port);
        }
    }

    /**
     * Validates that the request path does not contain directory traversal or malicious sequences.
     *
     * @param path the path to validate
     * @throws SecurityException if directory traversal is detected
     */
    public void validatePath(String path) {
        if (path == null) {
            return;
        }

        String normalized = path.toLowerCase(Locale.ROOT);
        if (normalized.contains("..") || normalized.contains("%2e%2e") || normalized.contains("%2e")
            || normalized.contains("\\") || normalized.contains("\0")) {
            log.warn("Path traversal attempt blocked: '{}'", path);
            throw new SecurityException("Invalid path traversal sequence detected in request path");
        }
    }
}
