package com.gatekeeper.resilience.engine;

import com.gatekeeper.resilience.bulkhead.BulkheadConfig;
import com.gatekeeper.resilience.bulkhead.RouteBulkhead;
import com.gatekeeper.resilience.circuitbreaker.CircuitBreakerConfig;
import com.gatekeeper.resilience.circuitbreaker.CircuitState;
import com.gatekeeper.resilience.circuitbreaker.RouteCircuitBreaker;
import com.gatekeeper.resilience.config.RouteResilienceProperties;
import com.gatekeeper.resilience.retry.RetryConfig;
import com.gatekeeper.resilience.retry.RetryPolicy;
import com.gatekeeper.routing.model.GatewayRoute;
import com.gatekeeper.routing.proxy.DefaultHttpProxyEngine;
import com.gatekeeper.routing.proxy.HttpProxyEngine;
import com.gatekeeper.routing.proxy.UpstreamResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Standard resilience engine coordinating per-route circuit breakers, concurrency bulkheads,
 * safe idempotency-aware retries, and deadline budgets.
 */
@Service
public class DefaultRouteResilienceEngine implements RouteResilienceEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultRouteResilienceEngine.class);

    private final RouteResilienceProperties properties;
    private final HttpProxyEngine httpProxyEngine;
    private final Clock clock;

    private final Map<String, RouteCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final Map<String, RouteBulkhead> bulkheads = new ConcurrentHashMap<>();
    private final Map<String, RetryPolicy> retryPolicies = new ConcurrentHashMap<>();

    @Autowired
    public DefaultRouteResilienceEngine(
        RouteResilienceProperties properties,
        HttpProxyEngine httpProxyEngine
    ) {
        this(properties, httpProxyEngine, Clock.systemUTC());
    }

    public DefaultRouteResilienceEngine(
        RouteResilienceProperties properties,
        HttpProxyEngine httpProxyEngine,
        Clock clock
    ) {
        this.properties = properties;
        this.httpProxyEngine = httpProxyEngine;
        this.clock = clock;
    }

    @Override
    public void execute(
        HttpServletRequest request,
        HttpServletResponse response,
        GatewayRoute route,
        String targetPath
    ) throws IOException {

        if (!properties.isEnabled()) {
            httpProxyEngine.forward(request, response, route, targetPath);
            return;
        }

        String routeId = route.routeId();
        RouteCircuitBreaker circuitBreaker = getCircuitBreaker(routeId);
        RouteBulkhead bulkhead = getBulkhead(routeId);
        RetryPolicy retryPolicy = getRetryPolicy(routeId);

        // Record initial state
        request.setAttribute("GATEKEEPER_CIRCUIT_STATE", circuitBreaker.getState().name());
        request.setAttribute("GATEKEEPER_RETRY_COUNT", 0);
        request.setAttribute("GATEKEEPER_BULKHEAD_REJECTED", false);
        request.setAttribute("GATEKEEPER_ROUTE_ID", routeId);

        // 1. Circuit Breaker Check (Fast Rejection)
        if (!circuitBreaker.tryAcquirePermission()) {
            request.setAttribute("GATEKEEPER_CIRCUIT_STATE", CircuitState.OPEN.name());
            request.setAttribute("GATEKEEPER_RESILIENCE_REJECTION", "CIRCUIT_OPEN");
            log.warn("Circuit breaker OPEN on route '{}'; rejecting incoming request to {}", routeId, request.getRequestURI());
            httpProxyEngine.sendError(
                response,
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "Circuit breaker is OPEN for upstream route '" + routeId + "'. Fast rejection applied."
            );
            return;
        }

        // 2. Bulkhead Concurrency Check
        if (!bulkhead.tryAcquirePermission()) {
            request.setAttribute("GATEKEEPER_BULKHEAD_REJECTED", true);
            request.setAttribute("GATEKEEPER_RESILIENCE_REJECTION", "BULKHEAD_REJECTED");
            log.warn("Bulkhead concurrency limit reached on route '{}'; rejecting request", routeId);
            httpProxyEngine.sendError(
                response,
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "Concurrency limit exceeded on route '" + routeId + "'. Bulkhead rejection applied."
            );
            return;
        }

        // 3. Execution under Bulkhead Permit
        try {
            boolean isSafeToRetry = retryPolicy.isSafeMethodToRetry(request.getMethod());
            int maxAttempts = isSafeToRetry ? retryPolicy.getConfig().maxAttempts() : 1;
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryPolicy.getConfig().maxCumulativeDeadlineMs());
            long overallStartNanos = System.nanoTime();

            UpstreamResponse lastSuccess = null;
            Exception lastException = null;
            int attempt = 0;

            while (attempt < maxAttempts) {
                attempt++;
                request.setAttribute("GATEKEEPER_RETRY_COUNT", attempt - 1);

                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    lastException = new HttpTimeoutException("Cumulative request deadline exceeded");
                    break;
                }

                Duration attemptTimeout = Duration.ofNanos(
                    Math.min(TimeUnit.MILLISECONDS.toNanos(route.timeoutMs()), remainingNanos)
                );

                try {
                    UpstreamResponse upstreamResponse = httpProxyEngine.dispatchAttempt(
                        request,
                        route,
                        targetPath,
                        attemptTimeout
                    );

                    lastSuccess = upstreamResponse;

                    if (upstreamResponse.isSuccess()) {
                        break;
                    }

                    // Transient status code check (e.g. 502, 503, 504)
                    if (isSafeToRetry && attempt < maxAttempts && retryPolicy.isRetryableFailure(upstreamResponse.statusCode(), null)) {
                        long backoffMs = retryPolicy.computeBackoffMs(attempt);
                        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                        if (backoffMs < remainingMs) {
                            try {
                                Thread.sleep(backoffMs);
                                continue;
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }

                    break;

                } catch (Exception ex) {
                    lastException = ex;

                    if (isSafeToRetry && attempt < maxAttempts && retryPolicy.isRetryableFailure(0, ex)) {
                        long backoffMs = retryPolicy.computeBackoffMs(attempt);
                        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                        if (backoffMs < remainingMs) {
                            try {
                                Thread.sleep(backoffMs);
                                continue;
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }

                    break;
                }
            }

            long totalDurationMs = Math.max(1, (System.nanoTime() - overallStartNanos) / 1_000_000);

            // 4. Handle Final Result
            if (lastSuccess != null) {
                int status = lastSuccess.statusCode();
                request.setAttribute("GATEKEEPER_UPSTREAM_STATUS", status);
                request.setAttribute("GATEKEEPER_UPSTREAM_LATENCY_MS", (int) lastSuccess.latencyMs());

                if (lastSuccess.isSuccess()) {
                    circuitBreaker.recordSuccess(lastSuccess.latencyMs());
                } else {
                    circuitBreaker.recordFailure(lastSuccess.latencyMs(), null);
                }

                httpProxyEngine.writeUpstreamResponse(response, lastSuccess);

            } else {
                circuitBreaker.recordFailure(totalDurationMs, lastException);

                if (lastException instanceof HttpTimeoutException) {
                    request.setAttribute("GATEKEEPER_UPSTREAM_STATUS", 504);
                    request.setAttribute("GATEKEEPER_UPSTREAM_LATENCY_MS", (int) totalDurationMs);
                    request.setAttribute("GATEKEEPER_RESILIENCE_REJECTION", "UPSTREAM_TIMEOUT");
                    httpProxyEngine.sendError(
                        response,
                        request,
                        HttpStatus.GATEWAY_TIMEOUT,
                        "Gateway Timeout: upstream service failed to respond within deadline"
                    );
                } else if (lastException instanceof ConnectException) {
                    request.setAttribute("GATEKEEPER_UPSTREAM_STATUS", 502);
                    request.setAttribute("GATEKEEPER_UPSTREAM_LATENCY_MS", (int) totalDurationMs);
                    request.setAttribute("GATEKEEPER_RESILIENCE_REJECTION", "UPSTREAM_CONNECTION_REFUSED");
                    httpProxyEngine.sendError(
                        response,
                        request,
                        HttpStatus.BAD_GATEWAY,
                        "Bad Gateway: unable to connect to upstream service at " + route.upstreamBaseUri()
                    );
                } else if (lastException instanceof DefaultHttpProxyEngine.PayloadTooLargeException) {
                    httpProxyEngine.sendError(
                        response,
                        request,
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        lastException.getMessage()
                    );
                } else if (lastException instanceof SecurityException) {
                    httpProxyEngine.sendError(
                        response,
                        request,
                        HttpStatus.FORBIDDEN,
                        "Access to upstream target forbidden: " + lastException.getMessage()
                    );
                } else {
                    request.setAttribute("GATEKEEPER_UPSTREAM_STATUS", 502);
                    request.setAttribute("GATEKEEPER_UPSTREAM_LATENCY_MS", (int) totalDurationMs);
                    request.setAttribute("GATEKEEPER_RESILIENCE_REJECTION", "UPSTREAM_IO_ERROR");
                    httpProxyEngine.sendError(
                        response,
                        request,
                        HttpStatus.BAD_GATEWAY,
                        "Bad Gateway: error communicating with upstream service"
                    );
                }
            }

        } finally {
            bulkhead.releasePermission();
        }
    }

    @Override
    public RouteCircuitBreaker getCircuitBreaker(String routeId) {
        return circuitBreakers.computeIfAbsent(routeId, id -> {
            RouteResilienceProperties.RouteOverride override = properties.getRoutes().get(id);

            int failureThreshold = (override != null && override.getFailureRateThreshold() != null)
                ? override.getFailureRateThreshold() : properties.getDefaultFailureRateThreshold();

            int slowCallThreshold = (override != null && override.getSlowCallRateThreshold() != null)
                ? override.getSlowCallRateThreshold() : properties.getDefaultSlowCallRateThreshold();

            long slowDuration = (override != null && override.getSlowCallDurationMs() != null)
                ? override.getSlowCallDurationMs() : properties.getDefaultSlowCallDurationMs();

            int windowSize = (override != null && override.getSlidingWindowSize() != null)
                ? override.getSlidingWindowSize() : properties.getDefaultSlidingWindowSize();

            int minCalls = (override != null && override.getMinimumNumberOfCalls() != null)
                ? override.getMinimumNumberOfCalls() : properties.getDefaultMinimumNumberOfCalls();

            long openWait = (override != null && override.getWaitDurationInOpenStateMs() != null)
                ? override.getWaitDurationInOpenStateMs() : properties.getDefaultWaitDurationInOpenStateMs();

            int halfOpenPermitted = (override != null && override.getPermittedCallsInHalfOpen() != null)
                ? override.getPermittedCallsInHalfOpen() : properties.getDefaultPermittedCallsInHalfOpen();

            CircuitBreakerConfig cbConfig = new CircuitBreakerConfig(
                failureThreshold,
                slowCallThreshold,
                slowDuration,
                windowSize,
                minCalls,
                openWait,
                halfOpenPermitted
            );

            return new RouteCircuitBreaker(id, cbConfig, clock);
        });
    }

    @Override
    public RouteBulkhead getBulkhead(String routeId) {
        return bulkheads.computeIfAbsent(routeId, id -> {
            RouteResilienceProperties.RouteOverride override = properties.getRoutes().get(id);

            int maxConcurrent = (override != null && override.getMaxConcurrentCalls() != null)
                ? override.getMaxConcurrentCalls() : properties.getDefaultMaxConcurrentCalls();

            return new RouteBulkhead(id, new BulkheadConfig(maxConcurrent));
        });
    }

    @Override
    public RetryPolicy getRetryPolicy(String routeId) {
        return retryPolicies.computeIfAbsent(routeId, id -> {
            RouteResilienceProperties.RouteOverride override = properties.getRoutes().get(id);

            int maxAttempts = (override != null && override.getMaxAttempts() != null)
                ? override.getMaxAttempts() : properties.getDefaultMaxAttempts();

            long initialBackoff = (override != null && override.getInitialBackoffMs() != null)
                ? override.getInitialBackoffMs() : properties.getDefaultInitialBackoffMs();

            long maxDeadline = (override != null && override.getMaxCumulativeDeadlineMs() != null)
                ? override.getMaxCumulativeDeadlineMs() : properties.getDefaultMaxCumulativeDeadlineMs();

            RetryConfig retryConfig = new RetryConfig(
                maxAttempts,
                initialBackoff,
                properties.getDefaultBackoffMultiplier(),
                properties.getDefaultJitterFactor(),
                maxDeadline
            );

            return new RetryPolicy(retryConfig);
        });
    }

    @Override
    public Map<String, RouteCircuitBreaker> getAllCircuitBreakers() {
        return Collections.unmodifiableMap(circuitBreakers);
    }

    @Override
    public Map<String, RouteBulkhead> getAllBulkheads() {
        return Collections.unmodifiableMap(bulkheads);
    }
}
