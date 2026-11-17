package com.gatekeeper.routing.proxy;

import com.gatekeeper.routing.model.GatewayRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Dispatches matched gateway requests to upstream services.
 */
public interface HttpProxyEngine {

    /**
     * Forwards an incoming HTTP request to the designated upstream target.
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
}
