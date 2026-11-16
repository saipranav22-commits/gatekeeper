package com.gatekeeper.security.service;

import com.gatekeeper.common.exception.LoginProtectionUnavailableException;
import com.gatekeeper.security.config.LoginProtectionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLoginRateLimiterTest {

    private LoginProtectionProperties properties;
    private DefaultLoginRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        properties = new LoginProtectionProperties();
        properties.setMaxAttempts(3);
        properties.setLockoutDurationSeconds(60);
        properties.setFailClosedOnOutage(true);
        rateLimiter = new DefaultLoginRateLimiter(properties);
    }

    @Test
    @DisplayName("Track failed attempts and block when threshold reached")
    void testLockoutAfterThreshold() {
        String key = "127.0.0.1:admin";

        assertThat(rateLimiter.isBlocked(key)).isFalse();

        rateLimiter.recordFailure(key);
        assertThat(rateLimiter.isBlocked(key)).isFalse();

        rateLimiter.recordFailure(key);
        assertThat(rateLimiter.isBlocked(key)).isFalse();

        // Third failure triggers threshold (maxAttempts = 3)
        rateLimiter.recordFailure(key);
        assertThat(rateLimiter.isBlocked(key)).isTrue();
    }

    @Test
    @DisplayName("Reset failed attempts on successful login")
    void testResetOnSuccess() {
        String key = "127.0.0.1:admin";

        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);
        assertThat(rateLimiter.isBlocked(key)).isFalse();

        rateLimiter.recordSuccess(key);
        assertThat(rateLimiter.isBlocked(key)).isFalse();

        // Should require 3 new failures to lock out
        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);
        assertThat(rateLimiter.isBlocked(key)).isFalse();
    }

    @Test
    @DisplayName("Fail-closed on infrastructure outage")
    void testFailClosedOnOutage() {
        rateLimiter.setSimulateOutage(true);

        assertThatThrownBy(() -> rateLimiter.isBlocked("127.0.0.1:admin"))
            .isInstanceOf(LoginProtectionUnavailableException.class)
            .hasMessageContaining("fail-closed");

        assertThatThrownBy(() -> rateLimiter.recordFailure("127.0.0.1:admin"))
            .isInstanceOf(LoginProtectionUnavailableException.class);
    }
}
