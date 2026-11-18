package com.gatekeeper.chaos.model;

/**
 * Controlled fault injection types supported by the Gatekeeper Chaos Lab.
 */
public enum FaultType {
    HTTP_ERROR,
    LATENCY_INJECTION,
    CONCURRENCY_BURST,
    DEPENDENCY_UNAVAILABLE,
    DENY_POLICY_ACTIVATION
}
