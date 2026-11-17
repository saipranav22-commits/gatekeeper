package com.gatekeeper.orders.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fault simulation manager for controlled resilience testing.
 * Strictly disabled in production profile (!prod).
 */
@Component
@Profile({"local", "test", "dev", "default"})
public class FaultSimulationManager {

    private static final Logger log = LoggerFactory.getLogger(FaultSimulationManager.class);

    private volatile boolean active = false;
    private volatile long delayMs = 0L;
    private volatile int statusCode = 0;
    private final AtomicInteger remainingFailures = new AtomicInteger(0);
    private final AtomicLong totalFaultsInjected = new AtomicLong(0);
    private final AtomicLong totalAttemptsReceived = new AtomicLong(0);
    private final Map<String, AtomicInteger> attemptsByCorrelationId = new ConcurrentHashMap<>();

    public synchronized void configure(long delayMs, int statusCode, int failureCount) {
        this.delayMs = delayMs;
        this.statusCode = statusCode;
        this.remainingFailures.set(failureCount > 0 ? failureCount : Integer.MAX_VALUE);
        this.active = true;
        log.warn("Fault simulation configured: delayMs={}, statusCode={}, failureCount={}",
            delayMs, statusCode, failureCount);
    }

    public synchronized void reset() {
        this.active = false;
        this.delayMs = 0L;
        this.statusCode = 0;
        this.remainingFailures.set(0);
        this.totalFaultsInjected.set(0);
        this.totalAttemptsReceived.set(0);
        this.attemptsByCorrelationId.clear();
        log.info("Fault simulation reset to normal operation");
    }

    public void recordAttempt(String correlationId) {
        totalAttemptsReceived.incrementAndGet();
        if (correlationId != null && !correlationId.isBlank()) {
            attemptsByCorrelationId.computeIfAbsent(correlationId.trim(), k -> new AtomicInteger(0))
                .incrementAndGet();
        }
    }

    public int getAttemptsForCorrelationId(String correlationId) {
        if (correlationId == null) return 0;
        AtomicInteger count = attemptsByCorrelationId.get(correlationId.trim());
        return count != null ? count.get() : 0;
    }

    public boolean shouldSimulate() {
        return active;
    }

    public void applyFault() throws InterruptedException {
        if (!active) {
            return;
        }

        if (delayMs > 0) {
            log.info("Simulating artificial latency of {} ms", delayMs);
            Thread.sleep(delayMs);
        }

        if (statusCode >= 400) {
            int remaining = remainingFailures.getAndUpdate(count -> count > 0 ? count - 1 : 0);
            if (remaining > 0) {
                totalFaultsInjected.incrementAndGet();
                log.warn("Simulating HTTP {} fault (remaining={})", statusCode, remaining - 1);
                throw new SimulatedFaultException(statusCode, "Simulated fault: HTTP " + statusCode);
            }
        }
    }

    public boolean isActive() {
        return active;
    }

    public long getDelayMs() {
        return delayMs;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public int getRemainingFailures() {
        return remainingFailures.get();
    }

    public long getTotalFaultsInjected() {
        return totalFaultsInjected.get();
    }

    public long getTotalAttemptsReceived() {
        return totalAttemptsReceived.get();
    }
}
