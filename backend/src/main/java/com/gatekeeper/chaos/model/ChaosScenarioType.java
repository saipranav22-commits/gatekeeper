package com.gatekeeper.chaos.model;

/**
 * Supported chaos scenarios for Gatekeeper resilience & reliability validation.
 */
public enum ChaosScenarioType {
    ORDERS_OUTAGE("Scenario A — Orders Service Outage", "Simulates sustained upstream HTTP 503 outage, circuit breaker trip, fast rejection, and automatic recovery"),
    SLOW_UPSTREAM("Scenario B — Slow Upstream & Timeout Cascade", "Injects controlled latency relative to gateway timeouts, slow-call rate accounting, and HTTP 504 handling"),
    BULKHEAD_SATURATION("Scenario C — Bulkhead Saturation", "Generates concurrent in-flight requests to verify semaphore ceiling, BULKHEAD_REJECTED handling, and route isolation"),
    REDIS_FAILURE("Scenario D — Redis Dependency Failure", "Verifies that token-bucket rate limiting and authentication fail closed safely when Redis connectivity is severed"),
    POLICY_SAFETY("Scenario E — Policy Safety Under Failure", "Demonstrates candidate DENY simulation, live enforcement without quota/upstream leak, audit trail, and safe rollback"),
    RETRY_STORM("Scenario F — Retry Storm Prevention", "Simulates transient errors to verify idempotent GET retries, non-idempotent POST safety, jittered backoff, and quota preservation");

    private final String displayName;
    private final String description;

    ChaosScenarioType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
