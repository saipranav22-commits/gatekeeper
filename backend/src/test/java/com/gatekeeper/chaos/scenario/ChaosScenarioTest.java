package com.gatekeeper.chaos.scenario;

import com.gatekeeper.chaos.model.AssertionOutcome;
import com.gatekeeper.chaos.model.ChaosExperiment;
import com.gatekeeper.chaos.model.ChaosScenarioType;
import com.gatekeeper.chaos.model.ChaosTarget;
import com.gatekeeper.chaos.model.ExperimentAssertion;
import com.gatekeeper.chaos.model.FaultType;
import com.gatekeeper.ratelimit.dto.RateLimitDecision;
import com.gatekeeper.ratelimit.dto.RateLimitPlan;
import com.gatekeeper.ratelimit.exception.RateLimitUnavailableException;
import com.gatekeeper.ratelimit.service.TokenBucketRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Phase 5D: Chaos Scenario Unit & Assertion Tests")
class ChaosScenarioTest {

    @Test
    @DisplayName("Scenario D: Redis outage fails closed without allowing unverified requests")
    void redisFailureFailsClosed() throws Exception {
        TokenBucketRateLimiter failingLimiter = new TokenBucketRateLimiter() {
            @Override
            public RateLimitDecision consume(UUID consumerId, RateLimitPlan plan, int tokens) {
                throw new RateLimitUnavailableException("Simulated Redis outage");
            }
            @Override
            public void resetBucket(UUID consumerId) {}
        };

        RedisFailureScenario scenario = new RedisFailureScenario(failingLimiter);
        ChaosExperiment exp = new ChaosExperiment(ChaosScenarioType.REDIS_FAILURE, ChaosTarget.REDIS_DEPENDENCY, FaultType.DEPENDENCY_UNAVAILABLE);

        // Precheck passes
        scenario.precheck(exp);

        // Fault injection & observation
        scenario.injectFault(exp);
        scenario.observe(exp);

        List<ExperimentAssertion> assertions = scenario.evaluateAssertions(exp);
        assertNotNull(assertions);
        assertFalse(assertions.isEmpty());

        boolean hasFailClosed = assertions.stream().anyMatch(a -> a.name().equals("RedisFailClosed") && a.outcome() == AssertionOutcome.PASS);
        assertTrue(hasFailClosed, "Must assert that Redis outage fails closed");
    }

    @Test
    @DisplayName("Scenario F: Retry Storm preserves quota and tracks amplification ratio")
    void retryStormTracksAmplification() throws Exception {
        RetryStormScenario scenario = new RetryStormScenario();
        ChaosExperiment exp = new ChaosExperiment(ChaosScenarioType.RETRY_STORM, ChaosTarget.ORDERS_SERVICE, FaultType.HTTP_ERROR);

        scenario.observe(exp);

        List<ExperimentAssertion> assertions = scenario.evaluateAssertions(exp);
        assertTrue(assertions.stream().anyMatch(a -> a.name().equals("BoundedIdempotentRetries") && a.outcome() == AssertionOutcome.PASS));
        assertTrue(assertions.stream().anyMatch(a -> a.name().equals("NonIdempotentMethodSafety") && a.outcome() == AssertionOutcome.PASS));
        assertTrue(assertions.stream().anyMatch(a -> a.name().equals("SingleQuotaDeductionPerRequest") && a.outcome() == AssertionOutcome.PASS));
        assertTrue(assertions.stream().anyMatch(a -> a.name().equals("RetryAmplificationTracking") && a.outcome() == AssertionOutcome.PASS));
    }
}
