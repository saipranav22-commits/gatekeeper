package com.gatekeeper.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.apikey.dto.ApiKeyValidationResult;
import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.apikey.service.ApiKeyService;
import com.gatekeeper.common.response.ErrorResponse;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.gateway.dto.ConsumerAuthenticationToken;
import com.gatekeeper.gateway.dto.ConsumerPrincipal;
import com.gatekeeper.ratelimit.dto.RateLimitPlan;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;

/**
 * Spring Security filter for authenticating consumer API keys presented via the X-API-Key header.
 */
@Component
@org.springframework.context.annotation.Profile("!foundation")
public class ConsumerApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ConsumerApiKeyAuthenticationFilter.class);
    public static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public ConsumerApiKeyAuthenticationFilter(ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = Objects.requireNonNull(apiKeyService, "apiKeyService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/gateway");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        Enumeration<String> headers = request.getHeaders(API_KEY_HEADER);

        if (headers == null || !headers.hasMoreElements()) {
            log.warn("Missing API key on gateway path: {}", request.getRequestURI());
            sendError(response, request, HttpStatus.UNAUTHORIZED, "Missing API key. Please provide '" + API_KEY_HEADER + "' header.");
            return;
        }

        String rawKey = headers.nextElement();

        if (headers.hasMoreElements()) {
            log.warn("Multiple API key headers detected on path: {}", request.getRequestURI());
            sendError(response, request, HttpStatus.UNAUTHORIZED, "Multiple API key headers are not permitted.");
            return;
        }

        if (rawKey == null || rawKey.trim().isEmpty()) {
            log.warn("Blank API key header on gateway path: {}", request.getRequestURI());
            sendError(response, request, HttpStatus.UNAUTHORIZED, "API key header must not be blank.");
            return;
        }

        String keyToValidate = rawKey.trim();

        // Validate API key against database using constant-time hash verification
        ApiKeyValidationResult result = apiKeyService.validateKey(keyToValidate, null);

        if (!result.isValid()) {
            log.warn("Consumer authentication failed on {}: {}", request.getRequestURI(), result.rejectionReason());
            sendError(response, request, HttpStatus.UNAUTHORIZED, "Invalid, expired or revoked API key.");
            return;
        }

        ApiConsumer consumer = result.consumer();
        ApiKey apiKey = result.apiKey();
        RateLimitPlan plan = result.rateLimitPlan();
        if (plan == null) {
            plan = RateLimitPlan.fromSubscriptionPlan(consumer.getPlan());
        }

        ConsumerPrincipal principal = new ConsumerPrincipal(
            consumer.getId(),
            consumer.getName(),
            apiKey.getId(),
            apiKey.getKeyPrefix(),
            result.scopes(),
            plan
        );

        ConsumerAuthenticationToken authentication = new ConsumerAuthenticationToken(
            principal,
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_API_CONSUMER"))
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute("consumerPrincipal", principal);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
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
