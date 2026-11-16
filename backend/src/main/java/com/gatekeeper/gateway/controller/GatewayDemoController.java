package com.gatekeeper.gateway.controller;

import com.gatekeeper.gateway.dto.ConsumerPrincipal;
import com.gatekeeper.gateway.dto.GatewayDemoResponse;
import com.gatekeeper.gateway.dto.OrdersDemoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Harmless consumer gateway demonstration endpoints.
 * Demonstrates successful API key authentication, scope-based authorization,
 * and distributed Redis rate limiting.
 */
@RestController
@RequestMapping("/api/v1/gateway")
@Tag(name = "Gateway Demo", description = "Consumer-facing demonstration endpoints with rate limiting")
@SecurityRequirement(name = "ApiKeyAuth")
@org.springframework.context.annotation.Profile("!foundation")
public class GatewayDemoController {

    @GetMapping("/demo")
    @Operation(summary = "Gateway Demo Endpoint", description = "Requires valid API key and gateway:invoke scope")
    public ResponseEntity<GatewayDemoResponse> getDemo(
            @AuthenticationPrincipal ConsumerPrincipal principal) {
        return ResponseEntity.ok(GatewayDemoResponse.success(principal));
    }

    @GetMapping("/orders")
    @Operation(summary = "Orders Demo Endpoint", description = "Requires valid API key and read:orders scope")
    public ResponseEntity<OrdersDemoResponse> getOrders(
            @AuthenticationPrincipal ConsumerPrincipal principal) {
        return ResponseEntity.ok(OrdersDemoResponse.success(principal));
    }
}
