package com.gatekeeper.routing.service;

import com.gatekeeper.routing.config.GatewayRoutingProperties;
import com.gatekeeper.routing.model.GatewayRoute;
import com.gatekeeper.routing.model.RouteMatchResult;
import com.gatekeeper.routing.validator.SsrfProtectionValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe default implementation of GatewayRouteRegistry.
 */
@Service
public class DefaultGatewayRouteRegistry implements GatewayRouteRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultGatewayRouteRegistry.class);

    private final GatewayRoutingProperties routingProperties;
    private final SsrfProtectionValidator ssrfProtectionValidator;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final Map<String, GatewayRoute> routesById = new ConcurrentHashMap<>();

    public DefaultGatewayRouteRegistry(
        GatewayRoutingProperties routingProperties,
        SsrfProtectionValidator ssrfProtectionValidator
    ) {
        this.routingProperties = routingProperties;
        this.ssrfProtectionValidator = ssrfProtectionValidator;
    }

    @PostConstruct
    public void initializeRoutes() {
        if (routingProperties.getRoutes() != null && !routingProperties.getRoutes().isEmpty()) {
            for (GatewayRoutingProperties.RouteDefinition def : routingProperties.getRoutes()) {
                try {
                    URI uri = URI.create(def.getUpstreamBaseUri());
                    ssrfProtectionValidator.validateUpstreamUri(uri);
                    GatewayRoute route = new GatewayRoute(
                        def.getRouteId(),
                        def.getPathPattern(),
                        def.getPathPrefix(),
                        def.getUpstreamServiceId(),
                        uri,
                        def.getAllowedMethods(),
                        def.getMethodScopes(),
                        def.getDefaultScope(),
                        def.isEnabled(),
                        def.getTimeoutMs(),
                        def.getMaxBodySizeBytes()
                    );
                    registerRoute(route);
                } catch (Exception e) {
                    log.error("Failed to register configured route '{}': {}", def.getRouteId(), e.getMessage(), e);
                }
            }
        }

        // Seed default routes if not present
        if (!routesById.containsKey("orders-service-v1")) {
            GatewayRoute ordersRoute = new GatewayRoute(
                "orders-service-v1",
                "/api/v1/proxy/orders/**",
                "/api/v1/proxy",
                "orders-service",
                URI.create("http://localhost:8081"),
                Set.of("GET", "POST"),
                Map.of("GET", "orders:read", "POST", "orders:write"),
                "orders:read",
                true,
                5000,
                1048576L
            );
            registerRoute(ordersRoute);
        }

        if (!routesById.containsKey("inventory-service-v1")) {
            GatewayRoute inventoryRoute = new GatewayRoute(
                "inventory-service-v1",
                "/api/v1/proxy/inventory/**",
                "/api/v1/proxy",
                "inventory-service",
                URI.create("http://localhost:8082"),
                Set.of("GET"),
                Map.of("GET", "inventory:read"),
                "inventory:read",
                true,
                5000,
                1048576L
            );
            registerRoute(inventoryRoute);
        }

        log.info("GatewayRouteRegistry initialized with {} routes.", routesById.size());
    }

    @Override
    public Optional<RouteMatchResult> matchRoute(String requestPath, String httpMethod) {
        if (requestPath == null || requestPath.isBlank()) {
            return Optional.empty();
        }

        // Validate path for traversal attacks
        ssrfProtectionValidator.validatePath(requestPath);

        // Sort patterns by specificity
        Comparator<String> patternComparator = pathMatcher.getPatternComparator(requestPath);
        List<GatewayRoute> sortedRoutes = new ArrayList<>(routesById.values());
        sortedRoutes.sort((r1, r2) -> patternComparator.compare(r1.pathPattern(), r2.pathPattern()));

        for (GatewayRoute route : sortedRoutes) {
            if (pathMatcher.match(route.pathPattern(), requestPath)) {
                String targetPath = requestPath;
                if (route.pathPrefix() != null && !route.pathPrefix().isBlank() && requestPath.startsWith(route.pathPrefix())) {
                    targetPath = requestPath.substring(route.pathPrefix().length());
                }
                if (!targetPath.startsWith("/")) {
                    targetPath = "/" + targetPath;
                }

                String requiredScope = route.resolveScopeForMethod(httpMethod);
                return Optional.of(new RouteMatchResult(route, targetPath, requiredScope));
            }
        }

        return Optional.empty();
    }

    @Override
    public Optional<GatewayRoute> getRoute(String routeId) {
        if (routeId == null) return Optional.empty();
        return Optional.ofNullable(routesById.get(routeId));
    }

    @Override
    public List<GatewayRoute> getAllRoutes() {
        return Collections.unmodifiableList(new ArrayList<>(routesById.values()));
    }

    @Override
    public void registerRoute(GatewayRoute route) {
        if (route == null) return;
        ssrfProtectionValidator.validateUpstreamUri(route.upstreamBaseUri());
        routesById.put(route.routeId(), route);
        log.info("Registered route '{}': pattern='{}', target='{}', enabled={}",
            route.routeId(), route.pathPattern(), route.upstreamBaseUri(), route.enabled());
    }
}
