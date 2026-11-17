package com.gatekeeper.routing.model;

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable definition of an authorized upstream gateway route.
 */
public record GatewayRoute(
    String routeId,
    String pathPattern,
    String pathPrefix,
    String upstreamServiceId,
    URI upstreamBaseUri,
    Set<String> allowedMethods,
    Map<String, String> methodScopes,
    String defaultScope,
    boolean enabled,
    int timeoutMs,
    long maxBodySizeBytes
) {
    public GatewayRoute {
        Objects.requireNonNull(routeId, "routeId must not be null");
        Objects.requireNonNull(pathPattern, "pathPattern must not be null");
        Objects.requireNonNull(upstreamServiceId, "upstreamServiceId must not be null");
        Objects.requireNonNull(upstreamBaseUri, "upstreamBaseUri must not be null");

        if (allowedMethods == null || allowedMethods.isEmpty()) {
            allowedMethods = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD");
        } else {
            allowedMethods = Collections.unmodifiableSet(allowedMethods);
        }

        if (methodScopes == null) {
            methodScopes = Collections.emptyMap();
        } else {
            methodScopes = Collections.unmodifiableMap(methodScopes);
        }

        if (timeoutMs <= 0) {
            timeoutMs = 5000;
        }

        if (maxBodySizeBytes <= 0) {
            maxBodySizeBytes = 1048576L; // 1 MB default
        }
    }

    /**
     * Resolves the required scope for a specific HTTP method on this route.
     */
    public String resolveScopeForMethod(String httpMethod) {
        if (httpMethod != null) {
            String upper = httpMethod.trim().toUpperCase();
            String methodScope = methodScopes.get(upper);
            if (methodScope != null && !methodScope.isBlank()) {
                return methodScope;
            }
        }
        return defaultScope;
    }

    /**
     * Checks if the given HTTP method is permitted on this route.
     */
    public boolean isMethodAllowed(String httpMethod) {
        if (httpMethod == null) return false;
        return allowedMethods.contains(httpMethod.trim().toUpperCase());
    }
}
