package com.gatekeeper.resilience.bulkhead;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RouteBulkheadTest {

    private RouteBulkhead bulkhead;

    @BeforeEach
    void setUp() {
        bulkhead = new RouteBulkhead("orders-route", new BulkheadConfig(3));
    }

    @Test
    @DisplayName("Allows concurrent calls up to maximum configured capacity")
    void allowsCallsUpToMaxCapacity() {
        assertThat(bulkhead.getMaxConcurrentCalls()).isEqualTo(3);
        assertThat(bulkhead.getAvailablePermits()).isEqualTo(3);
        assertThat(bulkhead.getInFlightCalls()).isZero();

        assertThat(bulkhead.tryAcquirePermission()).isTrue();
        assertThat(bulkhead.getAvailablePermits()).isEqualTo(2);
        assertThat(bulkhead.getInFlightCalls()).isEqualTo(1);

        assertThat(bulkhead.tryAcquirePermission()).isTrue();
        assertThat(bulkhead.getAvailablePermits()).isEqualTo(1);
        assertThat(bulkhead.getInFlightCalls()).isEqualTo(2);

        assertThat(bulkhead.tryAcquirePermission()).isTrue();
        assertThat(bulkhead.getAvailablePermits()).isZero();
        assertThat(bulkhead.getInFlightCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("Rejects immediately when concurrency capacity is saturated")
    void rejectsImmediatelyWhenSaturated() {
        bulkhead.tryAcquirePermission();
        bulkhead.tryAcquirePermission();
        bulkhead.tryAcquirePermission();

        // 4th call must be rejected
        assertThat(bulkhead.tryAcquirePermission()).isFalse();
        assertThat(bulkhead.getRejectedCalls()).isEqualTo(1);

        // 5th call rejected
        assertThat(bulkhead.tryAcquirePermission()).isFalse();
        assertThat(bulkhead.getRejectedCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("Releasing permit in finally block restores availability")
    void releasePermitRestoresAvailability() {
        bulkhead.tryAcquirePermission();
        bulkhead.tryAcquirePermission();
        bulkhead.tryAcquirePermission();

        assertThat(bulkhead.tryAcquirePermission()).isFalse();

        // Release one permit
        bulkhead.releasePermission();
        assertThat(bulkhead.getAvailablePermits()).isEqualTo(1);
        assertThat(bulkhead.getInFlightCalls()).isEqualTo(2);

        // Now next call succeeds
        assertThat(bulkhead.tryAcquirePermission()).isTrue();
        assertThat(bulkhead.getAvailablePermits()).isZero();
    }

    @Test
    @DisplayName("Independent bulkheads isolate concurrency limits between routes")
    void independentBulkheadsIsolateLimits() {
        RouteBulkhead inventoryBulkhead = new RouteBulkhead("inventory-route", new BulkheadConfig(2));

        bulkhead.tryAcquirePermission();
        bulkhead.tryAcquirePermission();
        bulkhead.tryAcquirePermission();
        assertThat(bulkhead.tryAcquirePermission()).isFalse();

        // Inventory route permits are unaffected
        assertThat(inventoryBulkhead.getAvailablePermits()).isEqualTo(2);
        assertThat(inventoryBulkhead.tryAcquirePermission()).isTrue();
        assertThat(inventoryBulkhead.tryAcquirePermission()).isTrue();
        assertThat(inventoryBulkhead.tryAcquirePermission()).isFalse();
    }

    @Test
    @DisplayName("Thread safety under high concurrent contention")
    void concurrentThreadSafety() throws Exception {
        int threads = 20;
        int maxCapacity = 5;
        RouteBulkhead concurrentBulkhead = new RouteBulkhead("concurrent-route", new BulkheadConfig(maxCapacity));
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger successfulAcquires = new AtomicInteger(0);
        AtomicInteger rejectedAcquires = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (concurrentBulkhead.tryAcquirePermission()) {
                        successfulAcquires.incrementAndGet();
                        // Hold briefly
                        Thread.sleep(20);
                        concurrentBulkhead.releasePermission();
                    } else {
                        rejectedAcquires.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(successfulAcquires.get() + rejectedAcquires.get()).isEqualTo(threads);
        // All permits must be released back
        assertThat(concurrentBulkhead.getAvailablePermits()).isEqualTo(maxCapacity);
        assertThat(concurrentBulkhead.getInFlightCalls()).isZero();
    }
}
