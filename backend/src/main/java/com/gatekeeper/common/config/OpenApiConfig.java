package com.gatekeeper.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 documentation configuration for Gatekeeper Management REST APIs.
 *
 * <p>Secured with HTTP Bearer JWT authentication requirement across all protected management routes.
 */
@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "Gatekeeper Management REST API",
        version = "v1",
        description = "Secure API key lifecycle management, subscription plan introspection, and rate limiting administration platform.",
        contact = @Contact(name = "Gatekeeper Security Engineering Team")
    ),
    security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "Enter administrator JWT access token (e.g. obtained from /api/v1/auth/login)"
)
public class OpenApiConfig {
}
