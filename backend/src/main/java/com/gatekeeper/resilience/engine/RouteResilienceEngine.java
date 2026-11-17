package com.gatekeeper.resilience.engine;

import com.gatekeeper.resilience.bulkhead.RouteBulkhead;
import com.gatekeeper.resilience.circuitbreaker.RouteCircuitBreaker;
import com.gatekeeper.resilience.retry.RetryPolicy;
import com.gatekeeper.routing.model.GatewayRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * Coordinates circuit breaker, concurrency bulkhead, and safe retry policies
 * for upstream gateway dispatches.
 */
public interface RouteResilienceEngine {

    /**
     * Executes an upstream dispatch under the route's resilience policies.
     *
     * @param request incoming HTTP servlet request
     * @param response outgoing HTTP servlet response
     * @param route matched gateway route definition
     * @param targetPath relative upstream path
     * @throws IOException on I/O error
     */
    void execute(
        HttpServletRequest request,
        HttpServletResponse response,
        GatewayRoute route,
        String targetPath
    ) throws IOException;

    RouteCircuitBreaker getCircuitBreaker(String routeId);

    RouteBulkhead getBulkhead(String routeId);

    RetryPolicy getRetryPolicy(String routeId);

    Map<String, RouteCircuitBreaker> getAllCircuitBreakers();

    Map<String, RouteBulkhead> getAllBulkheads();
}
