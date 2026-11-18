package com.gatekeeper.chaos.engine;

import com.gatekeeper.chaos.metrics.ReliabilityMetrics;
import com.gatekeeper.chaos.model.AssertionOutcome;
import com.gatekeeper.chaos.model.ChaosExperiment;
import com.gatekeeper.chaos.model.ChaosScenarioType;
import com.gatekeeper.chaos.model.ChaosTarget;
import com.gatekeeper.chaos.model.ExperimentAssertion;
import com.gatekeeper.chaos.model.ExperimentState;
import com.gatekeeper.chaos.scenario.ChaosScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Phase 5D: Chaos Experiment Engine Lifecycle Tests")
class ChaosExperimentEngineTest {

    static class MockSuccessScenario implements ChaosScenario {
        final AtomicBoolean prechecked = new AtomicBoolean(false);
        final AtomicBoolean baselineMeasured = new AtomicBoolean(false);
        final AtomicBoolean faultInjected = new AtomicBoolean(false);
        final AtomicBoolean observed = new AtomicBoolean(false);
        final AtomicBoolean faultRemoved = new AtomicBoolean(false);
        final AtomicBoolean recoveryVerified = new AtomicBoolean(false);
        final AtomicBoolean cleanedUp = new AtomicBoolean(false);

        @Override public ChaosScenarioType getScenarioType() { return ChaosScenarioType.ORDERS_OUTAGE; }
        @Override public ChaosTarget getDefaultTarget() { return ChaosTarget.ORDERS_SERVICE; }
        @Override public void precheck(ChaosExperiment exp) { prechecked.set(true); }
        @Override public void measureBaseline(ChaosExperiment exp) { baselineMeasured.set(true); }
        @Override public void injectFault(ChaosExperiment exp) { faultInjected.set(true); }
        @Override public void observe(ChaosExperiment exp) { observed.set(true); }
        @Override public void removeFault(ChaosExperiment exp) { faultRemoved.set(true); }
        @Override public void verifyRecovery(ChaosExperiment exp) { recoveryVerified.set(true); }
        @Override public void cleanup(ChaosExperiment exp) { cleanedUp.set(true); exp.setCleanupStatus("SUCCESS"); }

        @Override
        public List<ExperimentAssertion> evaluateAssertions(ChaosExperiment exp) {
            return List.of(ExperimentAssertion.pass("MockCheck", "Check description", "Details"));
        }

        @Override
        public ReliabilityMetrics computeMetrics(ChaosExperiment exp) {
            return ReliabilityMetrics.builder()
                .experimentId(exp.getId())
                .scenarioType(getScenarioType())
                .targetService(exp.getTarget().getServiceKey())
                .totalRequests(10)
                .successfulRequests(8)
                .failedRequests(2)
                .assertions(evaluateAssertions(exp))
                .build();
        }
    }

    static class MockFailingScenario extends MockSuccessScenario {
        @Override
        public void observe(ChaosExperiment exp) {
            throw new RuntimeException("Simulated unexpected network crash during observation");
        }
    }

    @Test
    @DisplayName("Lifecycle: Full 8-stage execution succeeds and completes cleanly")
    void executesCompleteLifecycleSuccessfully() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        ChaosSafetyGuard guard = new ChaosSafetyGuard(env);
        MockSuccessScenario scenario = new MockSuccessScenario();
        ChaosExperimentEngine engine = new ChaosExperimentEngine(guard, List.of(scenario));

        ChaosExperiment exp = engine.runExperiment(ChaosScenarioType.ORDERS_OUTAGE, Map.of());

        assertEquals(ExperimentState.COMPLETED, exp.getState());
        assertTrue(scenario.prechecked.get());
        assertTrue(scenario.baselineMeasured.get());
        assertTrue(scenario.faultInjected.get());
        assertTrue(scenario.observed.get());
        assertTrue(scenario.faultRemoved.get());
        assertTrue(scenario.recoveryVerified.get());
        assertTrue(scenario.cleanedUp.get());
        assertEquals("SUCCESS", exp.getCleanupStatus());
        assertNotNull(exp.getMetrics());
        assertEquals(10, exp.getMetrics().getTotalRequests());
    }

    @Test
    @DisplayName("Lifecycle: Mandatory cleanup executes and lock releases even on unexpected failure")
    void guaranteedCleanupOnFailure() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        ChaosSafetyGuard guard = new ChaosSafetyGuard(env);
        MockFailingScenario scenario = new MockFailingScenario();
        ChaosExperimentEngine engine = new ChaosExperimentEngine(guard, List.of(scenario));

        ChaosExperiment exp = engine.runExperiment(ChaosScenarioType.ORDERS_OUTAGE, Map.of());

        assertEquals(ExperimentState.FAILED, exp.getState());
        assertTrue(scenario.cleanedUp.get(), "Cleanup must execute even when scenario throws exception");
        assertTrue(guard.getActiveTargetExperiments().isEmpty(), "Target lock must be released");
        assertNotNull(exp.getFailureReason());
        assertTrue(exp.getFailureReason().contains("Simulated unexpected network crash"));
    }
}
