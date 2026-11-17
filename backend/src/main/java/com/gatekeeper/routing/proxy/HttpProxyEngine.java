package com.gatekeeper.routing.proxy;

import com.gatekeeper.routing.model.GatewayRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Duration;

/**
 * Dispatches matched gateway requests to upstream services.
 */
public interface HttpProxyEngine {

    /**
     * Forwards an incoming HTTP request directly to the designated upstream target.
     *
     * @param request the incoming HTTP request
     * @param response the HTTP response to write upstream result to
     * @param route the matched gateway route configuration
     * @param targetPath the relative path to invoke on the upstream service
     * @throws IOException on I/O error
     */
    void forward(
        HttpServletRequest request,
        HttpServletResponse response,
        GatewayRoute route,
        String targetPath
    ) throws IOException;

    /**
     * Dispatches a single HTTP attempt to the upstream service with a bounded timeout.
     *
     * @param request the incoming HTTP request
     * @param route the matched gateway route configuration
     * @param targetPath the relative path to invoke on the upstream service
     * @param timeout timeout budget for this specific attempt
     * @return UpstreamResponse containing status code, headers, body, and latency
     * @throws Exception on connection failure, timeout, or I/O error
     */
    UpstreamResponse dispatchAttempt(
        HttpServletRequest request,
        GatewayRoute route,
        String targetPath,
        Duration timeout
    ) throws Exception;

    /**
     * Writes an upstream response to the client response stream.
     */
    void writeUpstreamResponse(
        HttpServletResponse response,
        UpstreamResponse upstreamResponse
    ) throws IOException;

    /**
     * Writes a standardized JSON error response to the client.
     */
    void sendError(
        HttpServletResponse response,
        HttpServletRequest request,
        HttpStatus status,
        String message
    ) throws IOException;
}
