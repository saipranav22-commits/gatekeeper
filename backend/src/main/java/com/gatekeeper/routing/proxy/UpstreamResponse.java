package com.gatekeeper.routing.proxy;

import java.net.http.HttpHeaders;

/**
 * Immutable carrier for an upstream HTTP dispatch attempt result.
 */
public record UpstreamResponse(
    int statusCode,
    HttpHeaders headers,
    byte[] body,
    long latencyMs
) {
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
