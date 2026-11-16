package com.gatekeeper.common.exception;

import org.springframework.http.HttpStatus;

public class LoginProtectionUnavailableException extends GatekeeperException {

    public LoginProtectionUnavailableException(String message) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public LoginProtectionUnavailableException(String message, Throwable cause) {
        super(message, cause, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
