package com.gatekeeper.analytics.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.analytics.dto.UsageEventRecord;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous, bounded publisher for gateway usage telemetry into Redis Streams.
 *
 * <p>Architectural Guarantees:
 * <ul>
 *   <li><b>Zero Gateway Hot-Path Blocking:</b> Submissions to the bounded in-memory queue
 *       are non-blocking. If the queue is saturated, events are dropped with explicit counters
 *       and throttled warnings, preserving fail-closed gateway performance.</li>
 *   <li><b>Bounded Memory and Threads:</b> Thread pool with controlled core/max threads and
 *       an ArrayBlockingQueue ensures predictable memory consumption under extreme load.</li>
 *   <li><b>Graceful Shutdown:</b> Awaits pending queue flushes on application termination.</li>
 *   <li><b>Fail-Safe:</b> Exceptions in Redis do not crash gateway request handling.</li>
 * </ul>
 */
@Component
@Profile("!foundation")
public class UsageEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(UsageEventPublisher.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final String streamKey;
    private final long maxStreamLen;

    private final ThreadPoolExecutor executor;
    private final AtomicLong submittedCount = new AtomicLong(0);
    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong droppedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);
    private final AtomicLong lastDropLogTime = new AtomicLong(0);

    public UsageEventPublisher(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            @Value("${gatekeeper.analytics.stream.key:gatekeeper:stream:usage_events}") String streamKey,
            @Value("${gatekeeper.analytics.stream.max-len:50000}") long maxStreamLen,
            @Value("${gatekeeper.analytics.publisher.queue-capacity:10000}") int queueCapacity,
            @Value("${gatekeeper.analytics.publisher.core-threads:2}") int coreThreads,
            @Value("${gatekeeper.analytics.publisher.max-threads:4}") int maxThreads) {

        this.stringRedisTemplate = Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.streamKey = streamKey;
        this.maxStreamLen = maxStreamLen;

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadNum = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "usage-pub-" + threadNum.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };

        RejectedExecutionHandler rejectionHandler = (runnable, exec) -> {
            long drops = droppedCount.incrementAndGet();
            long now = System.currentTimeMillis();
            long last = lastDropLogTime.get();
            if (now - last > 5000 && lastDropLogTime.compareAndSet(last, now)) {
                log.warn("Telemetry submission queue saturated! Dropped events count: {}", drops);
            }
        };

        this.executor = new ThreadPoolExecutor(
            coreThreads,
            maxThreads,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            threadFactory,
            rejectionHandler
        );

        log.info("Initialized UsageEventPublisher: stream='{}', queueCapacity={}, threads={}-{}",
            streamKey, queueCapacity, coreThreads, maxThreads);
    }

    /**
     * Submits a usage event for asynchronous publishing. Never blocks the calling thread.
     */
    public void publishAsync(UsageEventRecord record) {
        if (record == null) {
            return;
        }

        submittedCount.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    String json = objectMapper.writeValueAsString(record);
                    MapRecord<String, String, String> streamRecord = StreamRecords.string(
                        Map.of(
                            "id", record.id().toString(),
                            "payload", json
                        )
                    ).withStreamKey(streamKey);

                    RecordId recordId = stringRedisTemplate.opsForStream().add(streamRecord);
                    if (recordId != null) {
                        publishedCount.incrementAndGet();
                    }
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize usage event {}: {}", record.id(), e.getMessage());
                    failedCount.incrementAndGet();
                } catch (Exception e) {
                    log.warn("Failed to publish telemetry event {} to Redis stream '{}': {}",
                        record.id(), streamKey, e.getMessage());
                    failedCount.incrementAndGet();
                }
            });
        } catch (Exception e) {
            droppedCount.incrementAndGet();
            log.warn("Failed to queue telemetry event {}: {}", record.id(), e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down UsageEventPublisher (submitted={}, published={}, dropped={}, failed={})",
            submittedCount.get(), publishedCount.get(), droppedCount.get(), failedCount.get());
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    // Telemetry operational metrics
    public long getSubmittedCount() { return submittedCount.get(); }
    public long getPublishedCount() { return publishedCount.get(); }
    public long getDroppedCount() { return droppedCount.get(); }
    public long getFailedCount() { return failedCount.get(); }
    public int getQueueSize() { return executor.getQueue().size(); }
}
