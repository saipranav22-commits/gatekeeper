package com.gatekeeper.chaos.engine;

import com.gatekeeper.chaos.metrics.ReliabilityMetrics;
import com.gatekeeper.chaos.model.ChaosExperiment;
import com.gatekeeper.chaos.model.ChaosScenarioType;
import com.gatekeeper.chaos.model.ExperimentState;
import com.gatekeeper.chaos.scenario.ChaosScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core orchestrator for controlled chaos engineering experiments.
 *
 * <p>Enforces the 8-stage experiment lifecycle:
 * <pre>
 *   PRECHECK
 *   -> BASELINE MEASUREMENT
 *   -> CONTROLLED FAULT INJECTION
 *   -> OBSERVATION
 *   -> FAULT REMOVAL
 *   -> RECOVERY VERIFICATION
 *   -> CLEANUP (guaranteed in finally)
 *   -> REPORT GENERATION
 * </pre>
 */
@Service
@Profile({"local", "test", "dev"})
public class ChaosExperimentEngine {

    private static final Logger log = LoggerFactory.getLogger(ChaosExperimentEngine.class);

    private final ChaosSafetyGuard safetyGuard;
    private final Map<ChaosScenarioType, ChaosScenario> scenarioRegistry = new ConcurrentHashMap<>();
    private final Map<String, ChaosExperiment> experimentHistory = new ConcurrentHashMap<>();

    public ChaosExperimentEngine(ChaosSafetyGuard safetyGuard, List<ChaosScenario> scenarios) {
        this.safetyGuard = Objects.requireNonNull(safetyGuard, "safetyGuard must not be null");
        if (scenarios != null) {
            for (ChaosScenario s : scenarios) {
                scenarioRegistry.put(s.getScenarioType(), s);
            }
        }
    }

    public ChaosExperiment runExperiment(ChaosScenarioType scenarioType, Map<String, Object> parameters) {
        ChaosScenario scenario = scenarioRegistry.get(scenarioType);
        if (scenario == null) {
            throw new IllegalArgumentException("Unsupported chaos scenario type: " + scenarioType);
        }

        ChaosExperiment experiment = new ChaosExperiment(
            scenarioType,
            scenario.getDefaultTarget(),
            scenarioType == ChaosScenarioType.ORDERS_OUTAGE ? com.gatekeeper.chaos.model.FaultType.HTTP_ERROR
                : scenarioType == ChaosScenarioType.SLOW_UPSTREAM ? com.gatekeeper.chaos.model.FaultType.LATENCY_INJECTION
                : scenarioType == ChaosScenarioType.BULKHEAD_SATURATION ? com.gatekeeper.chaos.model.FaultType.CONCURRENCY_BURST
                : scenarioType == ChaosScenarioType.REDIS_FAILURE ? com.gatekeeper.chaos.model.FaultType.DEPENDENCY_UNAVAILABLE
                : scenarioType == ChaosScenarioType.POLICY_SAFETY ? com.gatekeeper.chaos.model.FaultType.DENY_POLICY_ACTIVATION
                : com.gatekeeper.chaos.model.FaultType.HTTP_ERROR,
            parameters
        );

        experimentHistory.put(experiment.getId(), experiment);

        log.info("CHAOS LAB: Starting experiment '{}' for scenario '{}' on target '{}'...",
            experiment.getId(), scenarioType.getDisplayName(), experiment.getTarget().getServiceKey());

        // 1. Acquire Target Lock and Validate Blast-Radius Constraints
        safetyGuard.acquireTargetLock(experiment);

        try {
            // Stage 1: Precheck
            experiment.transitionTo(ExperimentState.VALIDATING);
            log.info("[1/7] Stage: PRECHECK - Validating baseline availability...");
            scenario.precheck(experiment);

            // Stage 2: Baseline Measurement
            log.info("[2/7] Stage: BASELINE - Measuring normal operational baseline...");
            scenario.measureBaseline(experiment);

            // Stage 3: Controlled Fault Injection
            experiment.markStarted();
            log.info("[3/7] Stage: FAULT INJECTION - Injecting controlled failure...");
            scenario.injectFault(experiment);

            // Stage 4: Observation
            log.info("[4/7] Stage: OBSERVATION - Collecting telemetry under failure condition...");
            scenario.observe(experiment);

            // Stage 5: Fault Removal
            experiment.transitionTo(ExperimentState.RECOVERING);
            log.info("[5/7] Stage: FAULT REMOVAL - Restoring normal target configuration...");
            scenario.removeFault(experiment);

            // Stage 6: Recovery Verification
            log.info("[6/7] Stage: RECOVERY VERIFICATION - Verifying return to healthy baseline...");
            scenario.verifyRecovery(experiment);

            experiment.markCompleted(ExperimentState.COMPLETED);
            log.info("[COMPLETED] Chaos experiment '{}' succeeded.", experiment.getId());

        } catch (Exception ex) {
            log.error("[FAILED] Chaos experiment '{}' failed: {}", experiment.getId(), ex.getMessage(), ex);
            experiment.setFailureReason(ex.getMessage());
            experiment.markCompleted(ExperimentState.FAILED);
        } finally {
            // Stage 7: Cleanup (guaranteed to execute even on failure)
            log.info("[7/7] Stage: CLEANUP - Executing mandatory resource cleanup...");
            try {
                scenario.cleanup(experiment);
            } catch (Exception cleanupEx) {
                log.error("Error during scenario cleanup: {}", cleanupEx.getMessage());
                experiment.setCleanupStatus("FAILED: " + cleanupEx.getMessage());
            }

            // Always release target lock
            safetyGuard.releaseTargetLock(experiment);

            // Stage 8: Report Generation & Metrics compilation
            try {
                ReliabilityMetrics metrics = scenario.computeMetrics(experiment);
                experiment.setMetrics(metrics);
                log.info("Report compiled for experiment '{}': TotalReqs={}, Success={}, Fail={}, P95={}ms",
                    experiment.getId(), metrics.getTotalRequests(), metrics.getSuccessfulRequests(),
                    metrics.getFailedRequests(), metrics.getGatewayLatencyP95Ms());
            } catch (Exception metricEx) {
                log.error("Failed to compile metrics for experiment '{}': {}", experiment.getId(), metricEx.getMessage());
            }
        }

        return experiment;
    }

    public ChaosExperiment getExperiment(String id) {
        return experimentHistory.get(id);
    }

    public List<ChaosExperiment> getAllExperiments() {
        return new ArrayList<>(experimentHistory.values());
    }

    public Map<ChaosScenarioType, ChaosScenario> getScenarioRegistry() {
        return Collections.unmodifiableMap(scenarioRegistry);
    }
}
