package com.gatekeeper.ratelimit.service;

import com.gatekeeper.ratelimit.dto.RateLimitDecision;
import com.gatekeeper.ratelimit.dto.RateLimitPlan;
import com.gatekeeper.ratelimit.exception.RateLimitUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Distributed Redis-backed Token Bucket rate limiter using an atomic Lua script.
 * Guarantees zero race conditions, sub-millisecond evaluation, server-authoritative
 * time synchronization, plan upgrade/downgrade handling, and fail-closed security.
 */
@Service
@Profile("!foundation")
public class RedisTokenBucketRateLimiter implements TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    public static final String KEY_PREFIX = "gatekeeper:ratelimit:consumer:";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<List> rateLimitScript;

    public RedisTokenBucketRateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate must not be null");

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/token_bucket.lua")));
        script.setResultType(List.class);
        this.rateLimitScript = script;
    }

    @Override
    public RateLimitDecision consume(UUID consumerId, RateLimitPlan plan, int requestCost) {
        if (consumerId == null) {
            throw new IllegalArgumentException("consumerId must not be null");
        }
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
        if (requestCost <= 0) {
            throw new IllegalArgumentException("requestCost must be positive, got: " + requestCost);
        }
        if (requestCost > plan.bucketCapacity()) {
            throw new IllegalArgumentException(String.format(
                "requestCost (%d) cannot exceed bucket capacity (%d)",
                requestCost, plan.bucketCapacity()
            ));
        }

        String key = KEY_PREFIX + consumerId;

        // TTL: at least 24 hours (86400s) or 2x the time required to refill from empty to full capacity
        long secondsToFull = (long) Math.ceil((double) plan.bucketCapacity() * plan.refillPeriodSeconds() / plan.refillTokens());
        long ttlSeconds = Math.max(86400L, secondsToFull * 2);

        try {
            List<?> result = stringRedisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(plan.bucketCapacity()),
                String.valueOf(plan.refillTokens()),
                String.valueOf(plan.refillPeriodSeconds()),
                String.valueOf(requestCost),
                String.valueOf(ttlSeconds)
            );

            if (result == null || result.size() < 5) {
                throw new RateLimitUnavailableException("Malformed response from Redis token bucket script");
            }

            long allowedVal = ((Number) result.get(0)).longValue();
            long tokensRemaining = ((Number) result.get(1)).longValue();
            long bucketCapacity = ((Number) result.get(2)).longValue();
            long retryAfterSec = ((Number) result.get(3)).longValue();
            long resetAfterSec = ((Number) result.get(4)).longValue();

            boolean allowed = (allowedVal == 1L);

            log.debug("Rate limit evaluated for consumer {}: allowed={}, remaining={}, capacity={}, retryAfter={}, resetAfter={}",
                consumerId, allowed, tokensRemaining, bucketCapacity, retryAfterSec, resetAfterSec);

            return RateLimitDecision.of(
                allowed,
                tokensRemaining,
                bucketCapacity,
                retryAfterSec,
                resetAfterSec,
                plan.name()
            );
        } catch (RateLimitUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("Redis token bucket rate limiter failed for consumer {}: {}", consumerId, e.getMessage());
            throw new RateLimitUnavailableException("Rate limiting service unavailable due to Redis error", e);
        }
    }

    @Override
    public void resetBucket(UUID consumerId) {
        if (consumerId == null) {
            throw new IllegalArgumentException("consumerId must not be null");
        }
        try {
            String key = KEY_PREFIX + consumerId;
            stringRedisTemplate.delete(key);
            log.debug("Reset rate limit bucket for consumer {}", consumerId);
        } catch (Exception e) {
            log.error("Failed to reset rate limit bucket in Redis for consumer {}: {}", consumerId, e.getMessage());
            throw new RateLimitUnavailableException("Rate limiting service unavailable during reset", e);
        }
    }
}
