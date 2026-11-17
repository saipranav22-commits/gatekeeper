package com.gatekeeper.inventory.fault;

public class SimulatedFaultException extends RuntimeException {

    private final int statusCode;

    public SimulatedFaultException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
