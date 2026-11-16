package com.gatekeeper.analytics.dto;

import java.time.Instant;

public record TimeSeriesPointDto(
    Instant bucket,
    long totalRequests,
    long allowedRequests,
    long rateLimitedRequests,
    long scopeDeniedRequests,
    long errorRequests
) {}
