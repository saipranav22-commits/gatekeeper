package com.gatekeeper.security.service;

public interface LoginRateLimiter {

    boolean isBlocked(String key);

    void recordFailure(String key);

    void recordSuccess(String key);

    void reset(String key);

    long getRemainingLockoutSeconds(String key);
}
