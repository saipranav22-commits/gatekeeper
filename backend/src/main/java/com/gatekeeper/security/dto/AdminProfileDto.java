package com.gatekeeper.security.dto;

import java.util.UUID;

public record AdminProfileDto(
    UUID id,
    String username,
    String email,
    String role,
    boolean mustChangePassword
) {}
