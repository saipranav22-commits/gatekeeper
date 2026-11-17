package com.gatekeeper.resilience.retry;

import com.gatekeeper.resilience.circuitbreaker.RouteCircuitBreaker;
import com.gatekeeper.resilience.config.RouteResilienceProperties;
import com.gatekeeper.resilience.engine.DefaultRouteResilienceEngine;
import com.gatekeeper.routing.model.GatewayRoute;
import com.gatekeeper.routing.proxy.HttpProxyEngine;
import com.gatekeeper.routing.proxy.UpstreamResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("RetryExecutionIntegrationTest - Phase 5B.2")
class RetryExecutionIntegrationTest {

    private RouteResilienceProperties properties;
    private HttpProxyEngine proxyEngine;
    private DefaultRouteResilienceEngine resilienceEngine;
    private GatewayRoute ordersRoute;

    private static final HttpHeaders EMPTY_HEADERS = HttpHeaders.of(Map.of(), (k, v) -> true);

    @BeforeEach
    void setUp() throws Exception {
        properties = new RouteResilienceProperties();
        properties.setEnabled(true);
        properties.setDefaultMaxAttempts(3);
        properties.setDefaultInitialBackoffMs(10L); // fast backoff for tests
        properties.setDefaultBackoffMultiplier(2.0);
        properties.setDefaultJitterFactor(0.2);
        properties.setDefaultMaxCumulativeDeadlineMs(500L);

        proxyEngine = mock(HttpProxyEngine.class);
        doAnswer(inv -> {
            HttpServletResponse r = inv.getArgument(0);
            org.springframework.http.HttpStatus st = inv.getArgument(2);
            r.setStatus(st.value());
            return null;
        }).when(proxyEngine).sendError(any(), any(), any(), any());

        doAnswer(inv -> {
            HttpServletResponse r = inv.getArgument(0);
            UpstreamResponse ur = inv.getArgument(1);
            r.setStatus(ur.statusCode());
            return null;
        }).when(proxyEngine).writeUpstreamResponse(any(), any());

        resilienceEngine = new DefaultRouteResilienceEngine(properties, proxyEngine);

        ordersRoute = new GatewayRoute(
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
    }

    @Test
    @DisplayName("Idempotent GET retries on transient 503 and returns 200 on subsequent success")
    void getRetriesOnTransientErrorAndSucceeds() throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);

        doAnswer(invocation -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt == 1) {
                // First attempt fails with transient 503
                return new UpstreamResponse(503, EMPTY_HEADERS, "{\"error\":\"Service Unavailable\"}".getBytes(), 20);
            }
            // Second attempt succeeds
            return new UpstreamResponse(200, EMPTY_HEADERS, "[{\"id\":\"ord-1\"}]".getBytes(), 25);
        }).when(proxyEngine).dispatchAttempt(any(), eq(ordersRoute), any(), any());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/proxy/orders");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        resilienceEngine.execute(req, resp, ordersRoute, "orders");

        // Client receives HTTP 200
        assertThat(resp.getStatus()).isEqualTo(200);
        // Upstream was invoked exactly 2 times
        assertThat(attemptCount.get()).isEqualTo(2);
        // Telemetry recorded retry count of 1
        assertThat((Integer) req.getAttribute("GATEKEEPER_RETRY_COUNT")).isEqualTo(1);

        // Circuit breaker records 1 logical request outcome (SUCCESS)
        RouteCircuitBreaker cb = resilienceEngine.getCircuitBreaker("orders-service-v1");
        assertThat(cb.getMetrics().totalCalls()).isEqualTo(1);
        assertThat(cb.getMetrics().successfulCalls()).isEqualTo(1);
        assertThat(cb.getMetrics().failedCalls()).isZero();
    }

    @Test
    @DisplayName("Non-idempotent POST is NEVER retried automatically on failure")
    void postIsNeverRetriedAutomatically() throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);

        doAnswer(invocation -> {
            attemptCount.incrementAndGet();
            return new UpstreamResponse(503, EMPTY_HEADERS, "{\"error\":\"Temporary failure\"}".getBytes(), 20);
        }).when(proxyEngine).dispatchAttempt(any(), eq(ordersRoute), any(), any());

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/proxy/orders");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        resilienceEngine.execute(req, resp, ordersRoute, "orders");

        // Client receives 503
        assertThat(resp.getStatus()).isEqualTo(503);
        // Upstream was invoked EXACTLY ONCE
        assertThat(attemptCount.get()).isEqualTo(1);
        // Telemetry records 0 retries
        assertThat((Integer) req.getAttribute("GATEKEEPER_RETRY_COUNT")).isZero();
    }

    @Test
    @DisplayName("Continuous transient failures retry up to maxAttempts (3) then fail")
    void continuousFailuresBoundedByMaxAttempts() throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);

        doAnswer(invocation -> {
            attemptCount.incrementAndGet();
            throw new ConnectException("Connection refused");
        }).when(proxyEngine).dispatchAttempt(any(), eq(ordersRoute), any(), any());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/proxy/orders");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        resilienceEngine.execute(req, resp, ordersRoute, "orders");

        // Max attempts is 3 -> 1 initial + 2 retries
        assertThat(attemptCount.get()).isEqualTo(3);
        assertThat((Integer) req.getAttribute("GATEKEEPER_RETRY_COUNT")).isEqualTo(2);
    }
}
