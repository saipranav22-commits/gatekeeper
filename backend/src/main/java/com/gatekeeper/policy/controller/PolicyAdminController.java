package com.gatekeeper.policy.controller;

import com.gatekeeper.policy.dto.ConfigureShadowPolicyRequest;
import com.gatekeeper.policy.dto.CreatePolicyDefinitionRequest;
import com.gatekeeper.policy.dto.CreatePolicyVersionRequest;
import com.gatekeeper.policy.dto.PolicyActivationAuditDto;
import com.gatekeeper.policy.dto.PolicyDefinitionDto;
import com.gatekeeper.policy.dto.PolicyReplayRequest;
import com.gatekeeper.policy.dto.PolicyReplayResponse;
import com.gatekeeper.policy.dto.PolicySimulationRequest;
import com.gatekeeper.policy.dto.PolicySimulationResponse;
import com.gatekeeper.policy.dto.PolicyVersionDto;
import com.gatekeeper.policy.service.PolicyAdministrationService;
import com.gatekeeper.policy.service.PolicyReplayService;
import com.gatekeeper.policy.service.PolicySimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/policies")
@Tag(name = "Policy Engine", description = "Versioned, explainable policy management, simulation, and auditing")
@Profile("!foundation")
public class PolicyAdminController {

    private final PolicyAdministrationService adminService;
    private final PolicySimulationService simulationService;
    private final PolicyReplayService replayService;

    public PolicyAdminController(
            PolicyAdministrationService adminService,
            PolicySimulationService simulationService,
            PolicyReplayService replayService) {
        this.adminService = adminService;
        this.simulationService = simulationService;
        this.replayService = replayService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Create policy definition", description = "Registers a new policy container with a unique policyKey.")
    public ResponseEntity<PolicyDefinitionDto> createDefinition(@Valid @RequestBody CreatePolicyDefinitionRequest request) {
        PolicyDefinitionDto created = adminService.createDefinition(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "List all policy definitions", description = "Returns summary metadata and active versions for all policy definitions.")
    public ResponseEntity<List<PolicyDefinitionDto>> listDefinitions() {
        return ResponseEntity.ok(adminService.listDefinitions());
    }

    @GetMapping("/{policyKey}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get policy definition", description = "Returns policy definition details including active and shadow version numbers.")
    public ResponseEntity<PolicyDefinitionDto> getDefinition(@PathVariable String policyKey) {
        return ResponseEntity.ok(adminService.getDefinition(policyKey));
    }

    @PostMapping("/{policyKey}/versions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Create immutable policy version", description = "Creates a new immutable draft version with strictly validated rules.")
    public ResponseEntity<PolicyVersionDto> createVersion(
            @PathVariable String policyKey,
            @Valid @RequestBody CreatePolicyVersionRequest request,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "system";
        PolicyVersionDto created = adminService.createVersion(policyKey, request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{policyKey}/versions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "List policy versions", description = "Returns version history and status for the specified policy definition.")
    public ResponseEntity<List<PolicyVersionDto>> listVersions(@PathVariable String policyKey) {
        return ResponseEntity.ok(adminService.listVersions(policyKey));
    }

    @GetMapping("/{policyKey}/versions/{versionNumber}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get specific policy version", description = "Returns rule definitions and metadata for an immutable policy version.")
    public ResponseEntity<PolicyVersionDto> getVersion(
            @PathVariable String policyKey,
            @PathVariable int versionNumber) {
        return ResponseEntity.ok(adminService.getVersion(policyKey, versionNumber));
    }

    @PostMapping("/{policyKey}/versions/{versionNumber}/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Atomically activate policy version", description = "Transitions candidate version to ACTIVE, retiring the current active version.")
    public ResponseEntity<PolicyDefinitionDto> activateVersion(
            @PathVariable String policyKey,
            @PathVariable int versionNumber,
            @RequestParam(required = false) String reason,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "system";
        return ResponseEntity.ok(adminService.activateVersion(policyKey, versionNumber, actor, reason));
    }

    @PostMapping("/{policyKey}/rollback")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Rollback active policy", description = "Rolls back to a previous validated immutable version without mutating history.")
    public ResponseEntity<PolicyDefinitionDto> rollback(
            @PathVariable String policyKey,
            @RequestParam(required = false) Integer targetVersion,
            @RequestParam(required = false) String reason,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "system";
        return ResponseEntity.ok(adminService.rollback(policyKey, targetVersion, actor, reason));
    }

    @PostMapping("/{policyKey}/shadow")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Configure shadow policy version", description = "Sets an optional candidate version to evaluate in shadow mode during live traffic.")
    public ResponseEntity<PolicyDefinitionDto> configureShadow(
            @PathVariable String policyKey,
            @RequestBody ConfigureShadowPolicyRequest request,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "system";
        return ResponseEntity.ok(adminService.configureShadowVersion(policyKey, request.shadowVersionNumber(), actor));
    }

    @PostMapping("/{policyKey}/simulate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Simulate policy evaluation", description = "Dry-run evaluation with full explainable decision trace and zero side-effects.")
    public ResponseEntity<PolicySimulationResponse> simulate(
            @PathVariable String policyKey,
            @RequestBody PolicySimulationRequest request) {
        return ResponseEntity.ok(simulationService.simulate(policyKey, request));
    }

    @PostMapping("/{policyKey}/replay")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Historical impact analysis (replay)", description = "Evaluates candidate policy against historical usage events without upstream calls.")
    public ResponseEntity<PolicyReplayResponse> replay(
            @PathVariable String policyKey,
            @Valid @RequestBody PolicyReplayRequest request) {
        return ResponseEntity.ok(replayService.replay(policyKey, request));
    }

    @GetMapping("/{policyKey}/audit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get policy activation audit trail", description = "Returns chronological activation and rollback audit history.")
    public ResponseEntity<List<PolicyActivationAuditDto>> getAuditHistory(@PathVariable String policyKey) {
        return ResponseEntity.ok(adminService.getAuditHistory(policyKey));
    }
}
