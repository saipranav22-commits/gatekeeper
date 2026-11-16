package com.gatekeeper.consumer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateConsumerRequest(
    @NotBlank(message = "Consumer name is required")
    @Size(max = 100, message = "Consumer name cannot exceed 100 characters")
    String name,

    @NotBlank(message = "Consumer email is required")
    @Email(message = "Invalid email format")
    @Size(max = 150, message = "Email cannot exceed 150 characters")
    String email,

    @NotNull(message = "Subscription plan ID is required")
    UUID planId
) {
}
