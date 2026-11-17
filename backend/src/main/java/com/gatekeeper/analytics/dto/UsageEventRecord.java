package com.gatekeeper.analytics.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable in-flight data carrier for gateway usage telemetry events.
 */
public record UsageEventRecord(
    UUID id,
    Instant timestamp,
    UUID consumerId,
    UUID apiKeyId,
    String consumerEmailSnapshot,
    String keyPrefixSnapshot,
    String endpointPath,
    String httpMethod,
    int httpStatus,
    int latencyMs,
    boolean rateLimited,
    String rejectionReason,
    OutcomeCategory outcomeCategory,
    String rateLimitPlan,
    String correlationId,
    String ipAddress,
    String userAgent,
    String routeId,
    Integer upstreamStatus,
    Integer upstreamLatencyMs,
    String circuitState,
    Integer retryCount,
    Boolean bulkheadRejected,
    String policyDecision,
    String matchedRuleId
) {
    public UsageEventRecord(
            UUID id, Instant timestamp, UUID consumerId, UUID apiKeyId,
            String consumerEmailSnapshot, String keyPrefixSnapshot, String endpointPath,
            String httpMethod, int httpStatus, int latencyMs, boolean rateLimited,
            String rejectionReason, OutcomeCategory outcomeCategory, String rateLimitPlan,
            String correlationId, String ipAddress, String userAgent) {
        this(id, timestamp, consumerId, apiKeyId, consumerEmailSnapshot, keyPrefixSnapshot,
             endpointPath, httpMethod, httpStatus, latencyMs, rateLimited, rejectionReason,
             outcomeCategory, rateLimitPlan, correlationId, ipAddress, userAgent,
             null, null, null, "CLOSED", 0, false, null, null);
    }

    public UsageEventRecord(
            UUID id, Instant timestamp, UUID consumerId, UUID apiKeyId,
            String consumerEmailSnapshot, String keyPrefixSnapshot, String endpointPath,
            String httpMethod, int httpStatus, int latencyMs, boolean rateLimited,
            String rejectionReason, OutcomeCategory outcomeCategory, String rateLimitPlan,
            String correlationId, String ipAddress, String userAgent,
            String routeId, Integer upstreamStatus, Integer upstreamLatencyMs) {
        this(id, timestamp, consumerId, apiKeyId, consumerEmailSnapshot, keyPrefixSnapshot,
             endpointPath, httpMethod, httpStatus, latencyMs, rateLimited, rejectionReason,
             outcomeCategory, rateLimitPlan, correlationId, ipAddress, userAgent,
             routeId, upstreamStatus, upstreamLatencyMs, "CLOSED", 0, false, null, null);
    }

    public UsageEventRecord(
            UUID id, Instant timestamp, UUID consumerId, UUID apiKeyId,
            String consumerEmailSnapshot, String keyPrefixSnapshot, String endpointPath,
            String httpMethod, int httpStatus, int latencyMs, boolean rateLimited,
            String rejectionReason, OutcomeCategory outcomeCategory, String rateLimitPlan,
            String correlationId, String ipAddress, String userAgent,
            String routeId, Integer upstreamStatus, Integer upstreamLatencyMs,
            String circuitState, Integer retryCount, Boolean bulkheadRejected) {
        this(id, timestamp, consumerId, apiKeyId, consumerEmailSnapshot, keyPrefixSnapshot,
             endpointPath, httpMethod, httpStatus, latencyMs, rateLimited, rejectionReason,
             outcomeCategory, rateLimitPlan, correlationId, ipAddress, userAgent,
             routeId, upstreamStatus, upstreamLatencyMs, circuitState, retryCount, bulkheadRejected, null, null);
    }
}
