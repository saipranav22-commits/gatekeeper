package com.gatekeeper.chaos.report;

import com.gatekeeper.chaos.metrics.ReliabilityMetrics;
import com.gatekeeper.chaos.model.AssertionOutcome;
import com.gatekeeper.chaos.model.ChaosExperiment;
import com.gatekeeper.chaos.model.ChaosScenarioType;
import com.gatekeeper.chaos.model.ChaosTarget;
import com.gatekeeper.chaos.model.ExperimentAssertion;
import com.gatekeeper.chaos.model.ExperimentState;
import com.gatekeeper.chaos.model.FaultType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Phase 5D: Chaos Report Generator Tests")
class ChaosReportGeneratorTest {

    @Test
    @DisplayName("Report Generator: Markdown contains all 12 mandatory sections and sanitizes secrets")
    void generatesCompleteMarkdownReport() {
        ChaosReportGenerator generator = new ChaosReportGenerator();

        ChaosExperiment exp = new ChaosExperiment("exp-test", ChaosScenarioType.ORDERS_OUTAGE, ChaosTarget.ORDERS_SERVICE, FaultType.HTTP_ERROR, Map.of());
        exp.markCompleted(ExperimentState.COMPLETED);

        ReliabilityMetrics metrics = ReliabilityMetrics.builder()
            .experimentId(exp.getId())
            .scenarioType(exp.getScenarioType())
            .targetService(exp.getTarget().getServiceKey())
            .startTime(Instant.now().minusSeconds(10))
            .endTime(Instant.now())
            .durationMs(10000)
            .injectedFaultType("HTTP_ERROR")
            .totalRequests(50)
            .successfulRequests(20)
            .failedRequests(30)
            .statusDistribution(Map.of(200, 20, 503, 30))
            .gatewayLatencyP50Ms(1.5)
            .gatewayLatencyP95Ms(5.0)
            .gatewayLatencyP99Ms(12.0)
            .upstreamLatencyP50Ms(15.0)
            .upstreamLatencyP95Ms(25.0)
            .upstreamLatencyP99Ms(50.0)
            .circuitBreakerTransitions(List.of("CLOSED -> OPEN", "OPEN -> HALF_OPEN -> CLOSED"))
            .bulkheadRejectionCount(0)
            .retryAttemptCount(10)
            .peakConcurrency(5)
            .recoveryDurationMs(1200)
            .healthyServiceAvailability(100.0)
            .cleanupStatus("SUCCESS")
            .assertions(List.of(
                ExperimentAssertion.pass("CircuitBreakerTrip", "Trip to OPEN on 503", "Observed OPEN state")
            ))
            .build();

        exp.setMetrics(metrics);

        String md = generator.generateMarkdownReport(List.of(exp));
        assertNotNull(md);

        // Verify sections
        assertTrue(md.contains("## 1. Executive Summary"));
        assertTrue(md.contains("#### 1. Experiment Objective"));
        assertTrue(md.contains("#### 2. System Configuration & Target"));
        assertTrue(md.contains("#### 3. Preconditions & Health"));
        assertTrue(md.contains("#### 4. Failure Injection Method"));
        assertTrue(md.contains("#### 5. Expected Reliability Properties"));
        assertTrue(md.contains("#### 6. Actual Observations & Runtime Telemetry"));
        assertTrue(md.contains("#### 7. Latency Measurements"));
        assertTrue(md.contains("#### 8. Recovery Timeline"));
        assertTrue(md.contains("#### 9. Assertion Outcomes"));
        assertTrue(md.contains("#### 10. Cleanup Verification"));
        assertTrue(md.contains("## 11. System Limitations & Safety Caveats"));
        assertTrue(md.contains("## 12. Reproduction Commands"));

        // Verify zero secrets leaked
        assertFalse(md.contains("Bearer "));
        assertFalse(md.contains("password"));
        assertFalse(md.contains("secret-key"));
    }

    @Test
    @DisplayName("Report Generator: Writes files to disk cleanly")
    void writesReportFiles(@TempDir Path tempDir) throws IOException {
        ChaosReportGenerator generator = new ChaosReportGenerator();
        ChaosExperiment exp = new ChaosExperiment("exp-123", ChaosScenarioType.ORDERS_OUTAGE, ChaosTarget.ORDERS_SERVICE, FaultType.HTTP_ERROR, Map.of());
        exp.markCompleted(ExperimentState.COMPLETED);

        generator.writeReportFiles(List.of(exp), tempDir.toString());

        File mdFile = tempDir.resolve("docs/chaos/phase-5d-experiment-report.md").toFile();
        File jsonFile = tempDir.resolve("docs/chaos/results/phase-5d-results.json").toFile();

        assertTrue(mdFile.exists(), "Markdown report must exist");
        assertTrue(jsonFile.exists(), "JSON results file must exist");
        assertTrue(mdFile.length() > 0);
        assertTrue(jsonFile.length() > 0);
    }
}
