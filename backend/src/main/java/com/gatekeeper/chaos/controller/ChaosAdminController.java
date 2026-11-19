package com.gatekeeper.chaos.controller;

import com.gatekeeper.chaos.engine.ChaosExperimentEngine;
import com.gatekeeper.chaos.model.ChaosExperiment;
import com.gatekeeper.chaos.model.ChaosScenarioType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Controlled REST administration interface for executing chaos engineering experiments.
 *
 * <p>Strict Security Constraints:
 * <ul>
 *   <li>Only enabled in non-production profiles (local, test, dev).</li>
 *   <li>Requires SUPER_ADMIN or ADMIN role authentication (JWT).</li>
 *   <li>Strictly forbidden for consumer API keys.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/chaos")
@Profile({"local", "test", "dev"})
@Tag(name = "Chaos Engineering", description = "Controlled reliability experiments & failure injection lab")
public class ChaosAdminController {

    private static final Logger log = LoggerFactory.getLogger(ChaosAdminController.class);

    private final ChaosExperimentEngine experimentEngine;

    public ChaosAdminController(ChaosExperimentEngine experimentEngine) {
        this.experimentEngine = experimentEngine;
    }

    public record RunExperimentRequest(
        String scenario,
        Map<String, Object> parameters
    ) {}

    @PostMapping("/experiments/run")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @Operation(summary = "Execute a controlled chaos experiment", description = "Executes the 8-stage chaos lifecycle for a specified scenario with strict safety bounds.")
    public ResponseEntity<ChaosExperiment> runExperiment(@RequestBody RunExperimentRequest request) {
        if (request == null || request.scenario() == null) {
            return ResponseEntity.badRequest().build();
        }

        ChaosScenarioType type;
        try {
            type = ChaosScenarioType.valueOf(request.scenario().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }

        log.warn("ADMIN AUDIT: Operator triggered chaos scenario '{}'", type.name());
        ChaosExperiment experiment = experimentEngine.runExperiment(type, request.parameters());
        return ResponseEntity.ok(experiment);
    }

    @GetMapping("/experiments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "List all executed chaos experiments")
    public ResponseEntity<List<ChaosExperiment>> getAllExperiments() {
        return ResponseEntity.ok(experimentEngine.getAllExperiments());
    }

    @GetMapping("/experiments/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get chaos experiment telemetry and report by ID")
    public ResponseEntity<ChaosExperiment> getExperiment(@PathVariable String id) {
        ChaosExperiment exp = experimentEngine.getExperiment(id);
        if (exp == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(exp);
    }
}
