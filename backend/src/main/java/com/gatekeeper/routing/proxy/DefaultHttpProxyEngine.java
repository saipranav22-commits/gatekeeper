package com.gatekeeper.routing.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.common.response.ErrorResponse;
import com.gatekeeper.gateway.filter.GatewayUsageTrackingFilter;
import com.gatekeeper.routing.model.GatewayRoute;
import com.gatekeeper.routing.validator.SsrfProtectionValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * Standard HTTP proxy engine utilizing Java 21 HttpClient.
 */
@Component
public class DefaultHttpProxyEngine implements HttpProxyEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultHttpProxyEngine.class);

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "host"
    );

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
        "x-api-key",
        "authorization",
        "cookie",
        "set-cookie"
    );

    private final HttpClient httpClient;
    private final SsrfProtectionValidator ssrfProtectionValidator;
    private final ObjectMapper objectMapper;

    @Autowired
    public DefaultHttpProxyEngine(
        SsrfProtectionValidator ssrfProtectionValidator,
        ObjectMapper objectMapper
    ) {
        this.ssrfProtectionValidator = ssrfProtectionValidator;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    // Constructor for testing / custom client injection
    public DefaultHttpProxyEngine(
        HttpClient httpClient,
        SsrfProtectionValidator ssrfProtectionValidator,
        ObjectMapper objectMapper
    ) {
        this.httpClient = httpClient;
        this.ssrfProtectionValidator = ssrfProtectionValidator;
        this.objectMapper = objectMapper;
    }

    @Override
    public UpstreamResponse dispatchAttempt(
        HttpServletRequest request,
        GatewayRoute route,
        String targetPath,
        Duration timeout
    ) throws Exception {

        // 1. Build and validate target URI
        URI targetUri = buildAndValidateTargetUri(request, route, targetPath);

        // 2. Validate payload length if known
        long contentLength = request.getContentLengthLong();
        if (contentLength > route.maxBodySizeBytes()) {
            log.warn("Request body size {} exceeds limit {} on route '{}'",
                contentLength, route.maxBodySizeBytes(), route.routeId());
            throw new PayloadTooLargeException("Request payload exceeds maximum allowed size of " + route.maxBodySizeBytes() + " bytes");
        }

        // 3. Prepare body publisher
        HttpRequest.BodyPublisher bodyPublisher;
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        boolean hasBodyMethod = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);

        if (hasBodyMethod) {
            byte[] bodyBytes = (byte[]) request.getAttribute("GATEKEEPER_CACHED_BODY");
            if (bodyBytes == null) {
                bodyBytes = request.getInputStream().readNBytes((int) route.maxBodySizeBytes() + 1);
                if (bodyBytes.length > route.maxBodySizeBytes()) {
                    log.warn("Streamed request body exceeded limit {} on route '{}'",
                        route.maxBodySizeBytes(), route.routeId());
                    throw new PayloadTooLargeException("Request payload exceeds maximum allowed size of " + route.maxBodySizeBytes() + " bytes");
                }
                request.setAttribute("GATEKEEPER_CACHED_BODY", bodyBytes);
            }
            bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(bodyBytes);
        } else {
            bodyPublisher = HttpRequest.BodyPublishers.noBody();
        }

        // 4. Build HttpRequest with sanitized headers
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(targetUri)
            .method(method, bodyPublisher)
            .timeout(timeout != null ? timeout : Duration.ofMillis(route.timeoutMs()));

        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                String lower = name.toLowerCase(Locale.ROOT);

                if (HOP_BY_HOP_HEADERS.contains(lower)
                    || SENSITIVE_HEADERS.contains(lower)
                    || lower.startsWith("x-admin-")
                    || lower.equals("content-length")) {
                    continue;
                }

                Enumeration<String> values = request.getHeaders(name);
                while (values.hasMoreElements()) {
                    String val = values.nextElement();
                    try {
                        requestBuilder.header(name, val);
                    } catch (IllegalArgumentException iae) {
                        // Restricted by Java HttpClient
                    }
                }
            }
        }

        // Forward correlation tracking
        String correlationId = (String) request.getAttribute(GatewayUsageTrackingFilter.CORRELATION_ID_ATTR);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = request.getHeader(GatewayUsageTrackingFilter.CORRELATION_ID_HEADER);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            requestBuilder.header("X-Request-Id", correlationId);
            requestBuilder.header("X-Correlation-Id", correlationId);
        }

        // Forward client IP and protocol
        String clientIp = extractClientIp(request);
        if (clientIp != null) {
            requestBuilder.header("X-Forwarded-For", clientIp);
        }
        requestBuilder.header("X-Forwarded-Proto", request.getScheme());

        // 5. Dispatch request to upstream service
        long startNanos = System.nanoTime();
        HttpResponse<InputStream> rawResponse = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        byte[] responseBody;
        try (InputStream is = rawResponse.body()) {
            responseBody = (is != null) ? is.readAllBytes() : new byte[0];
        }

        return new UpstreamResponse(
            rawResponse.statusCode(),
            rawResponse.headers(),
            responseBody,
            Math.max(1, latencyMs)
        );
    }

    @Override
    public void writeUpstreamResponse(
        HttpServletResponse response,
        UpstreamResponse upstreamResponse
    ) throws IOException {
        response.setStatus(upstreamResponse.statusCode());

        upstreamResponse.headers().map().forEach((headerName, headerValues) -> {
            String lower = headerName.toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP_HEADERS.contains(lower) && !lower.equals("content-length")) {
                for (String val : headerValues) {
                    response.addHeader(headerName, val);
                }
            }
        });

        if (upstreamResponse.body() != null && upstreamResponse.body().length > 0) {
            try (OutputStream os = response.getOutputStream()) {
                os.write(upstreamResponse.body());
                os.flush();
            }
        }
    }

    @Override
    public void forward(
        HttpServletRequest request,
        HttpServletResponse response,
        GatewayRoute route,
        String targetPath
    ) throws IOException {
        long startNanos = System.nanoTime();
        try {
            UpstreamResponse upstreamResponse = dispatchAttempt(
                request,
                route,
                targetPath,
                Duration.ofMillis(route.timeoutMs())
            );

            request.setAttribute("GATEKEEPER_UPSTREAM_STATUS", upstreamResponse.statusCode());
            request.setAttribute("GATEKEEPER_UPSTREAM_LATENCY_MS", (int) upstreamResponse.latencyMs());
            request.setAttribute("GATEKEEPER_ROUTE_ID", route.routeId());

            writeUpstreamResponse(response, upstreamResponse);

        } catch (PayloadTooLargeException ex) {
            sendError(response, request, HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage());
        } catch (SecurityException ex) {
            log.error("SSRF security violation for route '{}': {}", route.routeId(), ex.getMessage());
            sendError(response, request, HttpStatus.FORBIDDEN, "Access to upstream target forbidden: " + ex.getMessage());
        } catch (HttpTimeoutException ex) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            request.setAttribute("GATEKEEPER_UPSTREAM_LATENCY_MS", (int) Math.max(1, latencyMs));
            request.setAttribute("GATEKEEPER_UPSTREAM_STATUS", 504);
            request.setAttribute("GATEKEEPER_ROUTE_ID", route.routeId());
            sendError(response, request, HttpStatus.GATEWAY_TIMEOUT,
                "Gateway Timeout: upstream service failed to respond within " + route.timeoutMs() + "ms");
        } catch (ConnectException ex) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            request.setAttribute("GATEKEEPER_UPSTREAM_LATENCY_MS", (int) Math.max(1, latencyMs));
            request.setAttribute("GATEKEEPER_UPSTREAM_STATUS", 502);
            request.setAttribute("GATEKEEPER_ROUTE_ID", route.routeId());
            sendError(response, request, HttpStatus.BAD_GATEWAY,
                "Bad Gateway: unable to connect to upstream service at " + route.upstreamBaseUri());
        } catch (IOException ex) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            request.setAttribute("GATEKEEPER_UPSTREAM_LATENCY_MS", (int) Math.max(1, latencyMs));
            request.setAttribute("GATEKEEPER_UPSTREAM_STATUS", 502);
            request.setAttribute("GATEKEEPER_ROUTE_ID", route.routeId());
            sendError(response, request, HttpStatus.BAD_GATEWAY,
                "Bad Gateway: error communicating with upstream service");
        } catch (Exception ex) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            request.setAttribute("GATEKEEPER_UPSTREAM_LATENCY_MS", (int) Math.max(1, latencyMs));
            request.setAttribute("GATEKEEPER_UPSTREAM_STATUS", 502);
            request.setAttribute("GATEKEEPER_ROUTE_ID", route.routeId());
            sendError(response, request, HttpStatus.BAD_GATEWAY, "Gateway error: " + ex.getMessage());
        }
    }

    @Override
    public void sendError(
        HttpServletResponse response,
        HttpServletRequest request,
        HttpStatus status,
        String message
    ) throws IOException {
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

    private URI buildAndValidateTargetUri(HttpServletRequest request, GatewayRoute route, String targetPath) {
        String baseUriStr = route.upstreamBaseUri().toString();
        if (baseUriStr.endsWith("/")) {
            baseUriStr = baseUriStr.substring(0, baseUriStr.length() - 1);
        }

        String fullPath = targetPath.startsWith("/") ? targetPath : "/" + targetPath;
        String fullUriStr = baseUriStr + fullPath;
        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isBlank()) {
            fullUriStr += "?" + queryString;
        }

        URI targetUri = URI.create(fullUriStr);
        ssrfProtectionValidator.validateUpstreamUri(targetUri);
        return targetUri;
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[0].trim();
        }
        return request.getRemoteAddr();
    }

    public static class PayloadTooLargeException extends RuntimeException {
        public PayloadTooLargeException(String message) {
            super(message);
        }
    }
}
