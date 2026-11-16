package com.gatekeeper.ratelimit.service;

import com.gatekeeper.plan.entity.SubscriptionPlan;
import com.gatekeeper.ratelimit.dto.RateLimitDecision;
import com.gatekeeper.ratelimit.dto.RateLimitPlan;
import com.gatekeeper.ratelimit.exception.RateLimitUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("local")
class RedisTokenBucketRateLimiterTest {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiterTest.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTokenBucketRateLimiter rateLimiter;

    private final List<UUID> testConsumerIds = new ArrayList<>();

    // Standard plans
    private final RateLimitPlan freePlan = new RateLimitPlan("FREE", 60, 60, 60);
    private final RateLimitPlan proPlan = new RateLimitPlan("PRO", 600, 600, 60);

    private UUID createTestConsumerId() {
        UUID id = UUID.randomUUID();
        testConsumerIds.add(id);
        return id;
    }

    @BeforeEach
    void setUp() {
        testConsumerIds.clear();
    }

    @AfterEach
    void tearDown() {
        for (UUID id : testConsumerIds) {
            stringRedisTemplate.delete(RedisTokenBucketRateLimiter.KEY_PREFIX + id);
        }
    }

    @Test
    @DisplayName("Scenario 1: Initial bucket capacity and single token consumption")
    void testInitialCapacityAndSingleTokenConsumption() {
        UUID consumerId = createTestConsumerId();

        RateLimitDecision decision = rateLimiter.consume(consumerId, freePlan, 1);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.tokensRemaining()).isEqualTo(59);
        assertThat(decision.bucketCapacity()).isEqualTo(60);
        assertThat(decision.retryAfterSeconds()).isEqualTo(0);
        assertThat(decision.resetAfterSeconds()).isGreaterThanOrEqualTo(1);
        assertThat(decision.limitPolicy()).isEqualTo("FREE");
        assertThat(decision.decisionTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("Scenario 2: Sequential token consumption until exhaustion")
    void testSequentialTokenConsumptionUntilExhaustion() {
        UUID consumerId = createTestConsumerId();
        RateLimitPlan smallPlan = new RateLimitPlan("TEST_SMALL", 5, 5, 60);

        for (int i = 0; i < 5; i++) {
            RateLimitDecision decision = rateLimiter.consume(consumerId, smallPlan, 1);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.tokensRemaining()).isEqualTo(4 - i);
        }

        // 6th consumption must fail
        RateLimitDecision exhaustedDecision = rateLimiter.consume(consumerId, smallPlan, 1);
        assertThat(exhaustedDecision.allowed()).isFalse();
        assertThat(exhaustedDecision.tokensRemaining()).isEqualTo(0);
        assertThat(exhaustedDecision.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Scenario 3: Empty bucket rejection and zero negative balance")
    void testEmptyBucketRejectionAndZeroNegativeBalance() {
        UUID consumerId = createTestConsumerId();
        RateLimitPlan smallPlan = new RateLimitPlan("TEST_BURST", 3, 3, 60);

        // Exhaust all 3 tokens at once
        RateLimitDecision d1 = rateLimiter.consume(consumerId, smallPlan, 3);
        assertThat(d1.allowed()).isTrue();
        assertThat(d1.tokensRemaining()).isEqualTo(0);

        // Repeated requests against empty bucket
        for (int i = 0; i < 5; i++) {
            RateLimitDecision rejected = rateLimiter.consume(consumerId, smallPlan, 1);
            assertThat(rejected.allowed()).isFalse();
            assertThat(rejected.tokensRemaining()).isEqualTo(0); // Never drops below 0
            assertThat(rejected.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("Scenario 4: Refill calculation and partial refill over time")
    void testPartialRefillOverTime() throws InterruptedException {
        UUID consumerId = createTestConsumerId();
        // 10 tokens per second (10 tokens / 1 second)
        RateLimitPlan fastPlan = new RateLimitPlan("FAST_REFILL", 10, 10, 1);

        // Consume all 10 tokens
        RateLimitDecision d1 = rateLimiter.consume(consumerId, fastPlan, 10);
        assertThat(d1.allowed()).isTrue();
        assertThat(d1.tokensRemaining()).isEqualTo(0);

        // Next immediate request rejected
        RateLimitDecision d2 = rateLimiter.consume(consumerId, fastPlan, 1);
        assertThat(d2.allowed()).isFalse();

        // Sleep 350ms -> should refill ~3 tokens
        Thread.sleep(350);

        // Consume 2 tokens: should now be allowed
        RateLimitDecision d3 = rateLimiter.consume(consumerId, fastPlan, 2);
        assertThat(d3.allowed()).isTrue();
        assertThat(d3.tokensRemaining()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Scenario 5: Full refill caps at bucket capacity without over-refill")
    void testFullRefillCapsAtBucketCapacity() throws InterruptedException {
        UUID consumerId = createTestConsumerId();
        RateLimitPlan fastPlan = new RateLimitPlan("CAP_TEST", 10, 50, 1);

        // Consume 10 tokens
        RateLimitDecision d1 = rateLimiter.consume(consumerId, fastPlan, 10);
        assertThat(d1.allowed()).isTrue();
        assertThat(d1.tokensRemaining()).isEqualTo(0);

        // Wait 300ms (refills ~15 tokens, but bucket capacity is 10)
        Thread.sleep(300);

        // Next consumption of 1 token: bucket must cap at 10, so remaining is 9
        RateLimitDecision d2 = rateLimiter.consume(consumerId, fastPlan, 1);
        assertThat(d2.allowed()).isTrue();
        assertThat(d2.tokensRemaining()).isEqualTo(9);
    }

    @Test
    @DisplayName("Scenario 6: Concurrency stress — 100 simultaneous requests against 60-token bucket")
    void testHighConcurrencyStress() throws InterruptedException {
        UUID consumerId = createTestConsumerId();
        int totalRequests = 100;
        int capacity = 60;
        RateLimitPlan plan = new RateLimitPlan("CONCURRENCY", capacity, capacity, 3600); // long refill period so negligible refill during test

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    RateLimitDecision decision = rateLimiter.consume(consumerId, plan, 1);
                    if (decision.allowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        deniedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Error during concurrency test", e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Unleash all threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(allowedCount.get()).isEqualTo(60);
        assertThat(deniedCount.get()).isEqualTo(40);

        // Final balance should be exactly 0
        RateLimitDecision finalCheck = rateLimiter.consume(consumerId, plan, 1);
        assertThat(finalCheck.allowed()).isFalse();
        assertThat(finalCheck.tokensRemaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("Scenario 7: Consumer isolation — Consumer A does not affect Consumer B")
    void testConsumerIsolation() {
        UUID consumerA = createTestConsumerId();
        UUID consumerB = createTestConsumerId();

        // Consumer A drains all 60 tokens
        RateLimitDecision drainA = rateLimiter.consume(consumerA, freePlan, 60);
        assertThat(drainA.allowed()).isTrue();
        assertThat(drainA.tokensRemaining()).isEqualTo(0);

        RateLimitDecision rejectedA = rateLimiter.consume(consumerA, freePlan, 1);
        assertThat(rejectedA.allowed()).isFalse();

        // Consumer B requests 1 token: completely untouched
        RateLimitDecision decisionB = rateLimiter.consume(consumerB, freePlan, 1);
        assertThat(decisionB.allowed()).isTrue();
        assertThat(decisionB.tokensRemaining()).isEqualTo(59);
    }

    @Test
    @DisplayName("Scenario 8: Multi-key shared quota — Multiple API keys share consumer bucket")
    void testMultiKeySharedQuota() {
        UUID consumerId = createTestConsumerId();

        // Key 1 consumes 30 tokens
        RateLimitDecision key1 = rateLimiter.consume(consumerId, freePlan, 30);
        assertThat(key1.allowed()).isTrue();
        assertThat(key1.tokensRemaining()).isEqualTo(30);

        // Key 2 consumes 30 tokens
        RateLimitDecision key2 = rateLimiter.consume(consumerId, freePlan, 30);
        assertThat(key2.allowed()).isTrue();
        assertThat(key2.tokensRemaining()).isEqualTo(0);

        // Key 3 attempts to consume 1 token -> denied because consumer quota is exhausted
        RateLimitDecision key3 = rateLimiter.consume(consumerId, freePlan, 1);
        assertThat(key3.allowed()).isFalse();
        assertThat(key3.tokensRemaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("Scenario 9: Plan upgrade preserves existing balance without unearned bursts")
    void testPlanUpgradeBalancePreserved() {
        UUID consumerId = createTestConsumerId();

        // Start on FREE (capacity 60). Consume 40 tokens -> 20 remaining
        RateLimitDecision free1 = rateLimiter.consume(consumerId, freePlan, 40);
        assertThat(free1.allowed()).isTrue();
        assertThat(free1.tokensRemaining()).isEqualTo(20);

        // Upgrade to PRO (capacity 600, refill 600 / 60s). Consume 1 token immediately
        RateLimitDecision pro1 = rateLimiter.consume(consumerId, proPlan, 1);
        assertThat(pro1.allowed()).isTrue();
        // Balance was 20; minus 1 token consumed = 19 (not 599!)
        assertThat(pro1.tokensRemaining()).isEqualTo(19);
        assertThat(pro1.bucketCapacity()).isEqualTo(600);
        assertThat(pro1.limitPolicy()).isEqualTo("PRO");
    }

    @Test
    @DisplayName("Scenario 10: Plan downgrade clamps balance to new capacity")
    void testPlanDowngradeClampsBalanceToNewCapacity() {
        UUID consumerId = createTestConsumerId();

        // Start on PRO (capacity 600). Consume 100 tokens -> 500 remaining
        RateLimitDecision pro1 = rateLimiter.consume(consumerId, proPlan, 100);
        assertThat(pro1.allowed()).isTrue();
        assertThat(pro1.tokensRemaining()).isEqualTo(500);

        // Downgrade to FREE (capacity 60).
        // Balance 500 is clamped to FREE capacity 60, then 1 token consumed -> 59 remaining
        RateLimitDecision free1 = rateLimiter.consume(consumerId, freePlan, 1);
        assertThat(free1.allowed()).isTrue();
        assertThat(free1.tokensRemaining()).isEqualTo(59);
        assertThat(free1.bucketCapacity()).isEqualTo(60);
        assertThat(free1.limitPolicy()).isEqualTo("FREE");
    }

    @Test
    @DisplayName("Scenario 11: TTL expiration set on Redis key")
    void testTtlExpirationOnRedisKey() {
        UUID consumerId = createTestConsumerId();

        rateLimiter.consume(consumerId, freePlan, 1);

        String key = RedisTokenBucketRateLimiter.KEY_PREFIX + consumerId;
        Long expireSeconds = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);

        assertThat(expireSeconds).isNotNull();
        // Minimum TTL is 86400 (24h)
        assertThat(expireSeconds).isGreaterThanOrEqualTo(86300L);
    }

    @Test
    @DisplayName("Scenario 12: Fail-closed on Redis outage throws RateLimitUnavailableException")
    void testFailClosedOnRedisOutage() {
        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class);
        when(brokenRedis.execute(
            any(org.springframework.data.redis.core.script.RedisScript.class),
            any(List.class),
            any(), any(), any(), any(), any()
        )).thenThrow(new RedisConnectionFailureException("Redis connection refused"));

        RedisTokenBucketRateLimiter resilientLimiter = new RedisTokenBucketRateLimiter(brokenRedis);

        UUID consumerId = UUID.randomUUID();
        assertThatThrownBy(() -> resilientLimiter.consume(consumerId, freePlan, 1))
            .isInstanceOf(RateLimitUnavailableException.class)
            .hasMessageContaining("Rate limiting service unavailable");
    }

    @Test
    @DisplayName("Scenario 13: Malformed Redis state self-healing")
    void testMalformedRedisStateSelfHealing() {
        UUID consumerId = createTestConsumerId();
        String key = RedisTokenBucketRateLimiter.KEY_PREFIX + consumerId;

        // Populate corrupt state into Redis hash
        stringRedisTemplate.opsForHash().put(key, "tokens", "corrupt_garbage");
        stringRedisTemplate.opsForHash().put(key, "last_refill_ms", "not_a_number");

        // Evaluator must detect non-numeric fields and safely reinitialize the bucket
        RateLimitDecision decision = rateLimiter.consume(consumerId, freePlan, 1);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.tokensRemaining()).isEqualTo(59);
        assertThat(decision.bucketCapacity()).isEqualTo(60);
    }

    @Test
    @DisplayName("Scenario 14: Invalid request cost throws IllegalArgumentException")
    void testInvalidRequestCost() {
        UUID consumerId = createTestConsumerId();

        // Cost <= 0
        assertThatThrownBy(() -> rateLimiter.consume(consumerId, freePlan, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requestCost must be positive");

        assertThatThrownBy(() -> rateLimiter.consume(consumerId, freePlan, -5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requestCost must be positive");

        // Cost > Capacity (61 > 60)
        assertThatThrownBy(() -> rateLimiter.consume(consumerId, freePlan, 61))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot exceed bucket capacity");
    }

    @Test
    @DisplayName("Scenario 15: Null parameters throw IllegalArgumentException")
    void testNullParameters() {
        UUID consumerId = createTestConsumerId();

        assertThatThrownBy(() -> rateLimiter.consume(null, freePlan, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("consumerId must not be null");

        assertThatThrownBy(() -> rateLimiter.consume(consumerId, (RateLimitPlan) null, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("plan must not be null");

        assertThatThrownBy(() -> rateLimiter.resetBucket(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("consumerId must not be null");
    }

    @Test
    @DisplayName("Scenario 16: Retry-After calculation accuracy")
    void testRetryAfterCalculationAccuracy() {
        UUID consumerId = createTestConsumerId();
        // 60 tokens capacity, refill 60 tokens every 60 seconds (1 token / sec)
        rateLimiter.consume(consumerId, freePlan, 60);

        // Bucket has 0 tokens. Request cost 5 tokens -> needs 5 tokens at 1 token/sec = ~5 seconds
        RateLimitDecision decision = rateLimiter.consume(consumerId, freePlan, 5);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isBetween(4L, 6L);
    }

    @Test
    @DisplayName("Scenario 17: Reset-After calculation accuracy")
    void testResetAfterCalculationAccuracy() {
        UUID consumerId = createTestConsumerId();
        // Capacity 60, refill 60 tokens / 60 seconds (1 token / sec).
        // Consume 20 tokens -> 40 tokens remaining. Needs 20 tokens to reach capacity 60 = ~20 seconds
        RateLimitDecision decision = rateLimiter.consume(consumerId, freePlan, 20);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.tokensRemaining()).isEqualTo(40);
        assertThat(decision.resetAfterSeconds()).isBetween(19L, 21L);
    }

    @Test
    @DisplayName("Scenario 18: Reset bucket clears state in Redis")
    void testResetBucket() {
        UUID consumerId = createTestConsumerId();

        // Drain bucket
        rateLimiter.consume(consumerId, freePlan, 60);
        RateLimitDecision empty = rateLimiter.consume(consumerId, freePlan, 1);
        assertThat(empty.allowed()).isFalse();

        // Reset
        rateLimiter.resetBucket(consumerId);

        // Next consumption should start fresh with full capacity
        RateLimitDecision fresh = rateLimiter.consume(consumerId, freePlan, 1);
        assertThat(fresh.allowed()).isTrue();
        assertThat(fresh.tokensRemaining()).isEqualTo(59);
    }

    @Test
    @DisplayName("Scenario 19: SubscriptionPlan entity adapter overloads")
    void testSubscriptionPlanOverloads() {
        UUID consumerId = createTestConsumerId();
        SubscriptionPlan entity = new SubscriptionPlan("ENTERPRISE", "Enterprise Plan", 1000, 1000, 60);

        RateLimitDecision decision = rateLimiter.consume(consumerId, entity);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.bucketCapacity()).isEqualTo(1000);
        assertThat(decision.tokensRemaining()).isEqualTo(999);
        assertThat(decision.limitPolicy()).isEqualTo("ENTERPRISE");

        RateLimitDecision decisionCost = rateLimiter.consume(consumerId, entity, 10);
        assertThat(decisionCost.allowed()).isTrue();
        assertThat(decisionCost.tokensRemaining()).isEqualTo(989);
    }

    @Test
    @DisplayName("Scenario 20: High-throughput local performance benchmark (5,000 ops, 20 threads)")
    void testLocalPerformanceBenchmark() throws Exception {
        UUID consumerId = createTestConsumerId();
        int totalOps = 5000;
        int threadCount = 20;
        // Large capacity so most are allowed
        RateLimitPlan benchmarkPlan = new RateLimitPlan("BENCHMARK", 100000, 100000, 60);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalOps);

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);
        List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>(totalOps));

        long benchmarkStartNanos = System.nanoTime();

        for (int i = 0; i < totalOps; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long opStart = System.nanoTime();
                    RateLimitDecision decision = rateLimiter.consume(consumerId, benchmarkPlan, 1);
                    long opDuration = System.nanoTime() - opStart;
                    latenciesNanos.add(opDuration);

                    if (decision.allowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        deniedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Benchmark error", e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        long totalDurationNanos = System.nanoTime() - benchmarkStartNanos;
        executor.shutdown();

        assertThat(finished).isTrue();

        double totalDurationSeconds = totalDurationNanos / 1_000_000_000.0;
        double throughputOpsPerSec = totalOps / totalDurationSeconds;

        List<Long> sortedLatencies = new ArrayList<>(latenciesNanos);
        Collections.sort(sortedLatencies);

        double avgLatencyMs = sortedLatencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double p50LatencyMs = sortedLatencies.get((int) (sortedLatencies.size() * 0.50)) / 1_000_000.0;
        double p95LatencyMs = sortedLatencies.get((int) (sortedLatencies.size() * 0.95)) / 1_000_000.0;
        double p99LatencyMs = sortedLatencies.get((int) (sortedLatencies.size() * 0.99)) / 1_000_000.0;
        double maxLatencyMs = sortedLatencies.get(sortedLatencies.size() - 1) / 1_000_000.0;

        System.out.println("=================================================================");
        System.out.println("       GATEKEEPER TOKEN BUCKET BENCHMARK RESULTS                ");
        System.out.println("=================================================================");
        System.out.printf(" Total Operations   : %,d%n", totalOps);
        System.out.printf(" Allowed Operations : %,d%n", allowedCount.get());
        System.out.printf(" Denied Operations  : %,d%n", deniedCount.get());
        System.out.printf(" Threads Concurrent : %d%n", threadCount);
        System.out.printf(" Total Time Elapsed : %.3f s%n", totalDurationSeconds);
        System.out.printf(" Throughput         : %,.1f ops/sec%n", throughputOpsPerSec);
        System.out.printf(" Average Latency    : %.3f ms%n", avgLatencyMs);
        System.out.printf(" 50th Percentile    : %.3f ms%n", p50LatencyMs);
        System.out.printf(" 95th Percentile    : %.3f ms%n", p95LatencyMs);
        System.out.printf(" 99th Percentile    : %.3f ms%n", p99LatencyMs);
        System.out.printf(" Max Latency        : %.3f ms%n", maxLatencyMs);
        System.out.println("=================================================================");

        assertThat(allowedCount.get()).isEqualTo(totalOps);
        assertThat(throughputOpsPerSec).isGreaterThan(500.0);
    }
}
