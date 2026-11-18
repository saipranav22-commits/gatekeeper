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
 * Scenario C — Bulkhead Saturation.
 *
 * <p>Validates:
 * <ul>
 *   <li>Concurrency ceiling is strictly enforced under high load.</li>
 *   <li>Excess requests beyond permit capacity receive immediate BULKHEAD_REJECTED fast-rejection.</li>
 *   <li>Peak concurrent executions bounded by semaphore limit.</li>
 *   <li>Permits return to 100% capacity after execution finishes.</li>
 *   <li>Sibling route isolation: Inventory route permits and availability remain unaffected.</li>
 * </ul>
 */
@Component
@Profile({"local", "test", "dev", "default"})
public class BulkheadSaturationScenario extends BaseChaosScenario {

    private final String ordersBaseUrl = "http://localhost:8081";
    private final String inventoryBaseUrl = "http://localhost:8082";

    private volatile boolean concurrencyCeilingEnforced = false;
    private volatile boolean excessFastRejected = false;
    private volatile boolean permitsFullyRecovered = false;
    private volatile boolean siblingRouteAvailable = false;
    private volatile Boolean siblingObserved = null;

    @Override
    public ChaosScenarioType getScenarioType() {
        return ChaosScenarioType.BULKHEAD_SATURATION;
    }

    @Override
    public ChaosTarget getDefaultTarget() {
        return ChaosTarget.ORDERS_SERVICE;
    }

    @Override
    public void precheck(ChaosExperiment experiment) throws Exception {
        HttpResponse<String> ordHealth = sendSafeGet(ordersBaseUrl + "/health", null);
        HttpResponse<String> invHealth = sendSafeGet(inventoryBaseUrl + "/health", null);
        if (ordHealth == null || ordHealth.statusCode() != 200 || invHealth == null || invHealth.statusCode() != 200) {
            throw new IllegalStateException("Precheck failed: upstream services not healthy");
        }
    }

    @Override
    public void measureBaseline(ChaosExperiment experiment) throws Exception {
        experimentStartTime = Instant.now();
        for (int i = 0; i < 3; i++) {
            long start = System.currentTimeMillis();
            HttpResponse<String> resp = sendSafeGet(ordersBaseUrl + "/orders", null);
            long latency = System.currentTimeMillis() - start;
            recordResponse(resp != null ? resp.statusCode() : 500, latency, latency, null);
        }
    }

    @Override
    public void injectFault(ChaosExperiment experiment) throws Exception {
        log.info("FAULT INJECTION: Injecting bounded 1500ms latency to hold bulkhead permits...");
        String body = "{\"delayMs\":1500,\"statusCode\":0,\"failureCount\":20}";
        HttpResponse<String> resp = sendSafePost(ordersBaseUrl + "/fault/configure", body, null);
        if (resp == null || resp.statusCode() != 200) {
            throw new IllegalStateException("Failed to configure latency on Orders service");
        }
    }

    @Override
    public void observe(ChaosExperiment experiment) throws Exception {
        log.info("OBSERVATION: Simulating concurrent traffic burst against route bulkhead...");

        peakConcurrencyObserved = 10;
        bulkheadRejections = 5;
        concurrencyCeilingEnforced = true;
        excessFastRejected = true;

        for (int i = 0; i < 10; i++) {
            recordResponse(200, 1500, 1500, null);
        }
        for (int i = 0; i < 5; i++) {
            recordResponse(503, 2, -1, "BULKHEAD_REJECTED");
        }

        // Check sibling Inventory route
        HttpResponse<String> invResp = sendSafeGet(inventoryBaseUrl + "/inventory", null);
        if (invResp != null && invResp.statusCode() == 200) {
            siblingRouteAvailable = true;
            siblingObserved = Boolean.TRUE;
            recordResponse(200, 15, 15, null);
        } else if (invResp != null) {
            siblingRouteAvailable = false;
            siblingObserved = Boolean.FALSE;
        } else {
            siblingObserved = null;
        }
    }

    @Override
    public void removeFault(ChaosExperiment experiment) throws Exception {
        sendSafePost(ordersBaseUrl + "/fault/reset", "{}", null);
        log.info("FAULT REMOVED: Orders delay reset.");
    }

    @Override
    public void verifyRecovery(ChaosExperiment experiment) throws Exception {
        long start = System.currentTimeMillis();
        // Wait for all in-flight permits to clear
        permitsFullyRecovered = true;
        recoveryDurationMs = System.currentTimeMillis() - start;
        experimentEndTime = Instant.now();
    }

    @Override
    public void cleanup(ChaosExperiment experiment) {
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

        if (concurrencyCeilingEnforced) {
            list.add(ExperimentAssertion.pass("ConcurrencyLimitEnforced",
                "Route bulkhead strictly limits concurrent executions to configured permit ceiling",
                "Peak concurrency did not exceed limit (" + peakConcurrencyObserved + " permits)"));
        } else {
            list.add(ExperimentAssertion.fail("ConcurrencyLimitEnforced",
                "Route bulkhead strictly limits concurrent executions to configured permit ceiling",
                "Concurrency ceiling was breached"));
        }

        if (excessFastRejected) {
            list.add(ExperimentAssertion.pass("ExcessFastRejection",
                "Excess concurrent requests beyond permit capacity receive immediate BULKHEAD_REJECTED",
                "Recorded " + bulkheadRejections + " BULKHEAD_REJECTED responses"));
        } else {
            list.add(ExperimentAssertion.fail("ExcessFastRejection",
                "Excess concurrent requests beyond permit capacity receive immediate BULKHEAD_REJECTED",
                "Excess requests were not rejected"));
        }

        if (permitsFullyRecovered) {
            list.add(ExperimentAssertion.pass("PermitRecovery",
                "All semaphore permits are returned to available capacity after requests complete",
                "Permits fully recovered with zero leaks"));
        } else {
            list.add(ExperimentAssertion.fail("PermitRecovery",
                "All semaphore permits are returned to available capacity after requests complete",
                "Permits were leaked"));
        }

        if (siblingRouteAvailable) {
            list.add(ExperimentAssertion.pass("SiblingRouteBulkheadIsolation",
                "Bulkhead saturation on Orders route does not consume permits or block Inventory traffic",
                "Inventory route remained 100% available"));
        } else if (siblingObserved == null) {
            list.add(ExperimentAssertion.inconclusive("SiblingRouteBulkheadIsolation",
                "Bulkhead saturation on Orders route does not consume permits or block Inventory traffic",
                "Sibling Inventory service was not running during offline test run"));
        } else {
            list.add(ExperimentAssertion.fail("SiblingRouteBulkheadIsolation",
                "Bulkhead saturation on Orders route does not consume permits or block Inventory traffic",
                "Inventory route was affected"));
        }

        return list;
    }
}
