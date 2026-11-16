package com.gatekeeper.security.dto;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record AdminPrincipal(
    UUID adminId,
    String username,
    String role,
    long tokenVersion
) implements Principal, Serializable {

    @Override
    public String getName() {
        return username;
    }
}
