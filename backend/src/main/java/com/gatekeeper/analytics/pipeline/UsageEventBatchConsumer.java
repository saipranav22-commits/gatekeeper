package com.gatekeeper.analytics.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.analytics.dto.UsageEventRecord;
import com.gatekeeper.analytics.entity.DeadLetterUsageEvent;
import com.gatekeeper.analytics.repository.DeadLetterRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Distributed batch consumer for persisting Redis Streams usage events into PostgreSQL.
 *
 * <p>Key Guarantees:
 * <ul>
 *   <li><b>Idempotent Persistence:</b> Uses PostgreSQL {@code ON CONFLICT (id) DO NOTHING} to prevent duplicates.</li>
 *   <li><b>Post-Commit Acknowledgments:</b> Redis {@code XACK} is invoked ONLY after the database transaction commits.</li>
 *   <li><b>Monotonic Last-Used Tracking:</b> Updates {@code api_keys.last_used_at} asynchronously using GREATEST semantics.</li>
 *   <li><b>Poison-Message / DLQ Safety:</b> Corrupt payloads or messages exceeding max retries route to DLQ.</li>
 *   <li><b>Crash Recovery:</b> Periodically processes pending messages from worker crashes.</li>
 * </ul>
 */
@Component
@Profile("!foundation")
public class UsageEventBatchConsumer {

    private static final Logger log = LoggerFactory.getLogger(UsageEventBatchConsumer.class);

    private static final String INSERT_SQL = """
        INSERT INTO usage_events (
            id, timestamp, consumer_id, api_key_id, consumer_email_snapshot,
            key_prefix_snapshot, endpoint_path, http_method, http_status,
            latency_ms, rate_limited, rejection_reason, ip_address, user_agent,
            outcome_category, rate_limit_plan, correlation_id,
            route_id, upstream_status, upstream_latency_ms,
            circuit_state, retry_count, bulkhead_rejected
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (id) DO NOTHING
        """;

    private static final String UPDATE_KEY_LAST_USED_SQL = """
        UPDATE api_keys
        SET last_used_at = ?
        WHERE id = ?
          AND (last_used_at IS NULL OR last_used_at < ?)
          AND is_deleted = false
        """;

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final DeadLetterRepository deadLetterRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private final String streamKey;
    private final String groupName;
    private final String consumerName;
    private final int batchSize;
    private final int maxRetries;
    private final long maxStreamLen;

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicLong persistedCount = new AtomicLong(0);
    private final AtomicLong acknowledgedCount = new AtomicLong(0);
    private final AtomicLong deadLetterCount = new AtomicLong(0);

    public UsageEventBatchConsumer(
            StringRedisTemplate stringRedisTemplate,
            JdbcTemplate jdbcTemplate,
            DeadLetterRepository deadLetterRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${gatekeeper.analytics.stream.key:gatekeeper:stream:usage_events}") String streamKey,
            @Value("${gatekeeper.analytics.stream.group:gatekeeper-usage-group}") String groupName,
            @Value("${gatekeeper.analytics.stream.consumer-name:worker-1}") String consumerName,
            @Value("${gatekeeper.analytics.consumer.batch-size:200}") int batchSize,
            @Value("${gatekeeper.analytics.consumer.max-retries:3}") int maxRetries,
            @Value("${gatekeeper.analytics.stream.max-len:50000}") long maxStreamLen) {

        this.stringRedisTemplate = Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.deadLetterRepository = Objects.requireNonNull(deadLetterRepository, "deadLetterRepository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"));

        this.streamKey = streamKey;
        this.groupName = groupName;
        this.consumerName = consumerName;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.maxStreamLen = maxStreamLen;
    }

    @PostConstruct
    public void initConsumerGroup() {
        try {
            stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                try {
                    connection.streamCommands().xGroupCreate(
                        streamKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        groupName,
                        ReadOffset.latest(),
                        true
                    );
                    log.info("Initialized Redis Stream consumer group '{}' on stream '{}' (MKSTREAM)", groupName, streamKey);
                } catch (Exception ex) {
                    if (ex.getMessage() != null && ex.getMessage().contains("BUSYGROUP")) {
                        log.info("Redis Stream consumer group '{}' already exists on stream '{}'", groupName, streamKey);
                    } else {
                        log.warn("Could not create stream consumer group '{}': {}", groupName, ex.getMessage());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Non-fatal exception initializing consumer group '{}': {}", groupName, e.getMessage());
        }
    }

    /**
     * Periodically polls new events from Redis Streams and persists them in PostgreSQL.
     */
    @Scheduled(fixedDelayString = "${gatekeeper.analytics.consumer.poll-interval-ms:1000}")
    public void pollAndPersistBatch() {
        if (!isProcessing.compareAndSet(false, true)) {
            return; // Skip cycle if previous batch is still processing
        }

        try {
            List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                Consumer.from(groupName, consumerName),
                StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(500)),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
            );

            if (records != null && !records.isEmpty()) {
                processRecordBatch(records);
            }
        } catch (Exception e) {
            log.warn("Error reading from Redis Stream '{}': {}", streamKey, e.getMessage());
        } finally {
            isProcessing.set(false);
        }
    }

    /**
     * Crash recovery: periodically checks pending entries list (PEL) for unacknowledged messages.
     */
    @Scheduled(fixedDelayString = "${gatekeeper.analytics.consumer.recovery-interval-ms:30000}", initialDelay = 10000)
    public void recoverPendingMessages() {
        try {
            PendingMessages pendingMessages = stringRedisTemplate.opsForStream().pending(
                streamKey,
                groupName,
                org.springframework.data.domain.Range.unbounded(),
                batchSize
            );

            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return;
            }

            List<PendingMessage> poisonMessages = new ArrayList<>();
            List<RecordId> recoverableIds = new ArrayList<>();

            for (PendingMessage pm : pendingMessages) {
                if (pm.getTotalDeliveryCount() > maxRetries) {
                    poisonMessages.add(pm);
                } else if (pm.getElapsedTimeSinceLastDelivery().toSeconds() > 15) {
                    recoverableIds.add(pm.getId());
                }
            }

            // Route expired poison messages to dead-letter queue
            for (PendingMessage pm : poisonMessages) {
                RecordId poisonId = pm.getId();
                try {
                    log.error("Message {} exceeded max retries ({}), routing to dead-letter queue", poisonId, maxRetries);
                    deadLetterRepository.save(new DeadLetterUsageEvent(
                        poisonId.getValue(),
                        "Pending message recovery retry limit exceeded",
                        "Max retries exceeded (" + maxRetries + ")",
                        (int) pm.getTotalDeliveryCount()
                    ));
                    stringRedisTemplate.opsForStream().acknowledge(streamKey, groupName, poisonId);
                    deadLetterCount.incrementAndGet();
                } catch (Exception ex) {
                    log.error("Failed to route poison message {} to DLQ: {}", poisonId, ex.getMessage());
                }
            }

            // Reprocess recoverable pending messages
            if (!recoverableIds.isEmpty()) {
                List<MapRecord<String, Object, Object>> pendingRecords = stringRedisTemplate.opsForStream().read(
                    Consumer.from(groupName, consumerName),
                    StreamReadOptions.empty().count(recoverableIds.size()),
                    StreamOffset.create(streamKey, ReadOffset.from("0"))
                );

                if (pendingRecords != null && !pendingRecords.isEmpty()) {
                    log.info("Recovering {} pending telemetry messages after worker timeout", pendingRecords.size());
                    processRecordBatch(pendingRecords);
                }
            }

        } catch (Exception e) {
            log.warn("Non-fatal exception during pending message recovery: {}", e.getMessage());
        }
    }

    /**
     * Periodic stream trimming to enforce retention limits.
     */
    @Scheduled(fixedDelayString = "${gatekeeper.analytics.stream.trim-interval-ms:60000}", initialDelay = 30000)
    public void trimStream() {
        try {
            Long trimmed = stringRedisTemplate.opsForStream().trim(streamKey, maxStreamLen, true);
            if (trimmed != null && trimmed > 0) {
                log.debug("Trimmed {} expired records from stream '{}'", trimmed, streamKey);
            }
        } catch (Exception e) {
            log.debug("Non-fatal stream trimming exception: {}", e.getMessage());
        }
    }

    /**
     * Core batch processor: parses records, performs transactional batch persistence,
     * updates last-used timestamps, and acknowledges records ONLY after successful commit.
     */
    public int processRecordBatch(List<? extends MapRecord<String, ?, ?>> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }

        List<UsageEventRecord> validEvents = new ArrayList<>();
        List<RecordId> ackIds = new ArrayList<>();
        Map<UUID, Instant> keyLatestTimes = new HashMap<>();

        for (MapRecord<String, ?, ?> record : records) {
            RecordId recId = record.getId();
            Map<?, ?> value = record.getValue();
            String payload = value.get("payload") != null ? value.get("payload").toString() : null;

            if (payload == null || payload.isBlank()) {
                // Poison message: route to DLQ immediately and ACK
                deadLetterRepository.save(new DeadLetterUsageEvent(
                    recId.getValue(),
                    String.valueOf(value),
                    "Empty or null payload in stream record",
                    1
                ));
                ackIds.add(recId);
                deadLetterCount.incrementAndGet();
                continue;
            }

            try {
                UsageEventRecord eventRecord = objectMapper.readValue(payload, UsageEventRecord.class);
                validEvents.add(eventRecord);
                ackIds.add(recId);

                // Aggregate latest timestamp per API key
                if (eventRecord.apiKeyId() != null && eventRecord.timestamp() != null) {
                    keyLatestTimes.merge(
                        eventRecord.apiKeyId(),
                        eventRecord.timestamp(),
                        (existing, candidate) -> candidate.isAfter(existing) ? candidate : existing
                    );
                }
            } catch (Exception ex) {
                log.error("Malformed payload in stream record {}: {}", recId, ex.getMessage());
                deadLetterRepository.save(new DeadLetterUsageEvent(
                    recId.getValue(),
                    payload,
                    "JSON deserialization failure: " + ex.getMessage(),
                    1
                ));
                ackIds.add(recId);
                deadLetterCount.incrementAndGet();
            }
        }

        if (validEvents.isEmpty()) {
            if (!ackIds.isEmpty()) {
                stringRedisTemplate.opsForStream().acknowledge(streamKey, groupName, ackIds.toArray(new RecordId[0]));
            }
            return 0;
        }

        // Execute transactional batch persistence
        try {
            Set<UUID> validConsumers = resolveExistingConsumerIds(validEvents);
            Set<UUID> validKeys = resolveExistingKeyIds(validEvents);

            transactionTemplate.executeWithoutResult(status -> {
                // 1. Batch insert into usage_events with safe FK handling
                jdbcTemplate.batchUpdate(INSERT_SQL, validEvents, validEvents.size(), (ps, ev) -> {
                    ps.setObject(1, ev.id());
                    ps.setTimestamp(2, Timestamp.from(ev.timestamp() != null ? ev.timestamp() : Instant.now()));

                    if (ev.consumerId() != null && validConsumers.contains(ev.consumerId())) {
                        ps.setObject(3, ev.consumerId());
                    } else {
                        ps.setNull(3, Types.OTHER);
                    }

                    if (ev.apiKeyId() != null && validKeys.contains(ev.apiKeyId())) {
                        ps.setObject(4, ev.apiKeyId());
                    } else {
                        ps.setNull(4, Types.OTHER);
                    }

                    ps.setString(5, ev.consumerEmailSnapshot());
                    ps.setString(6, ev.keyPrefixSnapshot());
                    ps.setString(7, ev.endpointPath() != null ? ev.endpointPath() : "/");
                    ps.setString(8, ev.httpMethod() != null ? ev.httpMethod() : "GET");
                    ps.setInt(9, ev.httpStatus());
                    ps.setInt(10, ev.latencyMs());
                    ps.setBoolean(11, ev.rateLimited());
                    ps.setString(12, ev.rejectionReason());
                    ps.setString(13, ev.ipAddress());
                    ps.setString(14, ev.userAgent());
                    ps.setString(15, ev.outcomeCategory() != null ? ev.outcomeCategory().name() : "ALLOWED");
                    ps.setString(16, ev.rateLimitPlan());
                    ps.setString(17, ev.correlationId());
                    ps.setString(18, ev.routeId());
                    if (ev.upstreamStatus() != null) {
                        ps.setInt(19, ev.upstreamStatus());
                    } else {
                        ps.setNull(19, Types.INTEGER);
                    }
                    if (ev.upstreamLatencyMs() != null) {
                        ps.setInt(20, ev.upstreamLatencyMs());
                    } else {
                        ps.setNull(20, Types.INTEGER);
                    }
                    ps.setString(21, ev.circuitState() != null ? ev.circuitState() : "CLOSED");
                    ps.setInt(22, ev.retryCount() != null ? ev.retryCount() : 0);
                    ps.setBoolean(23, ev.bulkheadRejected() != null ? ev.bulkheadRejected() : false);
                });

                // 2. Monotonic update of api_keys.last_used_at for existing keys
                for (Map.Entry<UUID, Instant> entry : keyLatestTimes.entrySet()) {
                    if (validKeys.contains(entry.getKey())) {
                        Timestamp ts = Timestamp.from(entry.getValue());
                        jdbcTemplate.update(UPDATE_KEY_LAST_USED_SQL, ts, entry.getKey(), ts);
                    }
                }
            });

            // 3. Acknowledge messages in Redis ONLY after transaction commits
            if (!ackIds.isEmpty()) {
                stringRedisTemplate.opsForStream().acknowledge(streamKey, groupName, ackIds.toArray(new RecordId[0]));
                acknowledgedCount.addAndGet(ackIds.size());
            }

            persistedCount.addAndGet(validEvents.size());
            return validEvents.size();

        } catch (Exception e) {
            log.error("Failed to commit usage event batch to PostgreSQL ({} events will be retried): {}",
                validEvents.size(), e.getMessage());
            // DO NOT ACKNOWLEDGE: messages remain pending in stream for next retry
            throw e;
        }
    }

    private java.util.Set<UUID> resolveExistingConsumerIds(List<UsageEventRecord> events) {
        java.util.Set<UUID> ids = events.stream()
            .map(UsageEventRecord::consumerId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        String inClause = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<UUID> existing = jdbcTemplate.query(
            "SELECT id FROM api_consumers WHERE id IN (" + inClause + ")",
            (rs, rowNum) -> (UUID) rs.getObject("id"),
            ids.toArray()
        );
        return new java.util.HashSet<>(existing);
    }

    private java.util.Set<UUID> resolveExistingKeyIds(List<UsageEventRecord> events) {
        java.util.Set<UUID> ids = events.stream()
            .map(UsageEventRecord::apiKeyId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        String inClause = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<UUID> existing = jdbcTemplate.query(
            "SELECT id FROM api_keys WHERE id IN (" + inClause + ")",
            (rs, rowNum) -> (UUID) rs.getObject("id"),
            ids.toArray()
        );
        return new java.util.HashSet<>(existing);
    }

    public long getPersistedCount() { return persistedCount.get(); }
    public long getAcknowledgedCount() { return acknowledgedCount.get(); }
    public long getDeadLetterCount() { return deadLetterCount.get(); }
}
