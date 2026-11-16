package com.gatekeeper.common.exception;

import org.springframework.http.HttpStatus;

public class InsufficientScopeException extends GatekeeperException {

    public InsufficientScopeException(String message) {
        super(message, HttpStatus.FORBIDDEN);
    }
}
