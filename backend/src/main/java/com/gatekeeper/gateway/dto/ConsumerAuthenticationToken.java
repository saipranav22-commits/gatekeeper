package com.gatekeeper.gateway.dto;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Objects;

/**
 * Spring Security Authentication token representing an authenticated consumer principal.
 */
public class ConsumerAuthenticationToken extends AbstractAuthenticationToken {

    private final ConsumerPrincipal principal;

    public ConsumerAuthenticationToken(ConsumerPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = Objects.requireNonNull(principal, "principal must not be null");
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null; // Secrets are not stored in the authentication token
    }

    @Override
    public ConsumerPrincipal getPrincipal() {
        return principal;
    }
}
