package com.gatekeeper.gateway.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Harmless demonstration response showing successful API key authentication and quota consumption.
 */
public record GatewayDemoResponse(
    String status,
    String service,
    String message,
    UUID consumerId,
    String consumerName,
    String plan,
    Instant timestamp
) {
    public static GatewayDemoResponse success(ConsumerPrincipal principal) {
        return new GatewayDemoResponse(
            "SUCCESS",
            "gateway-demo",
            "Request successfully authenticated and rate limit quota consumed.",
            principal.consumerId(),
            principal.consumerName(),
            principal.plan().name(),
            Instant.now()
        );
    }
}
