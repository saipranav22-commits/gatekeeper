package com.gatekeeper.analytics.dto;

import java.time.Instant;
import java.util.UUID;

public record UsageEventDetailDto(
    UUID id,
    Instant timestamp,
    UUID apiKeyId,
    String keyPrefixSnapshot,
    String endpointPath,
    String httpMethod,
    int httpStatus,
    int latencyMs,
    String outcomeCategory,
    String rateLimitPlan,
    String correlationId
) {}
