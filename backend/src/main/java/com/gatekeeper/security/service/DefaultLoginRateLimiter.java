package com.gatekeeper.security.service;

import com.gatekeeper.common.exception.LoginProtectionUnavailableException;
import com.gatekeeper.security.config.LoginProtectionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DefaultLoginRateLimiter implements LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DefaultLoginRateLimiter.class);

    private final LoginProtectionProperties properties;
    private final ConcurrentHashMap<String, AttemptRecord> attempts = new ConcurrentHashMap<>();
    private volatile boolean simulateOutage = false;

    public DefaultLoginRateLimiter(LoginProtectionProperties properties) {
        this.properties = properties;
    }

    // For testing and fail-closed verification
    public void setSimulateOutage(boolean simulateOutage) {
        this.simulateOutage = simulateOutage;
    }

    @Override
    public boolean isBlocked(String key) {
        checkHealth();
        if (key == null || key.isBlank()) {
            return false;
        }

        AttemptRecord record = attempts.get(key);
        if (record == null) {
            return false;
        }

        Instant now = Instant.now();
        if (record.lockoutUntil != null) {
            if (now.isBefore(record.lockoutUntil)) {
                return true;
            } else {
                // Lockout window expired; clear record
                attempts.remove(key, record);
                return false;
            }
        }

        return false;
    }

    @Override
    public void recordFailure(String key) {
        checkHealth();
        if (key == null || key.isBlank()) {
            return;
        }

        Instant now = Instant.now();
        attempts.compute(key, (k, existing) -> {
            if (existing == null) {
                return new AttemptRecord(1, null, now);
            }

            // Check if window has expired since first attempt
            long secondsSinceLast = now.getEpochSecond() - existing.lastAttempt.getEpochSecond();
            if (secondsSinceLast > properties.getLockoutDurationSeconds()) {
                return new AttemptRecord(1, null, now);
            }

            int newCount = existing.failureCount + 1;
            Instant lockout = null;
            if (newCount >= properties.getMaxAttempts()) {
                lockout = now.plusSeconds(properties.getLockoutDurationSeconds());
                log.warn("Login protection locked key '{}' after {} failed attempts until {}",
                    k, newCount, lockout);
            }

            return new AttemptRecord(newCount, lockout, now);
        });
    }

    @Override
    public void recordSuccess(String key) {
        checkHealth();
        if (key != null) {
            attempts.remove(key);
        }
    }

    @Override
    public void reset(String key) {
        if (key != null) {
            attempts.remove(key);
        }
    }

    private void checkHealth() {
        if (simulateOutage && properties.isFailClosedOnOutage()) {
            throw new LoginProtectionUnavailableException("Login protection service dependency failure (fail-closed)");
        }
    }

    private record AttemptRecord(int failureCount, Instant lockoutUntil, Instant lastAttempt) {}
}
