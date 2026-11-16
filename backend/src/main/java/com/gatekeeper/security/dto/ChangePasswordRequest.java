package com.gatekeeper.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    @NotBlank(message = "Current password is required")
    String oldPassword,

    @NotBlank(message = "New password is required")
    @Size(min = 12, max = 100, message = "New password must be between 12 and 100 characters")
    String newPassword
) {}
