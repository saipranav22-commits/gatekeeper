package com.gatekeeper.resilience.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CircuitBreakerAutomaticRecoveryTest - Phase 5B.2")
class CircuitBreakerAutomaticRecoveryTest {

    private MutableClock clock;
    private CircuitBreakerConfig config;
    private RouteCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-17T12:00:00Z"));
        // 50% failure rate, 50% slow rate, 500ms slow duration, window=10, minCalls=4, openWait=5000ms, halfOpenPermits=3
        config = new CircuitBreakerConfig(50, 50, 500L, 10, 4, 5000L, 3);
        breaker = new RouteCircuitBreaker("orders-service-v1", config, clock);
    }

    @Test
    @DisplayName("Deterministic Full Cycle: CLOSED -> OPEN -> HALF_OPEN -> CLOSED")
    void fullAutomaticRecoveryCycle() {
        // Step 1: Confirm healthy baseline in CLOSED state
        assertThat(breaker.getState()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.tryAcquirePermission()).isTrue();
        breaker.recordSuccess(50);
        assertThat(breaker.getState()).isEqualTo(CircuitState.CLOSED);

        // Step 2 & 3: Inject controlled failures to trip the circuit breaker
        // Window needs minCalls=4. Let's record 2 successes and 2 failures -> 50% failure rate
        breaker.recordSuccess(60);
        breaker.recordFailure(100, new RuntimeException("Simulated upstream 503"));
        breaker.recordFailure(100, new RuntimeException("Simulated upstream 503"));

        // Confirm breaker tripped to OPEN
        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);

        // Step 4: Confirm OPEN requests are fast-rejected immediately without reaching upstream
        for (int i = 0; i < 5; i++) {
            assertThat(breaker.tryAcquirePermission()).isFalse();
        }
        assertThat(breaker.getMetrics().rejectedCalls()).isEqualTo(5);
        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);

        // Step 5 & 6: Simulate passage of time during open state
        // Advance clock by 4999 ms (less than 5000 ms open wait duration)
        clock.advanceMillis(4999);
        // Still OPEN; requests still rejected
        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();

        // Advance 1 more ms to reach exactly 5000 ms
        clock.advanceMillis(1);

        // Step 7 & 8: Verify automatic transition to HALF_OPEN upon next call and permit probe #1
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.getState()).isEqualTo(CircuitState.HALF_OPEN);

        // Send probe #2 and probe #3 (permittedNumberOfCallsInHalfOpenState = 3)
        assertThat(breaker.tryAcquirePermission()).isTrue(); // probe #2
        assertThat(breaker.tryAcquirePermission()).isTrue(); // probe #3

        // Step 9: Confirm excess HALF_OPEN probes are rejected while trial is in progress
        assertThat(breaker.tryAcquirePermission()).isFalse(); // excess probe rejected
        assertThat(breaker.tryAcquirePermission()).isFalse(); // excess probe rejected
        assertThat(breaker.getState()).isEqualTo(CircuitState.HALF_OPEN);

        // Step 10: Confirm successful probes close the circuit
        breaker.recordSuccess(80);  // probe #1 success
        assertThat(breaker.getState()).isEqualTo(CircuitState.HALF_OPEN);

        breaker.recordSuccess(90);  // probe #2 success
        assertThat(breaker.getState()).isEqualTo(CircuitState.HALF_OPEN);

        breaker.recordSuccess(85);  // probe #3 success -> meets required 3 consecutive successes!
        assertThat(breaker.getState()).isEqualTo(CircuitState.CLOSED);

        // Step 11: Verify traffic resumes normally in CLOSED state
        assertThat(breaker.tryAcquirePermission()).isTrue();
        breaker.recordSuccess(75);
        assertThat(breaker.getState()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.getMetrics().totalCalls()).isEqualTo(1); // sliding window cleared on recovery
    }

    @Test
    @DisplayName("Deterministic Failed Recovery: CLOSED -> OPEN -> HALF_OPEN -> OPEN")
    void failedRecoveryReopensCircuit() {
        // Trip to OPEN
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure(100, new RuntimeException("Upstream down"));
        }
        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);

        // Advance clock past open duration (5000 ms)
        clock.advanceMillis(5000);

        // First call transitions to HALF_OPEN
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.getState()).isEqualTo(CircuitState.HALF_OPEN);

        // Probe call fails!
        breaker.recordFailure(100, new RuntimeException("Upstream still failing"));

        // Must immediately trip back to OPEN
        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();

        // Advance 2500 ms (new open duration has not elapsed yet)
        clock.advanceMillis(2500);
        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();

        // Advance another 2500 ms (total 5000 ms since second trip)
        clock.advanceMillis(2500);
        // Can enter HALF_OPEN again for retry
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.getState()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("Slow probe call during HALF_OPEN trips breaker back to OPEN if treated as failure")
    void slowProbeCallTripsCircuitBackToOpen() {
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure(100, null);
        }
        clock.advanceMillis(5000);

        // Probe #1 admitted
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.getState()).isEqualTo(CircuitState.HALF_OPEN);

        // Probe fails with timeout
        breaker.recordFailure(600, new java.net.http.HttpTimeoutException("Probe timed out"));
        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);
    }

    private static class MutableClock extends Clock {
        private final AtomicLong millis;
        private final ZoneId zone;

        public MutableClock(Instant initialInstant) {
            this.millis = new AtomicLong(initialInstant.toEpochMilli());
            this.zone = ZoneId.of("UTC");
        }

        public void advanceMillis(long delta) {
            millis.addAndGet(delta);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }
}
