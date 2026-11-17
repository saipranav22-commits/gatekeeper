package com.gatekeeper.orders.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateOrderRequest(
    String consumerId,

    @NotBlank(message = "itemSku is required")
    String itemSku,

    @Min(value = 1, message = "quantity must be at least 1")
    int quantity,

    @NotNull(message = "totalPrice is required")
    @DecimalMin(value = "0.01", message = "totalPrice must be positive")
    BigDecimal totalPrice
) {}
