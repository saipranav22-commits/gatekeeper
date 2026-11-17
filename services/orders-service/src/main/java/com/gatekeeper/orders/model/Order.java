package com.gatekeeper.orders.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Order(
    String id,
    String consumerId,
    String itemSku,
    int quantity,
    BigDecimal totalPrice,
    String status,
    Instant createdAt
) {}
