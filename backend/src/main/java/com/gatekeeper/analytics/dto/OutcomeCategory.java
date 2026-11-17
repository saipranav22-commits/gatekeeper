package com.gatekeeper.analytics.dto;

/**
 * Standardized outcome categories for gateway request telemetry.
 */
public enum OutcomeCategory {
    /**
     * Authenticated and authorized request successfully dispatched to upstream handler (HTTP 2xx).
     */
    ALLOWED,

    /**
     * Request rejected due to Redis Token Bucket quota exhaustion (HTTP 429).
     */
    RATE_LIMITED,

    /**
     * Request presented a valid API key but lacked required endpoint scope (HTTP 403).
     */
    SCOPE_DENIED,

    /**
     * Request blocked by Gatekeeper fine-grained policy engine rule (HTTP 403).
     */
    POLICY_DENIED,

    /**
     * Rate limiting infrastructure or downstream service failure (HTTP 503).
     */
    SERVICE_UNAVAILABLE,

    /**
     * Request failed consumer API key authentication (HTTP 401).
     */
    UNAUTHORIZED
}
