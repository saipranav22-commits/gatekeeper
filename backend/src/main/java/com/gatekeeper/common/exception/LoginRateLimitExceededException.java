package com.gatekeeper.common.exception;

import org.springframework.http.HttpStatus;

public class LoginRateLimitExceededException extends GatekeeperException {

    public LoginRateLimitExceededException(String message) {
        super(message, HttpStatus.TOO_MANY_REQUESTS);
    }
}
