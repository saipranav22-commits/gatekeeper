package com.gatekeeper.common.exception;

import org.springframework.http.HttpStatus;

public class LoginRateLimitExceededException extends GatekeeperException {

    private final long retryAfterSeconds;

    public LoginRateLimitExceededException(String message) {
        this(message, 900);
    }

    public LoginRateLimitExceededException(String message, long retryAfterSeconds) {
        super(message, HttpStatus.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
