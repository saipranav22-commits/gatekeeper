package com.gatekeeper.routing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.common.response.ErrorResponse;
import com.gatekeeper.resilience.engine.RouteResilienceEngine;
import com.gatekeeper.routing.model.GatewayRoute;
import com.gatekeeper.routing.model.RouteMatchResult;
import com.gatekeeper.routing.proxy.HttpProxyEngine;
import com.gatekeeper.routing.service.GatewayRouteRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Entry controller for external API proxy requests (/api/v1/proxy/**).
 * Delegates request execution through the RouteResilienceEngine and HttpProxyEngine.
 */
@RestController
@RequestMapping("/api/v1/proxy")
public class GatewayProxyController {

    private static final Logger log = LoggerFactory.getLogger(GatewayProxyController.class);

    private final GatewayRouteRegistry routeRegistry;
    private final HttpProxyEngine httpProxyEngine;
    private final RouteResilienceEngine resilienceEngine;
    private final ObjectMapper objectMapper;

    @Autowired
    public GatewayProxyController(
        GatewayRouteRegistry routeRegistry,
        HttpProxyEngine httpProxyEngine,
        @Autowired(required = false) RouteResilienceEngine resilienceEngine,
        ObjectMapper objectMapper
    ) {
        this.routeRegistry = routeRegistry;
        this.httpProxyEngine = httpProxyEngine;
        this.resilienceEngine = resilienceEngine;
        this.objectMapper = objectMapper;
    }

    public GatewayProxyController(
        GatewayRouteRegistry routeRegistry,
        HttpProxyEngine httpProxyEngine,
        ObjectMapper objectMapper
    ) {
        this(routeRegistry, httpProxyEngine, null, objectMapper);
    }

    @RequestMapping("/**")
    public void proxyRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        GatewayRoute route = (GatewayRoute) request.getAttribute("GATEKEEPER_MATCHED_ROUTE");
        String targetPath = (String) request.getAttribute("GATEKEEPER_TARGET_PATH");

        if (route == null) {
            Optional<RouteMatchResult> matchResult = routeRegistry.matchRoute(request.getRequestURI(), request.getMethod());
            if (matchResult.isEmpty()) {
                sendError(response, request, HttpStatus.NOT_FOUND, "No matching route found for: " + request.getRequestURI());
                return;
            }
            route = matchResult.get().route();
            targetPath = matchResult.get().targetPath();
        }

        if (resilienceEngine != null) {
            resilienceEngine.execute(request, response, route, targetPath);
        } else {
            httpProxyEngine.forward(request, response, route, targetPath);
        }
    }

    private void sendError(
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
}
