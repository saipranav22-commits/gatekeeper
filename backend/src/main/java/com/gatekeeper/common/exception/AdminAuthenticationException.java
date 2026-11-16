package com.gatekeeper.common.exception;

import org.springframework.http.HttpStatus;

public class AdminAuthenticationException extends GatekeeperException {

    public AdminAuthenticationException(String message) {
        super(message, HttpStatus.UNAUTHORIZED);
    }
}
