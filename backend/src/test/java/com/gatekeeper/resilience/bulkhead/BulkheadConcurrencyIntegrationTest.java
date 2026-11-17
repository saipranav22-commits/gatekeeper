package com.gatekeeper.resilience.bulkhead;

import com.gatekeeper.resilience.config.RouteResilienceProperties;
import com.gatekeeper.resilience.engine.DefaultRouteResilienceEngine;
import com.gatekeeper.routing.model.GatewayRoute;
import com.gatekeeper.routing.proxy.HttpProxyEngine;
import com.gatekeeper.routing.proxy.UpstreamResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@DisplayName("BulkheadConcurrencyIntegrationTest - Phase 5B.2")
class BulkheadConcurrencyIntegrationTest {

    private RouteResilienceProperties properties;
    private HttpProxyEngine proxyEngine;
    private DefaultRouteResilienceEngine resilienceEngine;

    private GatewayRoute ordersRoute;
    private GatewayRoute inventoryRoute;

    private static final HttpHeaders EMPTY_HEADERS = HttpHeaders.of(Map.of(), (k, v) -> true);

    @BeforeEach
    void setUp() throws Exception {
        properties = new RouteResilienceProperties();
        properties.setEnabled(true);
        properties.setDefaultMaxConcurrentCalls(10); // 10 permits capacity

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
            Map.of("GET", "orders:read"),
            "orders:read",
            true,
            5000,
            1048576L
        );

        inventoryRoute = new GatewayRoute(
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
    }

    @Test
    @DisplayName("Bulkhead limits concurrent calls to exactly capacity and rejects excess requests without leaks")
    void realConcurrencySaturationAndIsolation() throws Exception {
        int capacity = 10;
        int totalOrdersRequests = 30; // 30 concurrent calls against 10 permits
        ExecutorService executor = Executors.newFixedThreadPool(totalOrdersRequests + 5);

        CountDownLatch slowCallStartLatch = new CountDownLatch(capacity);
        CountDownLatch releaseSlowCallsLatch = new CountDownLatch(1);
        CountDownLatch allDoneLatch = new CountDownLatch(totalOrdersRequests);

        AtomicInteger inFlightOrdersPeak = new AtomicInteger(0);
        AtomicInteger currentInFlightOrders = new AtomicInteger(0);
        AtomicInteger ordersAdmitted = new AtomicInteger(0);
        AtomicInteger ordersRejected = new AtomicInteger(0);

        // Mock proxy dispatchAttempt for orders: hold calls until releaseSlowCallsLatch counts down
        doAnswer(invocation -> {
            int current = currentInFlightOrders.incrementAndGet();
            inFlightOrdersPeak.accumulateAndGet(current, Math::max);
            ordersAdmitted.incrementAndGet();

            slowCallStartLatch.countDown();
            try {
                // Block until signaled
                releaseSlowCallsLatch.await(30, TimeUnit.SECONDS);
            } finally {
                currentInFlightOrders.decrementAndGet();
            }

            return new UpstreamResponse(200, EMPTY_HEADERS, "[]".getBytes(), 50);
        }).when(proxyEngine).dispatchAttempt(any(), eq(ordersRoute), any(), any());

        // Pre-configure proxy dispatchAttempt for inventory route before concurrent thread launch
        doAnswer(invocation -> new UpstreamResponse(200, EMPTY_HEADERS, "[]".getBytes(), 10))
            .when(proxyEngine).dispatchAttempt(any(), eq(inventoryRoute), any(), any());

        // Launch 30 concurrent orders calls
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < totalOrdersRequests; i++) {
            futures.add(executor.submit(() -> {
                MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/proxy/orders");
                MockHttpServletResponse resp = new MockHttpServletResponse();
                try {
                    resilienceEngine.execute(req, resp, ordersRoute, "orders");
                    if (resp.getStatus() == 503) {
                        ordersRejected.incrementAndGet();
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    allDoneLatch.countDown();
                }
            }));
        }

        // Wait until all 10 capacity permits are active and blocked inside the upstream dispatch
        assertThat(slowCallStartLatch.await(10, TimeUnit.SECONDS)).isTrue();

        // Verify peak in-flight calls NEVER exceeded capacity
        assertThat(inFlightOrdersPeak.get()).isEqualTo(capacity);
        RouteBulkhead ordersBulkhead = resilienceEngine.getBulkhead("orders-service-v1");
        assertThat(ordersBulkhead.getAvailablePermits()).isZero();
        assertThat(ordersBulkhead.getInFlightCalls()).isEqualTo(capacity);

        // CROSS-ROUTE ISOLATION TEST:
        // While Orders bulkhead is 100% saturated, execute an Inventory request
        MockHttpServletRequest invReq = new MockHttpServletRequest("GET", "/api/v1/proxy/inventory");
        MockHttpServletResponse invResp = new MockHttpServletResponse();
        resilienceEngine.execute(invReq, invResp, inventoryRoute, "inventory");

        // Inventory route must be 100% operational and return 200 OK
        assertThat(invResp.getStatus()).isEqualTo(200);
        RouteBulkhead invBulkhead = resilienceEngine.getBulkhead("inventory-service-v1");
        assertThat(invBulkhead.getAvailablePermits()).isEqualTo(capacity); // Inventory permits completely intact!

        // Now release the held orders requests
        releaseSlowCallsLatch.countDown();
        assertThat(allDoneLatch.await(15, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Exactly 10 calls were admitted and 20 were rejected by the bulkhead
        assertThat(ordersAdmitted.get()).isEqualTo(capacity);
        assertThat(ordersRejected.get()).isEqualTo(totalOrdersRequests - capacity);
        assertThat(ordersBulkhead.getRejectedCalls()).isEqualTo(totalOrdersRequests - capacity);

        // Verify NO PERMIT LEAKS: all 10 permits must be returned to the bulkhead
        assertThat(ordersBulkhead.getAvailablePermits()).isEqualTo(capacity);
        assertThat(ordersBulkhead.getInFlightCalls()).isZero();

        // A new request can now immediately acquire a permit and proceed
        MockHttpServletRequest subsequentReq = new MockHttpServletRequest("GET", "/api/v1/proxy/orders");
        MockHttpServletResponse subsequentResp = new MockHttpServletResponse();
        resilienceEngine.execute(subsequentReq, subsequentResp, ordersRoute, "orders");
        assertThat(subsequentResp.getStatus()).isEqualTo(200);
    }
}
