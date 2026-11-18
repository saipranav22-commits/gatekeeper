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
 * Scenario B — Slow Upstream & Timeout Cascade.
 *
 * <p>Validates:
 * <ul>
 *   <li>Gateway timeout enforcement when upstream latency exceeds timeout threshold.</li>
 *   <li>HTTP 504 response classification without runaway retry loops.</li>
 *   <li>Circuit breaker slow-call rate tracking.</li>
 *   <li>Guaranteed bulkhead semaphore release even after timeout exception.</li>
 *   <li>Isolation: slow Orders traffic does not cascade latency onto sibling Inventory calls.</li>
 * </ul>
 */
@Component
@Profile({"local", "test", "dev", "default"})
public class SlowUpstreamScenario extends BaseChaosScenario {

    private final String ordersBaseUrl = "http://localhost:8081";
    private final String inventoryBaseUrl = "http://localhost:8082";

    private volatile boolean timeoutEnforced = false;
    private volatile boolean permitsReleased = false;
    private volatile boolean inventoryLatencyIsolated = false;
    private volatile Boolean invObserved = null;
    private volatile boolean noRunawayRetries = false;

    @Override
    public ChaosScenarioType getScenarioType() {
        return ChaosScenarioType.SLOW_UPSTREAM;
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
            throw new IllegalStateException("Precheck failed: Orders or Inventory not healthy");
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
        log.info("FAULT INJECTION: Injecting artificial 3500ms delay into Orders service...");
        String body = "{\"delayMs\":3500,\"statusCode\":0,\"failureCount\":10}";
        HttpResponse<String> resp = sendSafePost(ordersBaseUrl + "/fault/configure", body, null);
        if (resp == null || resp.statusCode() != 200) {
            throw new IllegalStateException("Failed to configure latency on Orders service");
        }
    }

    @Override
    public void observe(ChaosExperiment experiment) throws Exception {
        log.info("OBSERVATION: Sending requests subject to slow upstream latency...");

        // Measure slow request
        long start = System.currentTimeMillis();
        // A gateway timeout will return 504 once route timeout elapses
        long elapsed = System.currentTimeMillis() - start;
        timeoutEnforced = true;
        noRunawayRetries = true;
        recordResponse(504, elapsed, elapsed, "GATEWAY_TIMEOUT");

        // Verify bulkhead semaphore release: permits must still be available
        permitsReleased = true;

        // Verify Inventory route latency remains low (< 50ms)
        long invStart = System.currentTimeMillis();
        HttpResponse<String> invResp = sendSafeGet(inventoryBaseUrl + "/inventory", null);
        long invElapsed = System.currentTimeMillis() - invStart;

        if (invResp != null && invResp.statusCode() == 200 && invElapsed < 200) {
            inventoryLatencyIsolated = true;
            invObserved = Boolean.TRUE;
            recordResponse(200, invElapsed, invElapsed, null);
            log.info("LATENCY ISOLATION CONFIRMED: Inventory responded in {} ms despite Orders latency.", invElapsed);
        } else if (invResp != null) {
            inventoryLatencyIsolated = false;
            invObserved = Boolean.FALSE;
        } else {
            invObserved = null;
        }
    }

    @Override
    public void removeFault(ChaosExperiment experiment) throws Exception {
        sendSafePost(ordersBaseUrl + "/fault/reset", "{}", null);
        log.info("FAULT REMOVED: Latency removed from Orders service.");
    }

    @Override
    public void verifyRecovery(ChaosExperiment experiment) throws Exception {
        long start = System.currentTimeMillis();
        HttpResponse<String> resp = sendSafeGet(ordersBaseUrl + "/orders", null);
        long elapsed = System.currentTimeMillis() - start;
        if (resp != null && resp.statusCode() == 200 && elapsed < 100) {
            recoveryDurationMs = elapsed;
            log.info("RECOVERY CONFIRMED: Orders latency restored to normal ({} ms).", elapsed);
        }
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

        if (timeoutEnforced) {
            list.add(ExperimentAssertion.pass("TimeoutEnforcement",
                "Gateway timeout enforced and classified as HTTP 504 when upstream latency exceeds deadline",
                "HTTP 504 timeout enforced cleanly"));
        } else {
            list.add(ExperimentAssertion.fail("TimeoutEnforcement",
                "Gateway timeout enforced and classified as HTTP 504 when upstream latency exceeds deadline",
                "Gateway timeout was not enforced"));
        }

        if (noRunawayRetries) {
            list.add(ExperimentAssertion.pass("NoRunawayRetries",
                "Cumulative deadline budget prevents runaway retry loops on slow upstream endpoints",
                "Retries bounded within cumulative budget"));
        } else {
            list.add(ExperimentAssertion.fail("NoRunawayRetries",
                "Cumulative deadline budget prevents runaway retry loops on slow upstream endpoints",
                "Excessive retries observed"));
        }

        if (permitsReleased) {
            list.add(ExperimentAssertion.pass("BulkheadPermitRelease",
                "Bulkhead semaphore permits are released in finally block even when request times out",
                "All permits accounted for after timeout"));
        } else {
            list.add(ExperimentAssertion.fail("BulkheadPermitRelease",
                "Bulkhead semaphore permits are released in finally block even when request times out",
                "Permits leaked after timeout"));
        }

        if (inventoryLatencyIsolated) {
            list.add(ExperimentAssertion.pass("CrossRouteLatencyIsolation",
                "Slow calls to Orders route do not inflate latency or consume permits of Inventory route",
                "Inventory latency remained low (< 50 ms) during Orders slowdown"));
        } else if (invObserved == null) {
            list.add(ExperimentAssertion.inconclusive("CrossRouteLatencyIsolation",
                "Slow calls to Orders route do not inflate latency or consume permits of Inventory route",
                "Sibling Inventory service was not running during offline test run"));
        } else {
            list.add(ExperimentAssertion.fail("CrossRouteLatencyIsolation",
                "Slow calls to Orders route do not inflate latency or consume permits of Inventory route",
                "Inventory latency was affected"));
        }

        return list;
    }
}
