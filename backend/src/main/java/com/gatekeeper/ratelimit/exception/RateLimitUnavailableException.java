package com.gatekeeper.ratelimit.exception;

import com.gatekeeper.common.exception.GatekeeperException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the rate limiting infrastructure (e.g. Redis) is unavailable,
 * enforcing a fail-closed posture.
 */
public class RateLimitUnavailableException extends GatekeeperException {

    public RateLimitUnavailableException(String message) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public RateLimitUnavailableException(String message, Throwable cause) {
        super(message, cause, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
