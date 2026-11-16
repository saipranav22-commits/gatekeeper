package com.gatekeeper.common.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record HealthResponse(
    String status,
    String service,
    @JsonProperty("serviceName")
    String serviceName,
    Instant timestamp
) {
    public static HealthResponse of(String service) {
        return new HealthResponse("UP", service, service, Instant.now());
    }
}
