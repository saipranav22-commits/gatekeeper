package com.gatekeeper.analytics.dto;

import java.util.UUID;

public record TopConsumerDto(
    UUID consumerId,
    String consumerName,
    String consumerEmail,
    String plan,
    long totalRequests,
    long allowedRequests,
    long rateLimitedRequests,
    double rateLimitDenialPercentage
) {}
