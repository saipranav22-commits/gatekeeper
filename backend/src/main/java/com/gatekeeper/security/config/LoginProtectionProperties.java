package com.gatekeeper.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gatekeeper.security.login")
public class LoginProtectionProperties {

    private int maxAttempts = 5;
    private long lockoutDurationSeconds = 900; // 15 minutes
    private boolean failClosedOnOutage = true;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getLockoutDurationSeconds() {
        return lockoutDurationSeconds;
    }

    public void setLockoutDurationSeconds(long lockoutDurationSeconds) {
        this.lockoutDurationSeconds = lockoutDurationSeconds;
    }

    public boolean isFailClosedOnOutage() {
        return failClosedOnOutage;
    }

    public void setFailClosedOnOutage(boolean failClosedOnOutage) {
        this.failClosedOnOutage = failClosedOnOutage;
    }
}
