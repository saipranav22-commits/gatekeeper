package com.gatekeeper.resilience.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RouteCircuitBreakerTest {

    private MutableClock clock;
    private CircuitBreakerConfig config;
    private RouteCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-17T12:00:00Z"));
        // 50% failure threshold, 50% slow rate threshold, 500ms slow duration, window=10, minCalls=4, openWait=5000ms, halfOpenPermits=2
        config = new CircuitBreakerConfig(50, 50, 500L, 10, 4, 5000L, 2);
        breaker = new RouteCircuitBreaker("orders-service", config, clock);
    }

    @Test
    @DisplayName("Initial state is CLOSED and allows requests")
    void initialStateIsClosed() {
        assertThat(breaker.getState()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.tryAcquirePermission()).isTrue();
        CircuitBreakerMetrics metrics = breaker.getMetrics();
        assertThat(metrics.totalCalls()).isZero();
        assertThat(metrics.rejectedCalls()).isZero();
    }

    @Test
    @DisplayName("Remains CLOSED when below minimumNumberOfCalls even if all fail")
    void remainsClosedBelowMinCalls() {
        // Record 3 failures (minCalls is 4)
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.getState()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.tryAcquirePermission()).isTrue();
    }

    @Test
    @DisplayName("Transitions CLOSED -> OPEN when failure rate meets or exceeds threshold")
    void transitionsToOpenOnFailureRate() {
        // 4 calls: 2 success, 2 failures -> 50% failure rate (threshold is 50%)
        breaker.recordSuccess(100);
        breaker.recordSuccess(100);
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);
        // Immediate rejection in OPEN state
        assertThat(breaker.tryAcquirePermission()).isFalse();
        assertThat(breaker.getMetrics().rejectedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Transitions CLOSED -> OPEN when slow call rate meets or exceeds threshold")
    void transitionsToOpenOnSlowCallRate() {
        // Slow duration threshold is 500ms
        breaker.recordSuccess(100);
        breaker.recordSuccess(100);
        breaker.recordSuccess(600); // slow
        breaker.recordSuccess(700); // slow

        // 2 of 4 calls slow -> 50% slow rate
        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();
    }

    @Test
    @DisplayName("Transitions OPEN -> HALF_OPEN after waitDurationInOpenStateMs has elapsed")
    void transitionsToHalfOpenAfterWaitDuration() {
        // Trigger OPEN
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();

        // Advance clock by 4999 ms (less than 5000ms openWait)
        clock.advanceMillis(4999);
        assertThat(breaker.tryAcquirePermission()).isFalse();
        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);

        // Advance clock by 1 ms to reach 5000 ms
        clock.advanceMillis(1);
        assertThat(breaker.tryAcquirePermission()).isTrue(); // transitions to HALF_OPEN and admits call #1
        assertThat(breaker.getState()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("HALF_OPEN admits exactly permittedCallsInHalfOpen then rejects extra calls")
    void halfOpenAdmittedCallsAreBounded() {
        // Trigger OPEN
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure();
        }
        clock.advanceMillis(5000);

        // First call admitted -> transitions to HALF_OPEN
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.getState()).isEqualTo(CircuitState.HALF_OPEN);

        // Second call admitted (permittedCallsInHalfOpen = 2)
        assertThat(breaker.tryAcquirePermission()).isTrue();

        // Third call rejected while probe calls in progress
        assertThat(breaker.tryAcquirePermission()).isFalse();
    }

    @Test
    @DisplayName("HALF_OPEN transitions to CLOSED when all probe calls succeed")
    void halfOpenTransitionsToClosedOnSuccess() {
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure();
        }
        clock.advanceMillis(5000);

        // Admit 2 calls
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.tryAcquirePermission()).isTrue();

        // Record successes
        breaker.recordSuccess(100);
        assertThat(breaker.getState()).isEqualTo(CircuitState.HALF_OPEN);

        breaker.recordSuccess(150);
        assertThat(breaker.getState()).isEqualTo(CircuitState.CLOSED);

        // Normal calls can resume
        assertThat(breaker.tryAcquirePermission()).isTrue();
    }

    @Test
    @DisplayName("HALF_OPEN transitions immediately back to OPEN if any probe call fails")
    void halfOpenTransitionsToOpenOnFailure() {
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure();
        }
        clock.advanceMillis(5000);

        assertThat(breaker.tryAcquirePermission()).isTrue();
        breaker.recordFailure();

        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();
    }

    @Test
    @DisplayName("Manual reset restores CLOSED state and clears metrics")
    void manualResetRestoresClosedState() {
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();

        breaker.reset();

        assertThat(breaker.getState()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.getMetrics().totalCalls()).isZero();
        assertThat(breaker.getMetrics().rejectedCalls()).isZero();
    }

    @Test
    @DisplayName("Multiple routes maintain independent circuit breaker state")
    void multipleRoutesMaintainIndependentState() {
        RouteCircuitBreaker inventoryBreaker = new RouteCircuitBreaker("inventory-service", config, clock);

        for (int i = 0; i < 4; i++) {
            breaker.recordFailure();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();

        // Inventory route is completely unaffected
        assertThat(inventoryBreaker.getState()).isEqualTo(CircuitState.CLOSED);
        assertThat(inventoryBreaker.tryAcquirePermission()).isTrue();
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
