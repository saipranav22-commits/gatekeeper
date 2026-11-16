package com.gatekeeper.analytics.dto;

import java.time.Instant;
import java.util.Map;

public record AnalyticsOverviewDto(
    long totalRequests,
    long allowedRequests,
    long rateLimitedRequests,
    long scopeDeniedRequests,
    long errorRequests,
    double rateLimitDenialPercentage,
    Map<String, Long> usageByPlan,
    Instant windowStart,
    Instant windowEnd
) {}
