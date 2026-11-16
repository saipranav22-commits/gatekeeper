package com.gatekeeper.analytics.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.analytics.dto.OutcomeCategory;
import com.gatekeeper.analytics.dto.UsageEventRecord;
import com.gatekeeper.analytics.entity.DeadLetterUsageEvent;
import com.gatekeeper.analytics.entity.UsageEvent;
import com.gatekeeper.analytics.repository.DeadLetterRepository;
import com.gatekeeper.analytics.repository.UsageEventRepository;
import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.apikey.entity.ApiKeyStatus;
import com.gatekeeper.apikey.repository.ApiKeyRepository;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.entity.ConsumerStatus;
import com.gatekeeper.consumer.repository.ApiConsumerRepository;
import com.gatekeeper.plan.entity.SubscriptionPlan;
import com.gatekeeper.plan.repository.SubscriptionPlanRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
class UsageEventPipelineIntegrationTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UsageEventBatchConsumer batchConsumer;

    @Autowired
    private UsageEventPublisher eventPublisher;

    @Autowired
    private UsageEventRepository usageEventRepository;

    @Autowired
    private DeadLetterRepository deadLetterRepository;

    @Autowired
    private ApiConsumerRepository consumerRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private SubscriptionPlanRepository planRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private SubscriptionPlan testPlan;
    private ApiConsumer testConsumer;
    private ApiKey testKey;
    private final List<UUID> createdEventIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        testPlan = planRepository.findAll().stream()
            .filter(p -> p.getName().equalsIgnoreCase("FREE"))
            .findFirst()
            .orElseGet(() -> planRepository.findAll().get(0));

        testConsumer = new ApiConsumer(
            "Telemetry-Pipeline-Test-" + System.nanoTime(),
            "pipeline-" + System.nanoTime() + "@example.com",
            testPlan
        );
        testConsumer = consumerRepository.save(testConsumer);

        testKey = new ApiKey(
            testConsumer,
            "Pipeline-Key-" + System.nanoTime(),
            "gk_live_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
            "mockDigestValueForTestingPipeline1234567890"
        );
        testKey = apiKeyRepository.save(testKey);
    }

    @AfterEach
    void tearDown() {
        for (UUID eventId : createdEventIds) {
            usageEventRepository.deleteById(eventId);
        }
        if (testKey != null) {
            apiKeyRepository.deleteById(testKey.getId());
        }
        if (testConsumer != null) {
            consumerRepository.deleteById(testConsumer.getId());
        }
    }

    @Test
    @DisplayName("BatchConsumer persists valid stream events and updates api_keys.last_used_at")
    void testProcessRecordBatchSuccess() throws Exception {
        UUID eventId = UUID.randomUUID();
        createdEventIds.add(eventId);
        Instant eventTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        UsageEventRecord record = new UsageEventRecord(
            eventId,
            eventTime,
            testConsumer.getId(),
            testKey.getId(),
            testConsumer.getEmail(),
            testKey.getKeyPrefix(),
            "/api/v1/gateway/demo",
            "GET",
            200,
            15,
            false,
            null,
            OutcomeCategory.ALLOWED,
            testPlan.getName(),
            "corr-test-01",
            "127.0.0.1",
            "IntegrationTest"
        );

        String json = objectMapper.writeValueAsString(record);
        MapRecord<String, String, String> mapRecord = MapRecord.create(
            "gatekeeper:stream:usage_events",
            Map.of("id", eventId.toString(), "payload", json)
        ).withId(RecordId.of("9999999-1"));

        int persisted = batchConsumer.processRecordBatch(List.of(mapRecord));
        assertThat(persisted).isEqualTo(1);

        // Verify persisted event in PostgreSQL
        UsageEvent saved = usageEventRepository.findById(eventId).orElseThrow();
        assertThat(saved.getEndpointPath()).isEqualTo("/api/v1/gateway/demo");
        assertThat(saved.getHttpStatus()).isEqualTo(200);
        assertThat(saved.getOutcomeCategory()).isEqualTo("ALLOWED");
        assertThat(saved.getCorrelationId()).isEqualTo("corr-test-01");

        // Verify monotonic last_used_at update on the API key
        ApiKey keyAfter = apiKeyRepository.findById(testKey.getId()).orElseThrow();
        assertThat(keyAfter.getLastUsedAt()).isNotNull();
        assertThat(keyAfter.getLastUsedAt()).isEqualTo(eventTime);
    }

    @Test
    @DisplayName("Idempotent replay: reprocessing duplicate event ID does not fail or duplicate rows")
    void testIdempotentEventReplay() throws Exception {
        UUID eventId = UUID.randomUUID();
        createdEventIds.add(eventId);

        UsageEventRecord record = new UsageEventRecord(
            eventId,
            Instant.now(),
            testConsumer.getId(),
            testKey.getId(),
            testConsumer.getEmail(),
            testKey.getKeyPrefix(),
            "/api/v1/gateway/demo",
            "GET",
            200,
            12,
            false,
            null,
            OutcomeCategory.ALLOWED,
            testPlan.getName(),
            "corr-idem-1",
            "127.0.0.1",
            "JUnit"
        );

        String json = objectMapper.writeValueAsString(record);
        MapRecord<String, String, String> mapRecord = MapRecord.create(
            "gatekeeper:stream:usage_events",
            Map.of("id", eventId.toString(), "payload", json)
        ).withId(RecordId.of("9999999-2"));

        // First pass
        int firstPass = batchConsumer.processRecordBatch(List.of(mapRecord));
        assertThat(firstPass).isEqualTo(1);

        // Second pass with identical event ID (e.g. replayed batch after worker crash)
        int secondPass = batchConsumer.processRecordBatch(List.of(mapRecord));
        assertThat(secondPass).isEqualTo(1);

        // Database count must still be exactly 1
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM usage_events WHERE id = ?",
            Long.class,
            eventId
        );
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("Monotonic update: older event timestamp never overwrites newer last_used_at")
    void testMonotonicLastUsedTimestamp() throws Exception {
        Instant newerTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant olderTime = newerTime.minus(10, ChronoUnit.MINUTES);

        UUID event1Id = UUID.randomUUID();
        UUID event2Id = UUID.randomUUID();
        createdEventIds.add(event1Id);
        createdEventIds.add(event2Id);

        UsageEventRecord newerRecord = new UsageEventRecord(
            event1Id, newerTime, testConsumer.getId(), testKey.getId(),
            testConsumer.getEmail(), testKey.getKeyPrefix(), "/api/v1/gateway/demo",
            "GET", 200, 10, false, null, OutcomeCategory.ALLOWED, testPlan.getName(), "c1", "127.0.0.1", "JUnit"
        );

        UsageEventRecord olderRecord = new UsageEventRecord(
            event2Id, olderTime, testConsumer.getId(), testKey.getId(),
            testConsumer.getEmail(), testKey.getKeyPrefix(), "/api/v1/gateway/demo",
            "GET", 200, 10, false, null, OutcomeCategory.ALLOWED, testPlan.getName(), "c2", "127.0.0.1", "JUnit"
        );

        // Process newer first
        MapRecord<String, String, String> rec1 = MapRecord.create(
            "gatekeeper:stream:usage_events",
            Map.of("id", event1Id.toString(), "payload", objectMapper.writeValueAsString(newerRecord))
        ).withId(RecordId.of("9999999-3"));
        batchConsumer.processRecordBatch(List.of(rec1));

        ApiKey keyAfterFirst = apiKeyRepository.findById(testKey.getId()).orElseThrow();
        assertThat(keyAfterFirst.getLastUsedAt()).isEqualTo(newerTime);

        // Process older event afterwards (e.g. out-of-order delivery)
        MapRecord<String, String, String> rec2 = MapRecord.create(
            "gatekeeper:stream:usage_events",
            Map.of("id", event2Id.toString(), "payload", objectMapper.writeValueAsString(olderRecord))
        ).withId(RecordId.of("9999999-4"));
        batchConsumer.processRecordBatch(List.of(rec2));

        // last_used_at must remain the newer timestamp!
        ApiKey keyAfterSecond = apiKeyRepository.findById(testKey.getId()).orElseThrow();
        assertThat(keyAfterSecond.getLastUsedAt()).isEqualTo(newerTime);
    }

    @Test
    @DisplayName("Poison message with malformed JSON routes to DeadLetterUsageEvent")
    void testPoisonMessageRoutingToDeadLetter() {
        MapRecord<String, String, String> poisonRecord = MapRecord.create(
            "gatekeeper:stream:usage_events",
            Map.of("id", "poison-1", "payload", "MALFORMED_JSON_NOT_AN_OBJECT{{{")
        ).withId(RecordId.of("9999999-5"));

        int persisted = batchConsumer.processRecordBatch(List.of(poisonRecord));
        assertThat(persisted).isEqualTo(0);

        List<DeadLetterUsageEvent> dlqEvents = deadLetterRepository.findAll();
        boolean found = dlqEvents.stream()
            .anyMatch(d -> d.getStreamMessageId().equals("9999999-5") && d.getPayload().contains("MALFORMED_JSON"));
        assertThat(found).isTrue();
    }
}
