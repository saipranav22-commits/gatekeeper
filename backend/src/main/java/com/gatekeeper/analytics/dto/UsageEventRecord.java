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
    String userAgent
) {}
