package com.gatekeeper.resilience.circuitbreaker;

/**
 * Lifecycle states of an upstream circuit breaker.
 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
