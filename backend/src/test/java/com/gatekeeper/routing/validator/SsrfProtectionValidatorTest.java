package com.gatekeeper.routing.validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SsrfProtectionValidator Unit Tests")
class SsrfProtectionValidatorTest {

    private SsrfProtectionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SsrfProtectionValidator(Set.of("localhost", "127.0.0.1", "::1", "internal.api.local"));
    }

    @Test
    @DisplayName("Permit valid upstream URIs")
    void shouldPermitValidUpstreamUris() {
        assertThatCode(() -> validator.validateUpstreamUri(URI.create("http://localhost:8081")))
            .doesNotThrowAnyException();

        assertThatCode(() -> validator.validateUpstreamUri(URI.create("http://127.0.0.1:8082")))
            .doesNotThrowAnyException();

        assertThatCode(() -> validator.validateUpstreamUri(URI.create("https://internal.api.local:8443")))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://localhost:8081",
        "file:///etc/passwd",
        "gopher://localhost:8081",
        "ws://localhost:8081"
    })
    @DisplayName("Reject disallowed URI schemes")
    void shouldRejectDisallowedSchemes(String uriStr) {
        assertThatThrownBy(() -> validator.validateUpstreamUri(URI.create(uriStr)))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Disallowed URI scheme");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://169.254.169.254/latest/meta-data/",
        "http://metadata.google.internal/computeMetadata/v1/",
        "http://100.100.100.200/latest/meta-data/"
    })
    @DisplayName("Reject cloud metadata IP addresses and domains")
    void shouldRejectCloudMetadataEndpoints(String uriStr) {
        assertThatThrownBy(() -> validator.validateUpstreamUri(URI.create(uriStr)))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Access to restricted host is prohibited");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://evil.com:8080",
        "http://192.168.1.50:8080",
        "http://10.0.0.1:8080",
        "http://external-attacker.com"
    })
    @DisplayName("Reject non-allowlisted external hosts")
    void shouldRejectNonAllowlistedHosts(String uriStr) {
        assertThatThrownBy(() -> validator.validateUpstreamUri(URI.create(uriStr)))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Host is not allowed");
    }

    @ParameterizedTest
    @ValueSource(ints = {5432, 5434, 6379, 6380})
    @DisplayName("Reject connections targeting protected internal database/cache ports")
    void shouldRejectProtectedInternalPorts(int port) {
        URI uri = URI.create("http://127.0.0.1:" + port);
        assertThatThrownBy(() -> validator.validateUpstreamUri(uri))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Access to restricted internal port is prohibited");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/orders/../admin",
        "/api/v1/orders/%2e%2e/admin",
        "/api/v1/orders/%2E%2E/admin",
        "/api/v1/orders/%2e/config",
        "/api/v1/orders/..\\admin",
        "/api/v1/orders/\0/admin"
    })
    @DisplayName("Reject path traversal sequences")
    void shouldRejectPathTraversalSequences(String path) {
        assertThatThrownBy(() -> validator.validatePath(path))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Invalid path traversal sequence");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/proxy/orders",
        "/api/v1/proxy/orders/ORD-001",
        "/api/v1/proxy/inventory/SKU-1001",
        "/api/v1/proxy/orders?status=COMPLETED&limit=10"
    })
    @DisplayName("Permit clean request paths")
    void shouldPermitCleanPaths(String path) {
        assertThatCode(() -> validator.validatePath(path))
            .doesNotThrowAnyException();
    }
}
