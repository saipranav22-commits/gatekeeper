package com.gatekeeper.chaos.engine;

import com.gatekeeper.chaos.model.ChaosExperiment;
import com.gatekeeper.chaos.model.ChaosScenarioType;
import com.gatekeeper.chaos.model.ChaosTarget;
import com.gatekeeper.chaos.model.FaultType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Phase 5D: Chaos Safety Guard Unit Tests")
class ChaosSafetyGuardTest {

    @Test
    @DisplayName("Safety: Production profile strictly throws SecurityException")
    void rejectsProductionProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        ChaosSafetyGuard guard = new ChaosSafetyGuard(env);

        assertThrows(SecurityException.class, guard::validateEnvironment);
    }

    @Test
    @DisplayName("Safety: Default or empty profile strictly throws SecurityException")
    void rejectsDefaultProfileWithoutExplicitLocalOrTest() {
        MockEnvironment env = new MockEnvironment(); // default / empty
        ChaosSafetyGuard guard = new ChaosSafetyGuard(env);

        assertThrows(SecurityException.class, guard::validateEnvironment);
    }

    @Test
    @DisplayName("Safety: Local, test, and dev profiles are permitted")
    void permitsNonProductionProfiles() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local", "test");
        ChaosSafetyGuard guard = new ChaosSafetyGuard(env);

        assertDoesNotThrow(guard::validateEnvironment);
    }

    @Test
    @DisplayName("Safety: Rejects unauthorized remote hosts and ports")
    void rejectsUnauthorizedTargets() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        ChaosSafetyGuard guard = new ChaosSafetyGuard(env);

        assertThrows(SecurityException.class, () -> guard.validateTargetUri("http://evil-external-host.com:8080"));
        assertThrows(SecurityException.class, () -> guard.validateTargetUri("http://192.168.1.100:8080"));
        assertThrows(SecurityException.class, () -> guard.validateTargetUri("http://localhost:9999")); // Unauthorized port
    }

    @Test
    @DisplayName("Safety: Allows authorized loopback target URIs")
    void allowsAuthorizedLoopbackTargets() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        ChaosSafetyGuard guard = new ChaosSafetyGuard(env);

        assertDoesNotThrow(() -> guard.validateTargetUri("http://localhost:8081"));
        assertDoesNotThrow(() -> guard.validateTargetUri("http://127.0.0.1:8082"));
        assertDoesNotThrow(() -> guard.validateTargetUri("internal"));
    }

    @Test
    @DisplayName("Safety: Enforces hard limits on concurrency, duration, and request volume")
    void enforcesParameterBoundaries() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        ChaosSafetyGuard guard = new ChaosSafetyGuard(env);

        ChaosExperiment excessiveVolume = new ChaosExperiment(
            "exp-1", ChaosScenarioType.ORDERS_OUTAGE, ChaosTarget.ORDERS_SERVICE, FaultType.HTTP_ERROR,
            Map.of("requestCount", 1000) // exceeds MAX_REQUEST_COUNT (500)
        );
        assertThrows(IllegalArgumentException.class, () -> guard.acquireTargetLock(excessiveVolume));

        ChaosExperiment excessiveConcurrency = new ChaosExperiment(
            "exp-2", ChaosScenarioType.ORDERS_OUTAGE, ChaosTarget.ORDERS_SERVICE, FaultType.HTTP_ERROR,
            Map.of("concurrency", 100) // exceeds MAX_CONCURRENCY (50)
        );
        assertThrows(IllegalArgumentException.class, () -> guard.acquireTargetLock(excessiveConcurrency));
    }

    @Test
    @DisplayName("Safety: Prevents multiple concurrent disruptive experiments on the same target")
    void preventsConcurrentDisruptiveExperimentsOnSameTarget() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        ChaosSafetyGuard guard = new ChaosSafetyGuard(env);

        ChaosExperiment exp1 = new ChaosExperiment("exp-1", ChaosScenarioType.ORDERS_OUTAGE, ChaosTarget.ORDERS_SERVICE, FaultType.HTTP_ERROR, Map.of());
        ChaosExperiment exp2 = new ChaosExperiment("exp-2", ChaosScenarioType.SLOW_UPSTREAM, ChaosTarget.ORDERS_SERVICE, FaultType.LATENCY_INJECTION, Map.of());

        guard.acquireTargetLock(exp1);
        try {
            assertThrows(IllegalStateException.class, () -> guard.acquireTargetLock(exp2));
        } finally {
            guard.releaseTargetLock(exp1);
        }

        // Lock released: exp2 can now acquire lock
        assertDoesNotThrow(() -> guard.acquireTargetLock(exp2));
        guard.releaseTargetLock(exp2);
    }
}
