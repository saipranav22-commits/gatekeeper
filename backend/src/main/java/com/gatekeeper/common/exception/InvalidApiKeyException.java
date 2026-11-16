package com.gatekeeper.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidApiKeyException extends GatekeeperException {

    public InvalidApiKeyException(String message) {
        super(message, HttpStatus.UNAUTHORIZED);
    }
}
