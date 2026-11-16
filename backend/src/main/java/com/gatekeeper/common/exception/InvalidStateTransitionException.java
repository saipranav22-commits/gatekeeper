package com.gatekeeper.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidStateTransitionException extends GatekeeperException {

    public InvalidStateTransitionException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
