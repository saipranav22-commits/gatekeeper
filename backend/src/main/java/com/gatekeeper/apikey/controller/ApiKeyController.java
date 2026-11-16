package com.gatekeeper.apikey.controller;

import com.gatekeeper.apikey.dto.ApiKeyCreationRequest;
import com.gatekeeper.apikey.dto.ApiKeyCreationResult;
import com.gatekeeper.apikey.dto.ApiKeyMetadataDto;
import com.gatekeeper.apikey.dto.CreateApiKeyRequest;
import com.gatekeeper.apikey.dto.RevokeApiKeyRequest;
import com.gatekeeper.apikey.service.ApiKeyService;
import com.gatekeeper.common.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/consumers/{consumerId}/keys")
@Profile("!foundation")
@Tag(name = "API Keys", description = "Endpoints for generating, inspecting, and revoking consumer API keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(
        summary = "Create an API key for consumer",
        description = "Generates a cryptographically secure API key. The plaintext key is returned exactly once in this response and is never stored or retrievable again."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "API key created successfully. Contains the one-time plaintext key.",
            content = @Content(schema = @Schema(implementation = ApiKeyCreationResult.class))),
        @ApiResponse(responseCode = "400", description = "Bad Request - invalid scopes, past expiration, or consumer not active"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Not Found - consumer does not exist or has been deleted")
    })
    public ResponseEntity<ApiKeyCreationResult> createApiKey(
            @Parameter(description = "Consumer UUID", required = true)
            @PathVariable UUID consumerId,
            @Valid @RequestBody ApiKeyCreationRequest request) {

        CreateApiKeyRequest domainRequest = new CreateApiKeyRequest(
            consumerId,
            request.name(),
            request.scopes(),
            request.expiresAt()
        );

        // Transaction is executed and committed inside ApiKeyService before response is delivered
        ApiKeyCreationResult result = apiKeyService.createApiKey(domainRequest);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{keyId}")
            .buildAndExpand(result.metadata().id())
            .toUri();

        // Enforce Cache-Control: no-store to prevent caching of plaintext credential in intermediaries
        return ResponseEntity.created(location)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(result);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "List API keys for consumer", description = "Retrieves sanitized metadata for all non-deleted API keys belonging to the consumer.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "List of API key metadata retrieved successfully",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiKeyMetadataDto.class)))),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Not Found - consumer does not exist or has been deleted")
    })
    public ResponseEntity<List<ApiKeyMetadataDto>> listKeys(
            @Parameter(description = "Consumer UUID", required = true)
            @PathVariable UUID consumerId) {
        List<ApiKeyMetadataDto> keys = apiKeyService.listKeysForConsumer(consumerId);
        return ResponseEntity.ok(keys);
    }

    @GetMapping("/{keyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get API key metadata by ID", description = "Retrieves sanitized metadata for a specific API key belonging to the consumer.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "API key metadata retrieved successfully",
            content = @Content(schema = @Schema(implementation = ApiKeyMetadataDto.class))),
        @ApiResponse(responseCode = "400", description = "Bad Request - malformed UUID"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Not Found - key does not exist or does not belong to specified consumer")
    })
    public ResponseEntity<ApiKeyMetadataDto> getKey(
            @Parameter(description = "Consumer UUID", required = true)
            @PathVariable UUID consumerId,
            @Parameter(description = "API Key UUID", required = true)
            @PathVariable UUID keyId) {

        ApiKeyMetadataDto metadata = apiKeyService.getKeyMetadata(keyId);
        // Cross-consumer boundary guard: key must belong to consumer in path
        if (!consumerId.equals(metadata.consumerId())) {
            throw new ResourceNotFoundException("ApiKey", "id", keyId);
        }

        return ResponseEntity.ok(metadata);
    }

    @PostMapping("/{keyId}/revoke")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Revoke API key", description = "Revokes an API key with idempotent semantics. Stored audit reason is updated.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "API key revoked successfully",
            content = @Content(schema = @Schema(implementation = ApiKeyMetadataDto.class))),
        @ApiResponse(responseCode = "400", description = "Bad Request - malformed UUID or invalid input"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Not Found - key does not exist or does not belong to specified consumer")
    })
    public ResponseEntity<ApiKeyMetadataDto> revokeKey(
            @Parameter(description = "Consumer UUID", required = true)
            @PathVariable UUID consumerId,
            @Parameter(description = "API Key UUID", required = true)
            @PathVariable UUID keyId,
            @Valid @RequestBody(required = false) RevokeApiKeyRequest request) {

        ApiKeyMetadataDto metadata = apiKeyService.getKeyMetadata(keyId);
        // Cross-consumer boundary guard
        if (!consumerId.equals(metadata.consumerId())) {
            throw new ResourceNotFoundException("ApiKey", "id", keyId);
        }

        String reason = request != null && request.reason() != null ? request.reason() : "Revoked by administrator";
        ApiKeyMetadataDto revoked = apiKeyService.revokeKey(keyId, reason);
        return ResponseEntity.ok(revoked);
    }
}
