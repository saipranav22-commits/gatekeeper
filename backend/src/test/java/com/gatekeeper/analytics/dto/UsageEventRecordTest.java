package com.gatekeeper.analytics.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UsageEventRecordTest {

    @Test
    @DisplayName("UsageEventRecord retains all fields immutably")
    void testUsageEventRecordCreation() {
        UUID eventId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        Instant now = Instant.now();

        UsageEventRecord record = new UsageEventRecord(
            eventId,
            now,
            consumerId,
            apiKeyId,
            "test@example.com",
            "gk_live_12345678",
            "/api/v1/gateway/demo",
            "GET",
            200,
            12,
            false,
            null,
            OutcomeCategory.ALLOWED,
            "FREE",
            "corr-12345",
            "127.0.0.1",
            "Mozilla/5.0"
        );

        assertThat(record.id()).isEqualTo(eventId);
        assertThat(record.timestamp()).isEqualTo(now);
        assertThat(record.consumerId()).isEqualTo(consumerId);
        assertThat(record.apiKeyId()).isEqualTo(apiKeyId);
        assertThat(record.consumerEmailSnapshot()).isEqualTo("test@example.com");
        assertThat(record.keyPrefixSnapshot()).isEqualTo("gk_live_12345678");
        assertThat(record.endpointPath()).isEqualTo("/api/v1/gateway/demo");
        assertThat(record.httpMethod()).isEqualTo("GET");
        assertThat(record.httpStatus()).isEqualTo(200);
        assertThat(record.latencyMs()).isEqualTo(12);
        assertThat(record.rateLimited()).isFalse();
        assertThat(record.rejectionReason()).isNull();
        assertThat(record.outcomeCategory()).isEqualTo(OutcomeCategory.ALLOWED);
        assertThat(record.rateLimitPlan()).isEqualTo("FREE");
        assertThat(record.correlationId()).isEqualTo("corr-12345");
        assertThat(record.ipAddress()).isEqualTo("127.0.0.1");
        assertThat(record.userAgent()).isEqualTo("Mozilla/5.0");
        assertThat(record.routeId()).isNull();
        assertThat(record.upstreamStatus()).isNull();
        assertThat(record.upstreamLatencyMs()).isNull();
    }

    @Test
    @DisplayName("UsageEventRecord retains routing and upstream fields")
    void testUsageEventRecordWithRoutingFields() {
        UUID eventId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        Instant now = Instant.now();

        UsageEventRecord record = new UsageEventRecord(
            eventId,
            now,
            consumerId,
            apiKeyId,
            "test@example.com",
            "gk_live_12345678",
            "/api/v1/proxy/orders/orders",
            "POST",
            201,
            25,
            false,
            null,
            OutcomeCategory.ALLOWED,
            "PRO",
            "corr-67890",
            "127.0.0.1",
            "Gatekeeper-Client/1.0",
            "orders-service-route",
            201,
            18
        );

        assertThat(record.routeId()).isEqualTo("orders-service-route");
        assertThat(record.upstreamStatus()).isEqualTo(201);
        assertThat(record.upstreamLatencyMs()).isEqualTo(18);
        assertThat(record.circuitState()).isEqualTo("CLOSED");
        assertThat(record.retryCount()).isEqualTo(0);
        assertThat(record.bulkheadRejected()).isFalse();
    }

    @Test
    @DisplayName("UsageEventRecord retains resilience metrics fields")
    void testUsageEventRecordWithResilienceFields() {
        UUID eventId = UUID.randomUUID();
        UsageEventRecord record = new UsageEventRecord(
            eventId,
            Instant.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test@example.com",
            "gk_live_12345678",
            "/api/v1/proxy/orders",
            "GET",
            503,
            45,
            false,
            "CIRCUIT_OPEN",
            OutcomeCategory.SERVICE_UNAVAILABLE,
            "PRO",
            "corr-11223",
            "127.0.0.1",
            "Gatekeeper-Client/1.0",
            "orders-service-v1",
            null,
            null,
            "OPEN",
            2,
            true
        );

        assertThat(record.circuitState()).isEqualTo("OPEN");
        assertThat(record.retryCount()).isEqualTo(2);
        assertThat(record.bulkheadRejected()).isTrue();
        assertThat(record.rejectionReason()).isEqualTo("CIRCUIT_OPEN");
    }

    @Test
    @DisplayName("OutcomeCategory covers all expected gateway states")
    void testOutcomeCategoryValues() {
        assertThat(OutcomeCategory.valueOf("ALLOWED")).isEqualTo(OutcomeCategory.ALLOWED);
        assertThat(OutcomeCategory.valueOf("RATE_LIMITED")).isEqualTo(OutcomeCategory.RATE_LIMITED);
        assertThat(OutcomeCategory.valueOf("SCOPE_DENIED")).isEqualTo(OutcomeCategory.SCOPE_DENIED);
        assertThat(OutcomeCategory.valueOf("SERVICE_UNAVAILABLE")).isEqualTo(OutcomeCategory.SERVICE_UNAVAILABLE);
        assertThat(OutcomeCategory.valueOf("UNAUTHORIZED")).isEqualTo(OutcomeCategory.UNAUTHORIZED);
    }
}
