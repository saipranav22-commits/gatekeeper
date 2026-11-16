package com.gatekeeper.plan.controller;

import com.gatekeeper.plan.dto.SubscriptionPlanDto;
import com.gatekeeper.plan.service.SubscriptionPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/plans")
@Profile("!foundation")
@Tag(name = "Subscription Plans", description = "Endpoints for inspecting available subscription tiers and rate limit policies")
public class PlanController {

    private final SubscriptionPlanService planService;

    public PlanController(SubscriptionPlanService planService) {
        this.planService = planService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "List available subscription plans", description = "Retrieves all currently active subscription plans and their rate-limiting token bucket capacities.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "List of subscription plans retrieved successfully",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = SubscriptionPlanDto.class)))),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges")
    })
    public ResponseEntity<List<SubscriptionPlanDto>> listPlans() {
        List<SubscriptionPlanDto> plans = planService.listAvailablePlans();
        return ResponseEntity.ok(plans);
    }

    @GetMapping("/{planId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get subscription plan by ID", description = "Retrieves the subscription plan details for a specific UUID.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription plan details retrieved successfully",
            content = @Content(schema = @Schema(implementation = SubscriptionPlanDto.class))),
        @ApiResponse(responseCode = "400", description = "Bad Request - malformed plan UUID"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Plan not found with specified identifier")
    })
    public ResponseEntity<SubscriptionPlanDto> getPlanById(
            @Parameter(description = "Subscription plan UUID", required = true)
            @PathVariable UUID planId) {
        SubscriptionPlanDto plan = planService.getPlanById(planId);
        return ResponseEntity.ok(plan);
    }
}
