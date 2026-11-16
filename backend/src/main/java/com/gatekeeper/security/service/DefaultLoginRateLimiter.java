package com.gatekeeper.security.service;

import com.gatekeeper.common.exception.LoginProtectionUnavailableException;
import com.gatekeeper.security.config.LoginProtectionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Login abuse and brute-force rate limiter.
 *
 * <p>Supports distributed atomic rate limiting via Redis when available, with
 * fail-closed guarantees on infrastructure failure. Falls back to in-memory
 * tracking when Redis is not configured (e.g. offline foundation testing).
 */
@Service
public class DefaultLoginRateLimiter implements LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DefaultLoginRateLimiter.class);
    private static final String REDIS_KEY_PREFIX = "gatekeeper:auth:abuse:";

    private final LoginProtectionProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<String, AttemptRecord> inMemoryAttempts = new ConcurrentHashMap<>();
    private volatile boolean simulateOutage = false;

    public DefaultLoginRateLimiter(LoginProtectionProperties properties) {
        this(properties, null);
    }

    @Autowired
    public DefaultLoginRateLimiter(
            LoginProtectionProperties properties,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        if (redisTemplate != null) {
            log.info("DefaultLoginRateLimiter initialized with distributed Redis backend");
        } else {
            log.info("DefaultLoginRateLimiter initialized with in-memory backend (single-instance)");
        }
    }

    public void setSimulateOutage(boolean simulateOutage) {
        this.simulateOutage = simulateOutage;
    }

    public boolean isRedisBacked() {
        return redisTemplate != null && !simulateOutage;
    }

    @Override
    public boolean isBlocked(String key) {
        checkHealth();
        if (key == null || key.isBlank()) {
            return false;
        }

        String normalizedKey = normalizeKey(key);

        if (redisTemplate != null) {
            try {
                String redisKey = REDIS_KEY_PREFIX + normalizedKey;
                String val = redisTemplate.opsForValue().get(redisKey);
                if (val != null) {
                    int count = Integer.parseInt(val);
                    return count >= properties.getMaxAttempts();
                }
                return false;
            } catch (DataAccessException e) {
                log.error("Redis failure during login abuse check", e);
                if (properties.isFailClosedOnOutage()) {
                    throw new LoginProtectionUnavailableException("Login protection service dependency failure (fail-closed)", e);
                }
                return false;
            }
        }

        // In-memory fallback
        AttemptRecord record = inMemoryAttempts.get(normalizedKey);
        if (record == null) {
            return false;
        }

        Instant now = Instant.now();
        if (record.lockoutUntil != null) {
            if (now.isBefore(record.lockoutUntil)) {
                return true;
            } else {
                inMemoryAttempts.remove(normalizedKey, record);
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

        String normalizedKey = normalizeKey(key);

        if (redisTemplate != null) {
            try {
                String redisKey = REDIS_KEY_PREFIX + normalizedKey;
                Long newCount = redisTemplate.opsForValue().increment(redisKey);
                if (newCount != null && newCount == 1L) {
                    redisTemplate.expire(redisKey, properties.getLockoutDurationSeconds(), TimeUnit.SECONDS);
                }
                if (newCount != null && newCount >= properties.getMaxAttempts()) {
                    log.warn("Login protection locked key '{}' in Redis after {} failed attempts",
                        normalizedKey, newCount);
                }
                return;
            } catch (DataAccessException e) {
                log.error("Redis failure recording login failure", e);
                if (properties.isFailClosedOnOutage()) {
                    throw new LoginProtectionUnavailableException("Login protection service dependency failure (fail-closed)", e);
                }
                return;
            }
        }

        // In-memory fallback
        Instant now = Instant.now();
        inMemoryAttempts.compute(normalizedKey, (k, existing) -> {
            if (existing == null) {
                return new AttemptRecord(1, null, now);
            }

            long secondsSinceLast = now.getEpochSecond() - existing.lastAttempt.getEpochSecond();
            if (secondsSinceLast > properties.getLockoutDurationSeconds()) {
                return new AttemptRecord(1, null, now);
            }

            int newCount = existing.failureCount + 1;
            Instant lockout = null;
            if (newCount >= properties.getMaxAttempts()) {
                lockout = now.plusSeconds(properties.getLockoutDurationSeconds());
                log.warn("Login protection locked key '{}' in memory after {} failed attempts until {}",
                    k, newCount, lockout);
            }
            return new AttemptRecord(newCount, lockout, now);
        });
    }

    @Override
    public void recordSuccess(String key) {
        checkHealth();
        if (key == null || key.isBlank()) {
            return;
        }

        String normalizedKey = normalizeKey(key);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(REDIS_KEY_PREFIX + normalizedKey);
                return;
            } catch (DataAccessException e) {
                log.error("Redis failure clearing login attempts on success", e);
                if (properties.isFailClosedOnOutage()) {
                    throw new LoginProtectionUnavailableException("Login protection service dependency failure (fail-closed)", e);
                }
                return;
            }
        }

        inMemoryAttempts.remove(normalizedKey);
    }

    @Override
    public void reset(String key) {
        if (key != null) {
            String normalizedKey = normalizeKey(key);
            if (redisTemplate != null) {
                try {
                    redisTemplate.delete(REDIS_KEY_PREFIX + normalizedKey);
                } catch (Exception ignored) {}
            }
            inMemoryAttempts.remove(normalizedKey);
        }
    }

    @Override
    public long getRemainingLockoutSeconds(String key) {
        if (key == null || key.isBlank()) {
            return 0L;
        }

        String normalizedKey = normalizeKey(key);

        if (redisTemplate != null) {
            try {
                Long expire = redisTemplate.getExpire(REDIS_KEY_PREFIX + normalizedKey, TimeUnit.SECONDS);
                return expire != null && expire > 0 ? expire : properties.getLockoutDurationSeconds();
            } catch (Exception e) {
                return properties.getLockoutDurationSeconds();
            }
        }

        AttemptRecord record = inMemoryAttempts.get(normalizedKey);
        if (record != null && record.lockoutUntil != null) {
            long remaining = record.lockoutUntil.getEpochSecond() - Instant.now().getEpochSecond();
            return Math.max(0, remaining);
        }
        return properties.getLockoutDurationSeconds();
    }

    private String normalizeKey(String key) {
        return key.trim().toLowerCase();
    }

    private void checkHealth() {
        if (simulateOutage && properties.isFailClosedOnOutage()) {
            throw new LoginProtectionUnavailableException("Login protection service dependency failure (fail-closed)");
        }
    }

    private record AttemptRecord(int failureCount, Instant lockoutUntil, Instant lastAttempt) {}
}
