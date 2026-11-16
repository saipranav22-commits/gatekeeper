package com.gatekeeper.security.dto;

import java.time.Instant;
import java.util.UUID;

public record AdminJwtClaims(
    UUID adminId,
    String username,
    String role,
    long tokenVersion,
    Instant issuedAt,
    Instant expiresAt
) {}
