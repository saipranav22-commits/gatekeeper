package com.gatekeeper.common.exception;

import org.springframework.http.HttpStatus;

public class GatekeeperException extends RuntimeException {

    private final HttpStatus status;

    public GatekeeperException(String message) {
        super(message);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
    }

    public GatekeeperException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public GatekeeperException(String message, Throwable cause, HttpStatus status) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
