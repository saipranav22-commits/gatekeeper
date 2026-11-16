package com.gatekeeper.consumer.controller;

import com.gatekeeper.consumer.dto.ChangePlanRequest;
import com.gatekeeper.consumer.dto.ConsumerDto;
import com.gatekeeper.consumer.dto.CreateConsumerRequest;
import com.gatekeeper.consumer.service.ConsumerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/consumers")
@Profile("!foundation")
@Tag(name = "Consumers", description = "Endpoints for managing API consumers, subscription assignments, and lifecycle status")
public class ConsumerController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("createdAt", "name", "email", "status");

    private final ConsumerService consumerService;

    public ConsumerController(ConsumerService consumerService) {
        this.consumerService = consumerService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Create an API consumer", description = "Registers a new API consumer associated with an active subscription plan.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Consumer registered successfully",
            content = @Content(schema = @Schema(implementation = ConsumerDto.class))),
        @ApiResponse(responseCode = "400", description = "Bad Request - validation or format error"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Not Found - associated subscription plan does not exist"),
        @ApiResponse(responseCode = "409", description = "Conflict - email already registered")
    })
    public ResponseEntity<ConsumerDto> createConsumer(@Valid @RequestBody CreateConsumerRequest request) {
        ConsumerDto consumer = consumerService.createConsumer(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{consumerId}")
            .buildAndExpand(consumer.id())
            .toUri();
        return ResponseEntity.created(location).body(consumer);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "List consumers with pagination", description = "Retrieves a paginated list of consumers with deterministic ordering and bounded page sizes.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Paginated consumers retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges")
    })
    public ResponseEntity<Page<ConsumerDto>> listConsumers(
            @Parameter(description = "Zero-based page index", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1 to 100)", example = "20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Property to sort by (createdAt, name, email, status)", example = "createdAt")
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Sort direction (asc, desc)", example = "desc")
            @RequestParam(defaultValue = "desc") String direction) {

        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        String sortProperty = ALLOWED_SORT_PROPERTIES.contains(sortBy) ? sortBy : "createdAt";
        Sort.Direction sortDir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;

        // Deterministic ordering: secondary sort on id ensures stable pagination across concurrent inserts
        Sort sort = Sort.by(sortDir, sortProperty).and(Sort.by(Sort.Direction.ASC, "id"));
        Pageable pageable = PageRequest.of(boundedPage, boundedSize, sort);

        Page<ConsumerDto> consumers = consumerService.listConsumers(pageable);
        return ResponseEntity.ok(consumers);
    }

    @GetMapping("/{consumerId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get consumer by ID", description = "Retrieves active consumer metadata by unique identifier.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Consumer retrieved successfully",
            content = @Content(schema = @Schema(implementation = ConsumerDto.class))),
        @ApiResponse(responseCode = "400", description = "Bad Request - malformed consumer UUID"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Not Found - consumer does not exist or has been deleted")
    })
    public ResponseEntity<ConsumerDto> getConsumer(
            @Parameter(description = "Consumer UUID", required = true)
            @PathVariable UUID consumerId) {
        ConsumerDto consumer = consumerService.getConsumerById(consumerId);
        return ResponseEntity.ok(consumer);
    }

    @PatchMapping("/{consumerId}/plan")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Change consumer subscription plan", description = "Migrates the consumer to a different active subscription plan.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription plan updated successfully",
            content = @Content(schema = @Schema(implementation = ConsumerDto.class))),
        @ApiResponse(responseCode = "400", description = "Bad Request - target plan is inactive or malformed input"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Not Found - consumer or target plan not found")
    })
    public ResponseEntity<ConsumerDto> changePlan(
            @Parameter(description = "Consumer UUID", required = true)
            @PathVariable UUID consumerId,
            @Valid @RequestBody ChangePlanRequest request) {
        ConsumerDto updated = consumerService.changeSubscriptionPlan(consumerId, request.planId());
        return ResponseEntity.ok(updated);
    }

    @PatchMapping("/{consumerId}/suspend")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Suspend consumer", description = "Suspends consumer access. Key creation and invocation are halted.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Consumer suspended successfully",
            content = @Content(schema = @Schema(implementation = ConsumerDto.class))),
        @ApiResponse(responseCode = "400", description = "Bad Request - invalid transition (e.g. deactivated)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Not Found - consumer not found or deleted")
    })
    public ResponseEntity<ConsumerDto> suspendConsumer(
            @Parameter(description = "Consumer UUID", required = true)
            @PathVariable UUID consumerId) {
        ConsumerDto suspended = consumerService.suspendConsumer(consumerId);
        return ResponseEntity.ok(suspended);
    }

    @PatchMapping("/{consumerId}/reactivate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Reactivate consumer", description = "Reactivates a suspended consumer back to active status.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Consumer reactivated successfully",
            content = @Content(schema = @Schema(implementation = ConsumerDto.class))),
        @ApiResponse(responseCode = "400", description = "Bad Request - invalid transition (e.g. deleted or deactivated)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Not Found - consumer not found or deleted")
    })
    public ResponseEntity<ConsumerDto> reactivateConsumer(
            @Parameter(description = "Consumer UUID", required = true)
            @PathVariable UUID consumerId) {
        ConsumerDto reactivated = consumerService.reactivateConsumer(consumerId);
        return ResponseEntity.ok(reactivated);
    }

    @DeleteMapping("/{consumerId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Soft delete consumer", description = "Soft deletes consumer and marks deletedAt while preserving audit history.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Consumer soft deleted successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Not Found - consumer not found or already deleted")
    })
    public ResponseEntity<Void> softDeleteConsumer(
            @Parameter(description = "Consumer UUID", required = true)
            @PathVariable UUID consumerId) {
        consumerService.softDeleteConsumer(consumerId);
        return ResponseEntity.noContent().build();
    }
}
