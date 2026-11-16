package com.gatekeeper.analytics.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.analytics.dto.OutcomeCategory;
import com.gatekeeper.analytics.dto.UsageEventRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageEventPublisherTest {

    @Test
    @DisplayName("Publisher queues and publishes events asynchronously without blocking")
    void testPublishAsyncSuccess() throws InterruptedException {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("1000-0"));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        UsageEventPublisher publisher = new UsageEventPublisher(
            redisTemplate,
            objectMapper,
            "gatekeeper:stream:test_events",
            50000,
            100,
            2,
            4
        );

        UsageEventRecord record = new UsageEventRecord(
            UUID.randomUUID(),
            Instant.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test@example.com",
            "gk_live_11223344",
            "/api/v1/gateway/demo",
            "GET",
            200,
            10,
            false,
            null,
            OutcomeCategory.ALLOWED,
            "FREE",
            "req-001",
            "127.0.0.1",
            "JUnit"
        );

        publisher.publishAsync(record);

        // Allow async worker to process
        TimeUnit.MILLISECONDS.sleep(200);

        assertThat(publisher.getSubmittedCount()).isEqualTo(1);
        assertThat(publisher.getPublishedCount()).isEqualTo(1);
        assertThat(publisher.getDroppedCount()).isEqualTo(0);
        assertThat(publisher.getFailedCount()).isEqualTo(0);

        publisher.shutdown();
    }

    @Test
    @DisplayName("Publisher drops events and increments dropped counter when queue is saturated")
    void testQueueSaturationBehavior() throws InterruptedException {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);

        CountDownLatch blocker = new CountDownLatch(1);
        when(streamOps.add(any(MapRecord.class))).thenAnswer(invocation -> {
            blocker.await(2, TimeUnit.SECONDS);
            return RecordId.of("1000-0");
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        // Create publisher with tiny queue of 2 and 1 core/max thread
        UsageEventPublisher publisher = new UsageEventPublisher(
            redisTemplate,
            objectMapper,
            "gatekeeper:stream:test_events",
            50000,
            2,
            1,
            1
        );

        // Submit 10 events rapidly to saturate the queue of 2
        for (int i = 0; i < 10; i++) {
            publisher.publishAsync(new UsageEventRecord(
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "test@example.com",
                "gk_live_11223344",
                "/api/v1/gateway/demo",
                "GET",
                200,
                10,
                false,
                null,
                OutcomeCategory.ALLOWED,
                "FREE",
                "req-" + i,
                "127.0.0.1",
                "JUnit"
            ));
        }

        // Some events must be dropped due to queue saturation
        assertThat(publisher.getDroppedCount()).isGreaterThan(0);

        blocker.countDown();
        publisher.shutdown();
    }

    @Test
    @DisplayName("Publisher ignores null event without throwing")
    void testPublishNullEvent() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();

        UsageEventPublisher publisher = new UsageEventPublisher(
            redisTemplate,
            objectMapper,
            "gatekeeper:stream:test_events",
            50000,
            10,
            1,
            1
        );

        publisher.publishAsync(null);
        assertThat(publisher.getSubmittedCount()).isEqualTo(0);

        publisher.shutdown();
    }
}
