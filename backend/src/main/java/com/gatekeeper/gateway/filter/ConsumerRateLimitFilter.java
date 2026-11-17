package com.gatekeeper.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.common.response.ErrorResponse;
import com.gatekeeper.gateway.dto.ConsumerPrincipal;
import com.gatekeeper.policy.engine.PolicyCacheManager;
import com.gatekeeper.policy.engine.PolicyEvaluationEngine;
import com.gatekeeper.policy.model.PolicyAction;
import com.gatekeeper.policy.model.PolicyDecision;
import com.gatekeeper.policy.model.PolicyEvaluationContext;
import com.gatekeeper.ratelimit.dto.RateLimitDecision;
import com.gatekeeper.ratelimit.exception.RateLimitUnavailableException;
import com.gatekeeper.ratelimit.service.TokenBucketRateLimiter;
import com.gatekeeper.routing.model.GatewayRoute;
import com.gatekeeper.routing.model.RouteMatchResult;
import com.gatekeeper.routing.service.GatewayRouteRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Optional;

/**
 * Enforces route matching, scope authorization, and distributed Redis Token Bucket rate limiting
 * on consumer gateway requests.
 *
 * <p>Execution Order:
 * <ol>
 *   <li>Route match, method validation, and scope checking occur BEFORE quota consumption
 *       (invalid routes, disallowed methods, or denied scope requests do not decrement tokens).</li>
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
    private final GatewayRouteRegistry routeRegistry;
    private final PolicyCacheManager policyCacheManager;
    private final PolicyEvaluationEngine policyEvaluationEngine;

    public ConsumerRateLimitFilter(TokenBucketRateLimiter tokenBucketRateLimiter, ObjectMapper objectMapper) {
        this(tokenBucketRateLimiter, objectMapper, null, null, null);
    }

    public ConsumerRateLimitFilter(
            TokenBucketRateLimiter tokenBucketRateLimiter,
            ObjectMapper objectMapper,
            GatewayRouteRegistry routeRegistry) {
        this(tokenBucketRateLimiter, objectMapper, routeRegistry, null, null);
    }

    @Autowired
    public ConsumerRateLimitFilter(
            TokenBucketRateLimiter tokenBucketRateLimiter,
            ObjectMapper objectMapper,
            @Autowired(required = false) GatewayRouteRegistry routeRegistry,
            @Autowired(required = false) PolicyCacheManager policyCacheManager,
            @Autowired(required = false) PolicyEvaluationEngine policyEvaluationEngine) {
        this.tokenBucketRateLimiter = Objects.requireNonNull(tokenBucketRateLimiter, "tokenBucketRateLimiter must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.routeRegistry = routeRegistry;
        this.policyCacheManager = policyCacheManager;
        this.policyEvaluationEngine = policyEvaluationEngine;
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
        String requiredScope;

        // Route matching and method validation for proxy endpoints
        if (uri.startsWith("/api/v1/proxy")) {
            if (routeRegistry != null) {
                RouteMatchResult matchResult;
                try {
                    Optional<RouteMatchResult> matchOpt = routeRegistry.matchRoute(uri, request.getMethod());
                    if (matchOpt.isEmpty()) {
                        log.warn("Proxy route not found for path: {}", uri);
                        sendError(response, request, HttpStatus.NOT_FOUND, "No matching route found for: " + uri);
                        return;
                    }
                    matchResult = matchOpt.get();
                } catch (SecurityException ex) {
                    log.warn("Security violation on path {}: {}", uri, ex.getMessage());
                    sendError(response, request, HttpStatus.BAD_REQUEST, "Invalid request path: " + ex.getMessage());
                    return;
                }

                GatewayRoute route = matchResult.route();
                if (!route.enabled()) {
                    log.warn("Route {} is disabled", route.routeId());
                    sendError(response, request, HttpStatus.SERVICE_UNAVAILABLE, "Route '" + route.routeId() + "' is temporarily disabled");
                    return;
                }

                if (!route.isMethodAllowed(request.getMethod())) {
                    log.warn("Method {} not allowed on route {}", request.getMethod(), route.routeId());
                    sendError(response, request, HttpStatus.METHOD_NOT_ALLOWED, "HTTP method '" + request.getMethod() + "' not allowed for route '" + route.routeId() + "'");
                    return;
                }

                requiredScope = matchResult.requiredScope();
                request.setAttribute("GATEKEEPER_MATCHED_ROUTE", route);
                request.setAttribute("GATEKEEPER_ROUTE_ID", route.routeId());
                request.setAttribute("GATEKEEPER_TARGET_PATH", matchResult.targetPath());
            } else {
                requiredScope = null;
            }
        } else {
            requiredScope = resolveRequiredScope(uri);
        }

        // 1. Scope checking BEFORE quota consumption
        if (requiredScope != null && !principal.hasScope(requiredScope)) {
            log.warn("Access denied on {} for consumer {}: missing scope '{}'",
                uri, principal.consumerId(), requiredScope);
            sendError(response, request, HttpStatus.FORBIDDEN,
                "Access denied: insufficient scope. Required: " + requiredScope);
            return;
        }

        // 2. Fine-grained Policy Evaluation BEFORE quota consumption
        if (policyCacheManager != null && policyEvaluationEngine != null) {
            String routeId = (String) request.getAttribute("GATEKEEPER_ROUTE_ID");
            if (routeId == null && uri.startsWith("/api/v1/gateway/")) {
                routeId = uri.substring("/api/v1/gateway/".length());
            }

            PolicyEvaluationContext policyContext = new PolicyEvaluationContext(
                routeId,
                request.getMethod(),
                request.getRemoteAddr(),
                principal.consumerId(),
                principal.plan() != null ? principal.plan().name() : null,
                principal.scopes(),
                java.time.Instant.now()
            );

            // Check route-specific policy first, then global 'default' policy
            java.util.List<String> policyKeysToCheck = new java.util.ArrayList<>();
            if (routeId != null) {
                policyKeysToCheck.add(routeId);
            }
            policyKeysToCheck.add("default");

            for (String pKey : policyKeysToCheck) {
                java.util.Optional<PolicyCacheManager.ActivePolicySnapshot> snapshotOpt = policyCacheManager.getActivePolicy(pKey);
                if (snapshotOpt.isPresent()) {
                    PolicyCacheManager.ActivePolicySnapshot snapshot = snapshotOpt.get();
                    if (!snapshot.activeRules().isEmpty()) {
                        PolicyDecision decision = policyEvaluationEngine.evaluate(
                            snapshot.policyKey(),
                            snapshot.versionNumber(),
                            snapshot.activeRules(),
                            policyContext
                        );

                        // Execute shadow evaluation safely if configured
                        if (!snapshot.shadowRules().isEmpty()) {
                            try {
                                PolicyDecision shadowDecision = policyEvaluationEngine.evaluate(
                                    snapshot.policyKey(),
                                    snapshot.shadowVersionNumber(),
                                    snapshot.shadowRules(),
                                    policyContext
                                );
                                if (decision.action() != shadowDecision.action()) {
                                    log.info("Shadow policy mismatch for '{}' (v{} vs shadow v{}): authoritative={}, shadow={} (rule={})",
                                        snapshot.policyKey(), snapshot.versionNumber(), snapshot.shadowVersionNumber(),
                                        decision.action(), shadowDecision.action(), shadowDecision.matchedRuleId());
                                }
                            } catch (Exception ex) {
                                log.warn("Shadow policy evaluation failed safely: {}", ex.getMessage());
                            }
                        }

                        if (decision.action() == PolicyAction.DENY) {
                            log.warn("Access denied by policy '{}' (rule '{}') on {}: {}",
                                decision.policyKey(), decision.matchedRuleId(), uri, decision.safeReason());

                            request.setAttribute("GATEKEEPER_POLICY_DECISION", "DENY");
                            request.setAttribute("GATEKEEPER_POLICY_MATCHED_RULE", decision.matchedRuleId());
                            request.setAttribute("GATEKEEPER_POLICY_REASON", decision.safeReason());
                            request.setAttribute("GATEKEEPER_POLICY_KEY", decision.policyKey());
                            request.setAttribute("GATEKEEPER_POLICY_VERSION", decision.policyVersion());

                            sendError(response, request, HttpStatus.FORBIDDEN,
                                "Access denied by gateway policy: " + decision.safeReason());
                            return; // Return immediately WITHOUT consuming quota or calling upstream!
                        } else {
                            request.setAttribute("GATEKEEPER_POLICY_DECISION", "ALLOW_CONTINUE");
                            request.setAttribute("GATEKEEPER_POLICY_MATCHED_RULE", decision.matchedRuleId());
                            request.setAttribute("GATEKEEPER_POLICY_KEY", decision.policyKey());
                            request.setAttribute("GATEKEEPER_POLICY_VERSION", decision.policyVersion());
                        }
                    }
                }
            }
        }

        // 3. Consume quota from atomic Redis Token Bucket
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
