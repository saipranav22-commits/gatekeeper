package com.gatekeeper.chaos.model;

/**
 * Deterministic outcome classifications for reliability experiment assertions.
 * Missing or insufficient metrics MUST be classified as INCONCLUSIVE, never PASS.
 */
public enum AssertionOutcome {
    PASS,
    FAIL,
    SKIPPED,
    INCONCLUSIVE
}
