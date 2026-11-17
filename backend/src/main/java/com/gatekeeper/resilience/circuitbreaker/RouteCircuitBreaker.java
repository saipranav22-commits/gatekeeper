package com.gatekeeper.resilience.circuitbreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance, thread-safe circuit breaker protecting upstream routes.
 *
 * <p>State Transitions:
 * <ul>
 *   <li><b>CLOSED &rarr; OPEN:</b> When minimum calls evaluated and either failure rate or slow call rate exceeds threshold.</li>
 *   <li><b>OPEN &rarr; HALF_OPEN:</b> After {@code waitDurationInOpenStateMs} has elapsed since opening.</li>
 *   <li><b>HALF_OPEN &rarr; CLOSED:</b> When consecutive test calls succeed without failures.</li>
 *   <li><b>HALF_OPEN &rarr; OPEN:</b> Immediately when any test probe call fails or times out.</li>
 * </ul>
 *
 * <p>Note: Breaker state is process-local to the gateway instance.
 */
public class RouteCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(RouteCircuitBreaker.class);

    private final String routeId;
    private final CircuitBreakerConfig config;
    private final Clock clock;
    private final SlidingWindow slidingWindow;

    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
    private volatile long lastOpenedAt = 0L;

    private final AtomicInteger halfOpenAdmittedCalls = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);
    private final AtomicLong totalRejectedCalls = new AtomicLong(0);

    public RouteCircuitBreaker(String routeId, CircuitBreakerConfig config) {
        this(routeId, config, Clock.systemUTC());
    }

    public RouteCircuitBreaker(String routeId, CircuitBreakerConfig config, Clock clock) {
        this.routeId = Objects.requireNonNull(routeId, "routeId must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.slidingWindow = new SlidingWindow(config.slidingWindowSize());
    }

    public String getRouteId() {
        return routeId;
    }

    public CircuitBreakerConfig getConfig() {
        return config;
    }

    public CircuitState getState() {
        checkAndPerformOpenToHalfOpenTransition();
        return state.get();
    }

    /**
     * Attempts to acquire permission to execute an upstream call.
     *
     * @return true if call is permitted; false if rejected by circuit breaker
     */
    public boolean tryAcquirePermission() {
        checkAndPerformOpenToHalfOpenTransition();

        CircuitState current = state.get();
        if (current == CircuitState.CLOSED) {
            return true;
        }

        if (current == CircuitState.HALF_OPEN) {
            int admitted = halfOpenAdmittedCalls.incrementAndGet();
            if (admitted <= config.permittedNumberOfCallsInHalfOpenState()) {
                return true;
            }
            totalRejectedCalls.incrementAndGet();
            return false;
        }

        // OPEN state
        totalRejectedCalls.incrementAndGet();
        return false;
    }

    /**
     * Records a successful upstream call execution.
     *
     * @param durationMs duration of the call in milliseconds
     */
    public void recordSuccess(long durationMs) {
        CircuitState current = state.get();

        if (current == CircuitState.HALF_OPEN) {
            int successes = halfOpenSuccesses.incrementAndGet();
            if (successes >= config.permittedNumberOfCallsInHalfOpenState()) {
                if (state.compareAndSet(CircuitState.HALF_OPEN, CircuitState.CLOSED)) {
                    slidingWindow.clear();
                    halfOpenAdmittedCalls.set(0);
                    halfOpenSuccesses.set(0);
                    log.info("CircuitBreaker '{}' successfully recovered: HALF_OPEN -> CLOSED", routeId);
                }
            }
            return;
        }

        if (current == CircuitState.CLOSED) {
            boolean isSlow = durationMs >= config.slowCallDurationMs();
            slidingWindow.record(isSlow ? SlidingWindow.Outcome.SLOW_SUCCESS : SlidingWindow.Outcome.SUCCESS);
            evaluateClosedStateThresholds();
        }
    }

    public void recordFailure() {
        recordFailure(0L, null);
    }

    /**
     * Records a failed upstream call execution.
     *
     * @param durationMs duration of the call in milliseconds
     * @param throwable cause of the failure
     */
    public void recordFailure(long durationMs, Throwable throwable) {
        CircuitState current = state.get();

        if (current == CircuitState.HALF_OPEN) {
            // Immediate trip back to OPEN on probe failure
            if (state.compareAndSet(CircuitState.HALF_OPEN, CircuitState.OPEN)) {
                lastOpenedAt = clock.millis();
                halfOpenAdmittedCalls.set(0);
                halfOpenSuccesses.set(0);
                log.warn("CircuitBreaker '{}' probe call failed: HALF_OPEN -> OPEN (reason: {})",
                    routeId, throwable != null ? throwable.getMessage() : "upstream failure");
            }
            return;
        }

        if (current == CircuitState.CLOSED) {
            boolean isSlow = durationMs >= config.slowCallDurationMs();
            slidingWindow.record(isSlow ? SlidingWindow.Outcome.SLOW_FAILURE : SlidingWindow.Outcome.FAILURE);
            evaluateClosedStateThresholds();
        }
    }

    private void evaluateClosedStateThresholds() {
        SlidingWindow.Snapshot snapshot = slidingWindow.getSnapshot();
        if (snapshot.totalCalls() >= config.minimumNumberOfCalls()) {
            boolean failureExceeded = snapshot.failureRate() >= config.failureRateThreshold();
            boolean slowExceeded = snapshot.slowCallRate() >= config.slowCallRateThreshold();

            if (failureExceeded || slowExceeded) {
                if (state.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN)) {
                    lastOpenedAt = clock.millis();
                    log.warn("CircuitBreaker '{}' tripped: CLOSED -> OPEN (failureRate={}% [threshold={}%], slowRate={}% [threshold={}%], totalCalls={})",
                        routeId, snapshot.failureRate(), config.failureRateThreshold(),
                        snapshot.slowCallRate(), config.slowCallRateThreshold(), snapshot.totalCalls());
                }
            }
        }
    }

    private void checkAndPerformOpenToHalfOpenTransition() {
        if (state.get() == CircuitState.OPEN) {
            long elapsed = clock.millis() - lastOpenedAt;
            if (elapsed >= config.waitDurationInOpenStateMs()) {
                if (state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                    halfOpenAdmittedCalls.set(0);
                    halfOpenSuccesses.set(0);
                    log.info("CircuitBreaker '{}' wait duration elapsed ({}ms): OPEN -> HALF_OPEN", routeId, elapsed);
                }
            }
        }
    }

    /**
     * Returns a snapshot of current metrics.
     */
    public CircuitBreakerMetrics getMetrics() {
        SlidingWindow.Snapshot s = slidingWindow.getSnapshot();
        return new CircuitBreakerMetrics(
            s.totalCalls(),
            s.successfulCalls(),
            s.failedCalls(),
            s.slowCalls(),
            s.failureRate(),
            s.slowCallRate(),
            totalRejectedCalls.get()
        );
    }

    public void transitionToOpen() {
        state.set(CircuitState.OPEN);
        lastOpenedAt = clock.millis();
        halfOpenAdmittedCalls.set(0);
        halfOpenSuccesses.set(0);
    }

    public void transitionToClosed() {
        state.set(CircuitState.CLOSED);
        slidingWindow.clear();
        halfOpenAdmittedCalls.set(0);
        halfOpenSuccesses.set(0);
    }

    public void transitionToHalfOpen() {
        state.set(CircuitState.HALF_OPEN);
        halfOpenAdmittedCalls.set(0);
        halfOpenSuccesses.set(0);
    }

    public void reset() {
        transitionToClosed();
        totalRejectedCalls.set(0);
    }
}
