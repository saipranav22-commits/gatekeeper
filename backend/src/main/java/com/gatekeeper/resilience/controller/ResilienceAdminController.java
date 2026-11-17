package com.gatekeeper.resilience.controller;

import com.gatekeeper.resilience.bulkhead.RouteBulkhead;
import com.gatekeeper.resilience.circuitbreaker.CircuitBreakerConfig;
import com.gatekeeper.resilience.circuitbreaker.CircuitBreakerMetrics;
import com.gatekeeper.resilience.circuitbreaker.RouteCircuitBreaker;
import com.gatekeeper.resilience.dto.RouteResilienceDetailDto;
import com.gatekeeper.resilience.dto.RouteResilienceSummaryDto;
import com.gatekeeper.resilience.engine.RouteResilienceEngine;
import com.gatekeeper.resilience.retry.RetryConfig;
import com.gatekeeper.resilience.retry.RetryPolicy;
import com.gatekeeper.routing.model.GatewayRoute;
import com.gatekeeper.routing.service.GatewayRouteRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Administrative REST APIs for monitoring and managing gateway route resilience state:
 * circuit breakers, concurrency bulkheads, and retry policies.
 */
@RestController
@RequestMapping("/api/v1/resilience")
@Profile("!foundation")
@Tag(name = "Resilience", description = "Gateway route resilience, circuit breaker status, and bulkhead telemetry")
public class ResilienceAdminController {

    private final RouteResilienceEngine resilienceEngine;
    private final GatewayRouteRegistry routeRegistry;

    public ResilienceAdminController(
        RouteResilienceEngine resilienceEngine,
        GatewayRouteRegistry routeRegistry
    ) {
        this.resilienceEngine = Objects.requireNonNull(resilienceEngine, "resilienceEngine must not be null");
        this.routeRegistry = Objects.requireNonNull(routeRegistry, "routeRegistry must not be null");
    }

    @GetMapping("/routes")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get resilience status for all routes", description = "Returns active circuit breaker state, metrics, and bulkhead capacity for all registered routes.")
    public ResponseEntity<List<RouteResilienceSummaryDto>> getAllRouteResilience() {
        List<GatewayRoute> routes = routeRegistry.getAllRoutes();
        List<RouteResilienceSummaryDto> summaries = new ArrayList<>();

        for (GatewayRoute route : routes) {
            String routeId = route.routeId();
            RouteCircuitBreaker cb = resilienceEngine.getCircuitBreaker(routeId);
            RouteBulkhead bh = resilienceEngine.getBulkhead(routeId);
            RetryPolicy retry = resilienceEngine.getRetryPolicy(routeId);

            CircuitBreakerMetrics cbMetrics = cb.getMetrics();

            summaries.add(new RouteResilienceSummaryDto(
                routeId,
                cb.getState().name(),
                cbMetrics.failureRate(),
                cbMetrics.slowCallRate(),
                cbMetrics.totalCalls(),
                cbMetrics.failedCalls(),
                cbMetrics.slowCalls(),
                cbMetrics.rejectedCalls(),
                bh.getMaxConcurrentCalls(),
                bh.getAvailablePermits(),
                bh.getInFlightCalls(),
                bh.getRejectedCalls(),
                retry.getConfig().maxAttempts()
            ));
        }

        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/routes/{routeId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get detailed resilience metrics for a specific route", description = "Returns configuration and runtime metrics for circuit breaker, bulkhead, and retry policies.")
    public ResponseEntity<RouteResilienceDetailDto> getRouteResilienceDetail(@PathVariable String routeId) {
        if (routeRegistry.getRoute(routeId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RouteCircuitBreaker cb = resilienceEngine.getCircuitBreaker(routeId);
        RouteBulkhead bh = resilienceEngine.getBulkhead(routeId);
        RetryPolicy retry = resilienceEngine.getRetryPolicy(routeId);

        CircuitBreakerConfig cbConfig = cb.getConfig();
        CircuitBreakerMetrics cbMetrics = cb.getMetrics();
        RetryConfig retryConfig = retry.getConfig();

        RouteResilienceDetailDto detail = new RouteResilienceDetailDto(
            routeId,
            new RouteResilienceDetailDto.CircuitBreakerDetail(
                cb.getState().name(),
                cbConfig.failureRateThreshold(),
                cbConfig.slowCallRateThreshold(),
                cbConfig.slowCallDurationMs(),
                cbConfig.slidingWindowSize(),
                cbConfig.minimumNumberOfCalls(),
                cbConfig.waitDurationInOpenStateMs(),
                cbConfig.permittedNumberOfCallsInHalfOpenState(),
                cbMetrics.totalCalls(),
                cbMetrics.successfulCalls(),
                cbMetrics.failedCalls(),
                cbMetrics.slowCalls(),
                cbMetrics.failureRate(),
                cbMetrics.slowCallRate(),
                cbMetrics.rejectedCalls()
            ),
            new RouteResilienceDetailDto.BulkheadDetail(
                bh.getMaxConcurrentCalls(),
                bh.getAvailablePermits(),
                bh.getInFlightCalls(),
                bh.getRejectedCalls()
            ),
            new RouteResilienceDetailDto.RetryDetail(
                retryConfig.maxAttempts(),
                retryConfig.initialBackoffMs(),
                retryConfig.backoffMultiplier(),
                retryConfig.jitterFactor(),
                retryConfig.maxCumulativeDeadlineMs()
            )
        );

        return ResponseEntity.ok(detail);
    }

    @PostMapping("/routes/{routeId}/reset")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Reset circuit breaker for a route", description = "Manually resets the route's circuit breaker to CLOSED and clears sliding window metrics.")
    public ResponseEntity<Map<String, Object>> resetRouteCircuitBreaker(@PathVariable String routeId) {
        if (routeRegistry.getRoute(routeId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RouteCircuitBreaker cb = resilienceEngine.getCircuitBreaker(routeId);
        cb.reset();

        return ResponseEntity.ok(Map.of(
            "routeId", routeId,
            "status", "RESET",
            "circuitState", cb.getState().name()
        ));
    }
}
