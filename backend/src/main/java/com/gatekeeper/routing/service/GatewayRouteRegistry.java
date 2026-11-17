package com.gatekeeper.routing.service;

import com.gatekeeper.routing.model.GatewayRoute;
import com.gatekeeper.routing.model.RouteMatchResult;

import java.util.List;
import java.util.Optional;

/**
 * Thread-safe registry and matcher for gateway routes.
 */
public interface GatewayRouteRegistry {

    /**
     * Matches an incoming request URI against registered gateway routes.
     *
     * @param requestPath the request path (e.g. /api/v1/proxy/orders/123)
     * @param httpMethod the HTTP method (GET, POST, etc.)
     * @return Optional containing RouteMatchResult if matched, or empty if no route matches
     */
    Optional<RouteMatchResult> matchRoute(String requestPath, String httpMethod);

    /**
     * Retrieves a route by its unique routeId.
     *
     * @param routeId the route identifier
     * @return Optional containing the route if found
     */
    Optional<GatewayRoute> getRoute(String routeId);

    /**
     * Retrieves all registered routes.
     *
     * @return list of registered routes
     */
    List<GatewayRoute> getAllRoutes();

    /**
     * Registers or updates a route.
     *
     * @param route the route to register
     */
    void registerRoute(GatewayRoute route);
}
