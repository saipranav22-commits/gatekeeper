package com.gatekeeper.gateway.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Harmless demonstration response for an operation requiring read:orders scope.
 */
public record OrdersDemoResponse(
    String status,
    String service,
    String message,
    UUID consumerId,
    String consumerName,
    String plan,
    List<String> orders,
    Instant timestamp
) {
    public static OrdersDemoResponse success(ConsumerPrincipal principal) {
        return new OrdersDemoResponse(
            "SUCCESS",
            "orders-service",
            "Orders access authorized under scope 'read:orders'.",
            principal.consumerId(),
            principal.consumerName(),
            principal.plan().name(),
            List.of("ORD-1001", "ORD-1002"),
            Instant.now()
        );
    }
}
