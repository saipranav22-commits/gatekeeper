package com.gatekeeper.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.common.response.ErrorResponse;
import com.gatekeeper.gateway.dto.ConsumerPrincipal;
import com.gatekeeper.ratelimit.dto.RateLimitDecision;
import com.gatekeeper.ratelimit.exception.RateLimitUnavailableException;
import com.gatekeeper.ratelimit.service.TokenBucketRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Enforces scope authorization and distributed Redis Token Bucket rate limiting on consumer gateway requests.
 *
 * <p>Execution Order:
 * <ol>
 *   <li>Scope checking occurs BEFORE quota consumption (denied scope requests do not decrement tokens).</li>
 *   <li>Atomic Token Bucket consumption against consumer-level shared bucket in Redis.</li>
 *   <li>Rate limit headers (RateLimit-Limit, RateLimit-Remaining, RateLimit-Reset) attached to response.</li>
 *   <li>Fails closed with HTTP 429 on quota exhaustion (attaching Retry-After header).</li>
 *   <li>Fails closed with HTTP 503 if Redis is unavailable.</li>
 * </ol>
 */
@Component
@org.springframework.context.annotation.Profile("!foundation")
public class ConsumerRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ConsumerRateLimitFilter.class);
    private static final String ALREADY_CONSUMED_ATTR = "GATEKEEPER_RATE_LIMIT_CONSUMED";

    private static final Map<String, String> ENDPOINT_SCOPES = Map.of(
        "/api/v1/gateway/demo", "gateway:invoke",
        "/api/v1/gateway/orders", "read:orders"
    );

    private final TokenBucketRateLimiter tokenBucketRateLimiter;
    private final ObjectMapper objectMapper;

    public ConsumerRateLimitFilter(TokenBucketRateLimiter tokenBucketRateLimiter, ObjectMapper objectMapper) {
        this.tokenBucketRateLimiter = Objects.requireNonNull(tokenBucketRateLimiter, "tokenBucketRateLimiter must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/gateway");
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

        // Prevent double consumption on async dispatch or forward
        if (request.getAttribute(ALREADY_CONSUMED_ATTR) != null) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof ConsumerPrincipal principal)) {
            // Not a consumer request (or not authenticated)
            filterChain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        String requiredScope = resolveRequiredScope(uri);

        // 1. Scope checking BEFORE quota consumption
        if (requiredScope != null && !principal.hasScope(requiredScope)) {
            log.warn("Access denied on {} for consumer {}: missing scope '{}'",
                uri, principal.consumerId(), requiredScope);
            sendError(response, request, HttpStatus.FORBIDDEN,
                "Access denied: insufficient scope. Required: " + requiredScope);
            return;
        }

        // 2. Consume quota from atomic Redis Token Bucket
        request.setAttribute(ALREADY_CONSUMED_ATTR, Boolean.TRUE);

        try {
            RateLimitDecision decision = tokenBucketRateLimiter.consume(
                principal.consumerId(),
                principal.plan(),
                1
            );

            // Standards-compatible rate-limit response headers
            response.setHeader("RateLimit-Limit", String.valueOf(decision.bucketCapacity()));
            response.setHeader("RateLimit-Remaining", String.valueOf(decision.tokensRemaining()));
            response.setHeader("RateLimit-Reset", String.valueOf(decision.resetAfterSeconds()));

            if (!decision.allowed()) {
                log.warn("Rate limit exceeded for consumer {} on {}: retry in {}s",
                    principal.consumerId(), uri, decision.retryAfterSeconds());
                response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
                sendError(response, request, HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit quota exceeded. Please retry after " + decision.retryAfterSeconds() + " seconds.");
                return;
            }

            filterChain.doFilter(request, response);

        } catch (RateLimitUnavailableException e) {
            log.error("Rate limiting service unavailable for consumer {}: {}", principal.consumerId(), e.getMessage());
            sendError(response, request, HttpStatus.SERVICE_UNAVAILABLE, "Rate limiting service temporarily unavailable.");
        } catch (Exception e) {
            log.error("Unexpected error during rate limiting for consumer {}: {}", principal.consumerId(), e.getMessage());
            sendError(response, request, HttpStatus.SERVICE_UNAVAILABLE, "Rate limiting service temporarily unavailable.");
        }
    }

    private String resolveRequiredScope(String uri) {
        if (uri == null) {
            return "gateway:invoke";
        }
        for (Map.Entry<String, String> entry : ENDPOINT_SCOPES.entrySet()) {
            if (uri.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "gateway:invoke";
    }

    private void sendError(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ErrorResponse errorResponse = ErrorResponse.of(
            status.value(),
            status.getReasonPhrase(),
            message,
            request.getRequestURI()
        );
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
        response.getWriter().flush();
    }
}
