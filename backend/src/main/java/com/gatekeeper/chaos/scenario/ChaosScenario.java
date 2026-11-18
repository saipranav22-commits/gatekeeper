package com.gatekeeper.chaos.scenario;

import com.gatekeeper.chaos.metrics.ReliabilityMetrics;
import com.gatekeeper.chaos.model.ChaosExperiment;
import com.gatekeeper.chaos.model.ChaosScenarioType;
import com.gatekeeper.chaos.model.ChaosTarget;
import com.gatekeeper.chaos.model.ExperimentAssertion;

import java.util.List;

/**
 * Standard lifecycle contract for all Gatekeeper chaos engineering scenarios.
 *
 * <p>Lifecycle:
 * PRECHECK -> BASELINE MEASUREMENT -> CONTROLLED FAULT INJECTION -> OBSERVATION
 * -> FAULT REMOVAL -> RECOVERY VERIFICATION -> CLEANUP -> REPORT GENERATION.
 */
public interface ChaosScenario {

    ChaosScenarioType getScenarioType();

    ChaosTarget getDefaultTarget();

    /**
     * Stage 1: Precheck baseline availability of dependencies and routes.
     */
    void precheck(ChaosExperiment experiment) throws Exception;

    /**
     * Stage 2: Measure normal traffic latency and status baseline.
     */
    void measureBaseline(ChaosExperiment experiment) throws Exception;

    /**
     * Stage 3: Inject controlled fault into target.
     */
    void injectFault(ChaosExperiment experiment) throws Exception;

    /**
     * Stage 4: Send traffic under failure condition and collect runtime observations.
     */
    void observe(ChaosExperiment experiment) throws Exception;

    /**
     * Stage 5: Remove fault cleanly from target.
     */
    void removeFault(ChaosExperiment experiment) throws Exception;

    /**
     * Stage 6: Verify automatic or triggered recovery back to baseline.
     */
    void verifyRecovery(ChaosExperiment experiment) throws Exception;

    /**
     * Stage 7: Clean up all resources, reset fault state, restore routes/policies.
     * Guaranteed to execute in finally block.
     */
    void cleanup(ChaosExperiment experiment);

    /**
     * Evaluates assertions against recorded telemetry.
     */
    List<ExperimentAssertion> evaluateAssertions(ChaosExperiment experiment);

    /**
     * Compiles final structured reliability metrics.
     */
    ReliabilityMetrics computeMetrics(ChaosExperiment experiment);
}
