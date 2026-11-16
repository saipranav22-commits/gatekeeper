package com.gatekeeper.analytics.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ConsumerAnalyticsDto(
    UUID consumerId,
    String consumerName,
    String consumerEmail,
    String currentPlan,
    long totalRequests,
    long allowedRequests,
    long rateLimitedRequests,
    long scopeDeniedRequests,
    Instant firstSeen,
    Instant lastSeen,
    Map<String, Long> requestsByEndpoint,
    Map<String, Long> requestsByKeyPrefix
) {}
