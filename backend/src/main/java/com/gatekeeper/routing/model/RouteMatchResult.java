package com.gatekeeper.routing.model;

import java.util.Objects;

/**
 * Result of matching an incoming request URI against the registered gateway routes.
 */
public record RouteMatchResult(
    GatewayRoute route,
    String targetPath,
    String requiredScope
) {
    public RouteMatchResult {
        Objects.requireNonNull(route, "route must not be null");
        if (targetPath == null || targetPath.isBlank()) {
            targetPath = "/";
        } else if (!targetPath.startsWith("/")) {
            targetPath = "/" + targetPath;
        }
    }
}
