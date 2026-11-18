package com.gatekeeper.chaos.model;

/**
 * Lifecycle state transitions for a controlled chaos engineering experiment:
 * CREATED -> VALIDATING -> RUNNING -> RECOVERING -> COMPLETED (or FAILED / ABORTED).
 */
public enum ExperimentState {
    CREATED,
    VALIDATING,
    RUNNING,
    RECOVERING,
    COMPLETED,
    FAILED,
    ABORTED
}
