package com.gatekeeper.resilience.bulkhead;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency bulkhead that enforces maximum concurrent execution limits per upstream route.
 * Rejects requests immediately without an unbounded waiting queue.
 */
public class RouteBulkhead {

    private final String routeId;
    private final BulkheadConfig config;
    private final Semaphore semaphore;
    private final AtomicLong rejectedCalls = new AtomicLong(0);

    public RouteBulkhead(String routeId, BulkheadConfig config) {
        this.routeId = Objects.requireNonNull(routeId, "routeId must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.semaphore = new Semaphore(config.maxConcurrentCalls());
    }

    public String getRouteId() {
        return routeId;
    }

    public BulkheadConfig getConfig() {
        return config;
    }

    /**
     * Attempts to acquire an execution permit.
     *
     * @return true if permit was acquired; false if capacity is exhausted
     */
    public boolean tryAcquirePermission() {
        boolean acquired = semaphore.tryAcquire();
        if (!acquired) {
            rejectedCalls.incrementAndGet();
        }
        return acquired;
    }

    /**
     * Releases an execution permit back to the bulkhead.
     * Must be called in a finally block.
     */
    public void releasePermission() {
        semaphore.release();
    }

    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }

    public int getMaxConcurrentCalls() {
        return config.maxConcurrentCalls();
    }

    public int getInFlightCalls() {
        return Math.max(0, config.maxConcurrentCalls() - semaphore.availablePermits());
    }

    public long getRejectedCalls() {
        return rejectedCalls.get();
    }
}
