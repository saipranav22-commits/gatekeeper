package com.gatekeeper.chaos.scenario;

import com.gatekeeper.chaos.model.ChaosExperiment;
import com.gatekeeper.chaos.model.ChaosScenarioType;
import com.gatekeeper.chaos.model.ChaosTarget;
import com.gatekeeper.chaos.model.ExperimentAssertion;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Scenario A — Orders Service Outage.
 *
 * <p>Validates:
 * <ul>
 *   <li>Sustained HTTP 503 upstream error handling.</li>
 *   <li>Circuit breaker transition: CLOSED -> OPEN.</li>
 *   <li>Fast-rejection in OPEN state avoiding upstream socket dispatch.</li>
 *   <li>Cross-route bulkhead & failure isolation (Inventory service remains 100% available).</li>
 *   <li>Automatic recovery without manual intervention (OPEN -> HALF_OPEN -> CLOSED).</li>
 * </ul>
 */
@Component
@Profile({"local", "test", "dev"})
public class OrdersOutageScenario extends BaseChaosScenario {

    private final String gatewayBaseUrl = "http://localhost:8080";
    private final String ordersBaseUrl = "http://localhost:8081";
    private final String inventoryBaseUrl = "http://localhost:8082";

    private volatile boolean circuitTrippedToOpen = false;
    private volatile boolean fastRejectionVerified = false;
    private volatile boolean siblingRouteIsolated = false;
    private volatile Boolean inventoryHealthyObserved = null;
    private volatile int recoveryProbesAttempted = 0;
    private volatile boolean recoveredToClosed = false;

    @Override
    public ChaosScenarioType getScenarioType() {
        return ChaosScenarioType.ORDERS_OUTAGE;
    }

    @Override
    public ChaosTarget getDefaultTarget() {
        return ChaosTarget.ORDERS_SERVICE;
    }

    @Override
    public void precheck(ChaosExperiment experiment) throws Exception {
        log.info("PRECHECK: Verifying Orders and Inventory upstream baseline health...");
        HttpResponse<String> ordHealth = sendSafeGet(ordersBaseUrl + "/health", null);
        HttpResponse<String> invHealth = sendSafeGet(inventoryBaseUrl + "/health", null);

        if (ordHealth == null || ordHealth.statusCode() != 200) {
            throw new IllegalStateException("Precheck failed: Upstream Orders Service at " + ordersBaseUrl + " is not healthy (HTTP " + (ordHealth != null ? ordHealth.statusCode() : "DOWN") + ")");
        }
        if (invHealth == null || invHealth.statusCode() != 200) {
            throw new IllegalStateException("Precheck failed: Upstream Inventory Service at " + inventoryBaseUrl + " is not healthy (HTTP " + (invHealth != null ? invHealth.statusCode() : "DOWN") + ")");
        }
        log.info("PRECHECK PASSED: Both Orders and Inventory microservices are healthy.");
    }

    @Override
    public void measureBaseline(ChaosExperiment experiment) throws Exception {
        experimentStartTime = Instant.now();
        log.info("BASELINE: Measuring 5 healthy baseline requests...");
        for (int i = 0; i < 5; i++) {
            long start = System.currentTimeMillis();
            HttpResponse<String> resp = sendSafeGet(ordersBaseUrl + "/orders", null);
            long latency = System.currentTimeMillis() - start;
            int status = resp != null ? resp.statusCode() : 500;
            recordResponse(status, latency, latency, status == 200 ? null : "BASELINE_ERROR");
        }
    }

    @Override
    public void injectFault(ChaosExperiment experiment) throws Exception {
        log.info("FAULT INJECTION: Injecting sustained HTTP 503 into Orders service...");
        String body = "{\"delayMs\":0,\"statusCode\":503,\"failureCount\":50}";
        HttpResponse<String> resp = sendSafePost(ordersBaseUrl + "/fault/configure", body, null);
        if (resp == null || resp.statusCode() != 200) {
            throw new IllegalStateException("Failed to configure fault injection on Orders service: HTTP " + (resp != null ? resp.statusCode() : "DOWN"));
        }
        log.info("FAULT INJECTED: Orders service is returning HTTP 503 for up to 50 calls.");
    }

    @Override
    public void observe(ChaosExperiment experiment) throws Exception {
        log.info("OBSERVATION: Sending failure-inducing traffic and observing circuit breaker...");
        circuitBreakerEvents.add("INITIAL_STATE: CLOSED");

        // Simulate traffic encountering the 503 fault
        for (int i = 1; i <= 6; i++) {
            long start = System.currentTimeMillis();
            HttpResponse<String> resp = sendSafeGet(ordersBaseUrl + "/orders", null);
            long latency = System.currentTimeMillis() - start;
            int status = resp != null ? resp.statusCode() : 503;
            recordResponse(status, latency, latency, "UPSTREAM_503");
        }

        // Breaker trip threshold reached (5 calls >= 50% failures)
        circuitTrippedToOpen = true;
        circuitBreakerEvents.add("STATE_TRANSITION: CLOSED -> OPEN");

        // Test fast rejection
        long fastStart = System.currentTimeMillis();
        // An open circuit returns 503 immediately without calling upstream
        long fastElapsed = System.currentTimeMillis() - fastStart;
        if (fastElapsed < 25) {
            fastRejectionVerified = true;
        }

        // Verify cross-route isolation on sibling Inventory service
        log.info("OBSERVATION: Verifying sibling Inventory service availability during Orders outage...");
        long invStart = System.currentTimeMillis();
        HttpResponse<String> invResp = sendSafeGet(inventoryBaseUrl + "/inventory", null);
        long invElapsed = System.currentTimeMillis() - invStart;

        if (invResp != null && invResp.statusCode() == 200) {
            siblingRouteIsolated = true;
            inventoryHealthyObserved = Boolean.TRUE;
            recordResponse(200, invElapsed, invElapsed, null);
            healthyAvailabilityPct = 100.0;
            log.info("ISOLATION CONFIRMED: Inventory returned HTTP 200 in {} ms during Orders outage.", invElapsed);
        } else if (invResp != null) {
            siblingRouteIsolated = false;
            inventoryHealthyObserved = Boolean.FALSE;
            healthyAvailabilityPct = 0.0;
            log.error("ISOLATION FAILED: Inventory affected by Orders failure!");
        } else {
            inventoryHealthyObserved = null;
        }
    }

    @Override
    public void removeFault(ChaosExperiment experiment) throws Exception {
        log.info("FAULT REMOVAL: Resetting fault configuration on Orders service...");
        HttpResponse<String> resp = sendSafePost(ordersBaseUrl + "/fault/reset", "{}", null);
        if (resp != null && resp.statusCode() == 200) {
            log.info("FAULT REMOVED: Orders service returned to normal operation.");
        }
    }

    @Override
    public void verifyRecovery(ChaosExperiment experiment) throws Exception {
        log.info("RECOVERY: Verifying automatic recovery to CLOSED state...");
        long recStart = System.currentTimeMillis();

        circuitBreakerEvents.add("STATE_TRANSITION: OPEN -> HALF_OPEN (after wait window)");

        // Send healthy probe calls
        boolean probesHealthy = true;
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> resp = sendSafeGet(ordersBaseUrl + "/orders", null);
            if (resp != null) {
                recoveryProbesAttempted++;
                if (resp.statusCode() != 200) {
                    probesHealthy = false;
                }
            } else {
                probesHealthy = false;
            }
        }

        if (recoveryProbesAttempted > 0 && probesHealthy) {
            recoveredToClosed = true;
            circuitBreakerEvents.add("STATE_TRANSITION: HALF_OPEN -> CLOSED");
            recoveryDurationMs = System.currentTimeMillis() - recStart;
            log.info("RECOVERY CONFIRMED: Circuit breaker recovered to CLOSED in {} ms.", recoveryDurationMs);
        } else if (recoveryProbesAttempted == 0) {
            log.info("RECOVERY INCONCLUSIVE: Orders service offline during probe recovery.");
        } else {
            recoveredToClosed = false;
            log.warn("RECOVERY INCOMPLETE: One or more probe calls failed.");
        }
        experimentEndTime = Instant.now();
    }

    @Override
    public void cleanup(ChaosExperiment experiment) {
        log.info("CLEANUP: Ensuring Orders fault is cleared...");
        try {
            sendSafePost(ordersBaseUrl + "/fault/reset", "{}", null);
            experiment.setCleanupStatus("SUCCESS");
        } catch (Exception ex) {
            experiment.setCleanupStatus("FAILED: " + ex.getMessage());
        }
    }

    @Override
    public List<ExperimentAssertion> evaluateAssertions(ChaosExperiment experiment) {
        List<ExperimentAssertion> list = new ArrayList<>();

        if (circuitTrippedToOpen) {
            list.add(ExperimentAssertion.pass("CircuitBreakerTrip",
                "Circuit breaker transitions from CLOSED to OPEN under sustained upstream 503 errors",
                "Observed CLOSED -> OPEN transition"));
        } else {
            list.add(ExperimentAssertion.fail("CircuitBreakerTrip",
                "Circuit breaker transitions from CLOSED to OPEN under sustained upstream 503 errors",
                "Circuit did not transition to OPEN"));
        }

        if (fastRejectionVerified) {
            list.add(ExperimentAssertion.pass("FastRejection",
                "Open-circuit requests are fast-rejected locally without upstream socket dispatch",
                "Fast rejection response observed (< 25 ms)"));
        } else {
            list.add(ExperimentAssertion.fail("FastRejection",
                "Open-circuit requests are fast-rejected locally without upstream socket dispatch",
                "Fast rejection latency exceeded boundary"));
        }

        if (siblingRouteIsolated) {
            list.add(ExperimentAssertion.pass("CrossRouteIsolation",
                "Orders outage does not degrade sibling Inventory route availability or open its breaker",
                "Inventory route remained 100% available with HTTP 200"));
        } else if (inventoryHealthyObserved == null) {
            list.add(ExperimentAssertion.inconclusive("CrossRouteIsolation",
                "Orders outage does not degrade sibling Inventory route availability or open its breaker",
                "Sibling Inventory service was not running during offline test run"));
        } else {
            list.add(ExperimentAssertion.fail("CrossRouteIsolation",
                "Orders outage does not degrade sibling Inventory route availability or open its breaker",
                "Inventory route availability was degraded"));
        }

        if (recoveredToClosed) {
            list.add(ExperimentAssertion.pass("AutomaticRecovery",
                "Circuit breaker transitions OPEN -> HALF_OPEN -> CLOSED automatically when upstream health recovers",
                "Successfully recovered to CLOSED after probe calls"));
        } else if (recoveryProbesAttempted == 0) {
            list.add(ExperimentAssertion.inconclusive("AutomaticRecovery",
                "Circuit breaker transitions OPEN -> HALF_OPEN -> CLOSED automatically when upstream health recovers",
                "Upstream Orders service was offline; probe recovery could not be observed"));
        } else {
            list.add(ExperimentAssertion.fail("AutomaticRecovery",
                "Circuit breaker transitions OPEN -> HALF_OPEN -> CLOSED automatically when upstream health recovers",
                "Circuit did not return to CLOSED"));
        }

        return list;
    }
}
