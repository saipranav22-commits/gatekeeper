package com.gatekeeper.policy.model;

/**
 * Deterministic action produced by a Gatekeeper policy rule.
 *
 * <p>Security Invariant:
 * {@link #ALLOW_CONTINUE} is strictly permission to proceed through mandatory platform security
 * (API key authentication, active consumer checks, mandatory scope checks, atomic Redis rate limiting).
 * It CANNOT bypass mandatory security controls.
 */
public enum PolicyAction {
    /**
     * Request passes this policy filter and continues through mandatory platform security checks.
     */
    ALLOW_CONTINUE,

    /**
     * Request is explicitly blocked by this policy rule. Returns HTTP 403 Forbidden with a safe reason code.
     * Quota is NOT consumed and upstream service is NOT contacted.
     */
    DENY
}
