package com.gatekeeper.security.dto;

import java.time.Instant;

public record LoginResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    Instant expiresAt,
    AdminProfileDto admin
) {}
