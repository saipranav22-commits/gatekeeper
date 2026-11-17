package com.gatekeeper.gateway.filter;

import com.gatekeeper.analytics.dto.OutcomeCategory;
import com.gatekeeper.analytics.dto.UsageEventRecord;
import com.gatekeeper.analytics.pipeline.UsageEventPublisher;
import com.gatekeeper.gateway.dto.ConsumerPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Outermost filter in the Gateway Security Chain.
 * Captures request timing, correlation tracking, and dispatches asynchronous usage telemetry.
 *
 * <p>Latency Semantics:
 * The recorded {@code latencyMs} measures the full gateway request lifecycle within Gatekeeper,
 * spanning authentication, rate-limiting evaluation, upstream dispatch, and response generation.
 */
@Component
@Profile("!foundation")
public class GatewayUsageTrackingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayUsageTrackingFilter.class);

    public static final String CORRELATION_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_ATTR = "GATEKEEPER_CORRELATION_ID";

    private static final Set<String> ALLOWLISTED_ROUTES = Set.of(
        "/api/v1/gateway/demo",
        "/api/v1/gateway/orders"
    );

    private final UsageEventPublisher usageEventPublisher;

    public GatewayUsageTrackingFilter(@Autowired(required = false) UsageEventPublisher usageEventPublisher) {
        this.usageEventPublisher = usageEventPublisher;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/gateway") && !path.startsWith("/api/v1/proxy");
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        long startNanos = System.nanoTime();

        // 1. Resolve or generate correlation / request ID
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.trim().isEmpty() || correlationId.length() > 64) {
            correlationId = UUID.randomUUID().toString();
        } else {
            correlationId = correlationId.trim();
        }

        request.setAttribute(CORRELATION_ID_ATTR, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                long durationNanos = System.nanoTime() - startNanos;
                int latencyMs = (int) Math.max(1, durationNanos / 1_000_000);
                captureTelemetry(request, response, latencyMs, correlationId);
            } catch (Exception ex) {
                // Telemetry failure policy: never compromise the response returned to the caller
                log.warn("Non-fatal exception while capturing gateway telemetry: {}", ex.getMessage());
            }
        }
    }

    private void captureTelemetry(HttpServletRequest request, HttpServletResponse response,
                                  int latencyMs, String correlationId) {
        if (usageEventPublisher == null) {
            return;
        }

        int status = response.getStatus();
        ConsumerPrincipal principal = (ConsumerPrincipal) request.getAttribute("consumerPrincipal");

        // Outcome classification
        OutcomeCategory outcome;
        boolean rateLimited = false;
        String rejectionReason = null;

        if (status >= 200 && status < 300) {
            outcome = OutcomeCategory.ALLOWED;
        } else if (status == 429) {
            outcome = OutcomeCategory.RATE_LIMITED;
            rateLimited = true;
            rejectionReason = "RATE_LIMIT_EXCEEDED";
        } else if (status == 403) {
            outcome = OutcomeCategory.SCOPE_DENIED;
            rejectionReason = "INSUFFICIENT_SCOPE";
        } else if (status == 503) {
            outcome = OutcomeCategory.SERVICE_UNAVAILABLE;
            rejectionReason = "SERVICE_UNAVAILABLE";
        } else if (status == 401) {
            outcome = OutcomeCategory.UNAUTHORIZED;
            rejectionReason = "AUTHENTICATION_FAILED";
        } else {
            outcome = OutcomeCategory.SERVICE_UNAVAILABLE;
            rejectionReason = "HTTP_" + status;
        }

        // Privacy & Attribution Contract:
        // For missing, malformed or unknown API keys (status 401 with no principal):
        // We do not fabricate a consumer identity or persist plaintext credentials.
        if (principal == null && outcome == OutcomeCategory.UNAUTHORIZED) {
            return;
        }

        String routeId = (String) request.getAttribute("GATEKEEPER_ROUTE_ID");
        Integer upstreamStatus = (Integer) request.getAttribute("GATEKEEPER_UPSTREAM_STATUS");
        Integer upstreamLatencyMs = (Integer) request.getAttribute("GATEKEEPER_UPSTREAM_LATENCY_MS");

        // Normalize route template to prevent unbounded metric cardinality
        String rawPath = request.getRequestURI();
        String normalizedRoute;
        if (routeId != null) {
            normalizedRoute = "/api/v1/proxy/" + routeId;
        } else {
            normalizedRoute = normalizeRoute(rawPath);
        }

        UUID consumerId = principal != null ? principal.consumerId() : null;
        UUID apiKeyId = principal != null ? principal.apiKeyId() : null;
        String emailSnapshot = principal != null ? principal.consumerName() : null;
        String prefixSnapshot = principal != null ? principal.keyPrefix() : null;
        String planName = (principal != null && principal.plan() != null) ? principal.plan().name() : null;

        String clientIp = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 255) {
            userAgent = userAgent.substring(0, 255);
        }

        UsageEventRecord event = new UsageEventRecord(
            UUID.randomUUID(),
            Instant.now(),
            consumerId,
            apiKeyId,
            emailSnapshot,
            prefixSnapshot,
            normalizedRoute,
            request.getMethod(),
            status,
            latencyMs,
            rateLimited,
            rejectionReason,
            outcome,
            planName,
            correlationId,
            clientIp,
            userAgent,
            routeId,
            upstreamStatus,
            upstreamLatencyMs
        );

        usageEventPublisher.publishAsync(event);
    }

    private String normalizeRoute(String uri) {
        if (uri == null) {
            return "/api/v1/gateway/unknown";
        }
        for (String route : ALLOWLISTED_ROUTES) {
            if (uri.equalsIgnoreCase(route)) {
                return route;
            }
        }
        return "/api/v1/gateway/unmatched";
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[0].trim();
        }
        return request.getRemoteAddr();
    }
}
