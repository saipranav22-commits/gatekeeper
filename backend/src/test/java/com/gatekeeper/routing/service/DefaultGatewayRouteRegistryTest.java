package com.gatekeeper.routing.service;

import com.gatekeeper.routing.config.GatewayRoutingProperties;
import com.gatekeeper.routing.model.GatewayRoute;
import com.gatekeeper.routing.model.RouteMatchResult;
import com.gatekeeper.routing.validator.SsrfProtectionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GatewayRouteRegistry Unit Tests")
class DefaultGatewayRouteRegistryTest {

    private DefaultGatewayRouteRegistry registry;
    private SsrfProtectionValidator ssrfValidator;

    @BeforeEach
    void setUp() {
        ssrfValidator = new SsrfProtectionValidator();
        GatewayRoutingProperties properties = new GatewayRoutingProperties();
        registry = new DefaultGatewayRouteRegistry(properties, ssrfValidator);
        registry.initializeRoutes();
    }

    @Test
    @DisplayName("Registry initializes with default Orders and Inventory routes")
    void shouldInitializeWithDefaultRoutes() {
        assertThat(registry.getAllRoutes()).hasSize(2);

        Optional<GatewayRoute> ordersRoute = registry.getRoute("orders-service-v1");
        assertThat(ordersRoute).isPresent();
        assertThat(ordersRoute.get().pathPattern()).isEqualTo("/api/v1/proxy/orders/**");
        assertThat(ordersRoute.get().upstreamBaseUri()).isEqualTo(URI.create("http://localhost:8081"));

        Optional<GatewayRoute> inventoryRoute = registry.getRoute("inventory-service-v1");
        assertThat(inventoryRoute).isPresent();
        assertThat(inventoryRoute.get().pathPattern()).isEqualTo("/api/v1/proxy/inventory/**");
        assertThat(inventoryRoute.get().upstreamBaseUri()).isEqualTo(URI.create("http://localhost:8082"));
    }

    @Test
    @DisplayName("Match Orders GET route and strip proxy prefix")
    void shouldMatchOrdersGetRoute() {
        Optional<RouteMatchResult> match = registry.matchRoute("/api/v1/proxy/orders/ORD-001", "GET");

        assertThat(match).isPresent();
        assertThat(match.get().route().routeId()).isEqualTo("orders-service-v1");
        assertThat(match.get().targetPath()).isEqualTo("/orders/ORD-001");
        assertThat(match.get().requiredScope()).isEqualTo("orders:read");
    }

    @Test
    @DisplayName("Match Orders POST route with method scope")
    void shouldMatchOrdersPostRoute() {
        Optional<RouteMatchResult> match = registry.matchRoute("/api/v1/proxy/orders", "POST");

        assertThat(match).isPresent();
        assertThat(match.get().route().routeId()).isEqualTo("orders-service-v1");
        assertThat(match.get().targetPath()).isEqualTo("/orders");
        assertThat(match.get().requiredScope()).isEqualTo("orders:write");
    }

    @Test
    @DisplayName("Match Inventory GET route")
    void shouldMatchInventoryGetRoute() {
        Optional<RouteMatchResult> match = registry.matchRoute("/api/v1/proxy/inventory/SKU-1001", "GET");

        assertThat(match).isPresent();
        assertThat(match.get().route().routeId()).isEqualTo("inventory-service-v1");
        assertThat(match.get().targetPath()).isEqualTo("/inventory/SKU-1001");
        assertThat(match.get().requiredScope()).isEqualTo("inventory:read");
    }

    @Test
    @DisplayName("Return empty Optional when no route matches")
    void shouldReturnEmptyForUnknownRoute() {
        Optional<RouteMatchResult> match = registry.matchRoute("/api/v1/proxy/unknown/path", "GET");
        assertThat(match).isEmpty();
    }

    @Test
    @DisplayName("Reject path traversal attack in matchRoute")
    void shouldRejectPathTraversalInMatchRoute() {
        assertThatThrownBy(() -> registry.matchRoute("/api/v1/proxy/orders/../admin", "GET"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Invalid path traversal sequence");
    }

    @Test
    @DisplayName("Register custom route dynamically")
    void shouldRegisterCustomRouteDynamically() {
        GatewayRoute customRoute = new GatewayRoute(
            "custom-service-v1",
            "/api/v1/proxy/custom/**",
            "/api/v1/proxy",
            "custom-service",
            URI.create("http://localhost:8083"),
            Set.of("GET"),
            Map.of("GET", "custom:read"),
            "custom:read",
            true,
            3000,
            2048576L
        );

        registry.registerRoute(customRoute);

        Optional<GatewayRoute> retrieved = registry.getRoute("custom-service-v1");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().upstreamBaseUri()).isEqualTo(URI.create("http://localhost:8083"));
    }
}
