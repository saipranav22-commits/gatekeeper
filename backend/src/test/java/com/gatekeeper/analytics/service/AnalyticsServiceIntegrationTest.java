package com.gatekeeper.analytics.service;

import com.gatekeeper.analytics.dto.AnalyticsOverviewDto;
import com.gatekeeper.analytics.dto.ConsumerAnalyticsDto;
import com.gatekeeper.analytics.dto.TimeSeriesPointDto;
import com.gatekeeper.analytics.dto.TopConsumerDto;
import com.gatekeeper.analytics.dto.UsageEventDetailDto;
import com.gatekeeper.analytics.entity.UsageEvent;
import com.gatekeeper.analytics.repository.UsageEventRepository;
import com.gatekeeper.common.exception.ResourceNotFoundException;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.repository.ApiConsumerRepository;
import com.gatekeeper.plan.entity.SubscriptionPlan;
import com.gatekeeper.plan.repository.SubscriptionPlanRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("local")
class AnalyticsServiceIntegrationTest {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private UsageEventRepository usageEventRepository;

    @Autowired
    private ApiConsumerRepository consumerRepository;

    @Autowired
    private SubscriptionPlanRepository planRepository;

    private SubscriptionPlan testPlan;
    private ApiConsumer testConsumer;
    private final List<UUID> createdEventIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        testPlan = planRepository.findAll().stream()
            .filter(p -> p.getName().equalsIgnoreCase("STARTER"))
            .findFirst()
            .orElseGet(() -> planRepository.findAll().get(0));

        testConsumer = new ApiConsumer(
            "Analytics-Service-Consumer-" + System.nanoTime(),
            "analytics-" + System.nanoTime() + "@example.com",
            testPlan
        );
        testConsumer = consumerRepository.save(testConsumer);
    }

    @AfterEach
    void tearDown() {
        for (UUID id : createdEventIds) {
            usageEventRepository.deleteById(id);
        }
        if (testConsumer != null) {
            consumerRepository.deleteById(testConsumer.getId());
        }
    }

    private UsageEvent insertEvent(Instant timestamp, int status, String outcome, String endpoint, boolean rateLimited) {
        UsageEvent event = new UsageEvent(endpoint, "GET", status, 25);
        event.setId(UUID.randomUUID());
        event.setTimestamp(timestamp);
        event.setConsumer(testConsumer);
        event.setConsumerEmailSnapshot(testConsumer.getEmail());
        event.setKeyPrefixSnapshot("gk_live_99887766");
        event.setOutcomeCategory(outcome);
        event.setRateLimitPlan(testPlan.getName());
        event.setCorrelationId(UUID.randomUUID().toString());
        event.setRateLimited(rateLimited);

        UsageEvent saved = usageEventRepository.save(event);
        createdEventIds.add(saved.getId());
        return saved;
    }

    @Test
    @DisplayName("getOverview calculates accurate counts and denial percentages")
    void testGetOverview() {
        Instant now = Instant.now();
        Instant start = now.minus(2, ChronoUnit.HOURS);
        Instant end = now.plus(1, ChronoUnit.HOURS);

        // Seed: 3 allowed, 1 rate-limited (429), 1 scope-denied (403), 1 error (503)
        insertEvent(now.minus(50, ChronoUnit.MINUTES), 200, "ALLOWED", "/api/v1/gateway/demo", false);
        insertEvent(now.minus(40, ChronoUnit.MINUTES), 200, "ALLOWED", "/api/v1/gateway/demo", false);
        insertEvent(now.minus(30, ChronoUnit.MINUTES), 200, "ALLOWED", "/api/v1/gateway/orders", false);
        insertEvent(now.minus(20, ChronoUnit.MINUTES), 429, "RATE_LIMITED", "/api/v1/gateway/demo", true);
        insertEvent(now.minus(15, ChronoUnit.MINUTES), 403, "SCOPE_DENIED", "/api/v1/gateway/orders", false);
        insertEvent(now.minus(10, ChronoUnit.MINUTES), 503, "SERVICE_UNAVAILABLE", "/api/v1/gateway/demo", false);

        AnalyticsOverviewDto overview = analyticsService.getOverview(start, end);

        assertThat(overview.totalRequests()).isGreaterThanOrEqualTo(6);
        assertThat(overview.allowedRequests()).isGreaterThanOrEqualTo(3);
        assertThat(overview.rateLimitedRequests()).isGreaterThanOrEqualTo(1);
        assertThat(overview.scopeDeniedRequests()).isGreaterThanOrEqualTo(1);
        assertThat(overview.errorRequests()).isGreaterThanOrEqualTo(1);
        assertThat(overview.rateLimitDenialPercentage()).isGreaterThan(0.0);
        assertThat(overview.usageByPlan()).containsKey(testPlan.getName());
    }

    @Test
    @DisplayName("getTimeSeries groups events by hour accurately")
    void testGetTimeSeriesHourly() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant start = now.minus(5, ChronoUnit.HOURS);
        Instant end = now.plus(1, ChronoUnit.HOURS);

        insertEvent(now.minus(3, ChronoUnit.HOURS), 200, "ALLOWED", "/api/v1/gateway/demo", false);
        insertEvent(now.minus(2, ChronoUnit.HOURS), 429, "RATE_LIMITED", "/api/v1/gateway/demo", true);

        List<TimeSeriesPointDto> points = analyticsService.getTimeSeries(start, end, "hourly");
        assertThat(points).isNotEmpty();

        long totalAcrossBuckets = points.stream().mapToLong(TimeSeriesPointDto::totalRequests).sum();
        assertThat(totalAcrossBuckets).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("getConsumerAnalytics returns consumer usage metrics and breakdowns")
    void testGetConsumerAnalytics() {
        Instant now = Instant.now();
        Instant start = now.minus(2, ChronoUnit.HOURS);
        Instant end = now.plus(1, ChronoUnit.HOURS);

        insertEvent(now.minus(30, ChronoUnit.MINUTES), 200, "ALLOWED", "/api/v1/gateway/demo", false);
        insertEvent(now.minus(20, ChronoUnit.MINUTES), 429, "RATE_LIMITED", "/api/v1/gateway/demo", true);
        insertEvent(now.minus(10, ChronoUnit.MINUTES), 200, "ALLOWED", "/api/v1/gateway/orders", false);

        ConsumerAnalyticsDto stats = analyticsService.getConsumerAnalytics(testConsumer.getId(), start, end);

        assertThat(stats.consumerId()).isEqualTo(testConsumer.getId());
        assertThat(stats.totalRequests()).isEqualTo(3);
        assertThat(stats.allowedRequests()).isEqualTo(2);
        assertThat(stats.rateLimitedRequests()).isEqualTo(1);
        assertThat(stats.requestsByEndpoint()).containsEntry("/api/v1/gateway/demo", 2L);
        assertThat(stats.requestsByEndpoint()).containsEntry("/api/v1/gateway/orders", 1L);
        assertThat(stats.requestsByKeyPrefix()).containsEntry("gk_live_99887766", 3L);
    }

    @Test
    @DisplayName("getConsumerUsageEvents returns paginated audit records")
    void testGetConsumerUsageEvents() {
        Instant now = Instant.now();
        Instant start = now.minus(2, ChronoUnit.HOURS);
        Instant end = now.plus(1, ChronoUnit.HOURS);

        insertEvent(now.minus(20, ChronoUnit.MINUTES), 200, "ALLOWED", "/api/v1/gateway/demo", false);
        insertEvent(now.minus(10, ChronoUnit.MINUTES), 429, "RATE_LIMITED", "/api/v1/gateway/demo", true);

        Page<UsageEventDetailDto> page = analyticsService.getConsumerUsageEvents(
            testConsumer.getId(), start, end, PageRequest.of(0, 10)
        );

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).endpointPath()).isEqualTo("/api/v1/gateway/demo");
    }

    @Test
    @DisplayName("getTopConsumers orders consumers by traffic volume")
    void testGetTopConsumers() {
        Instant now = Instant.now();
        Instant start = now.minus(2, ChronoUnit.HOURS);
        Instant end = now.plus(1, ChronoUnit.HOURS);

        for (int i = 0; i < 5; i++) {
            insertEvent(now.minus(10, ChronoUnit.MINUTES), 200, "ALLOWED", "/api/v1/gateway/demo", false);
        }

        List<TopConsumerDto> top = analyticsService.getTopConsumers(start, end, 50);
        assertThat(top).isNotEmpty();

        boolean found = top.stream().anyMatch(tc -> tc.consumerId().equals(testConsumer.getId()));
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("validateTimeRange throws when start is after end or range exceeds 90 days")
    void testValidateTimeRange() {
        Instant now = Instant.now();

        // start after end
        assertThatThrownBy(() -> analyticsService.getOverview(now, now.minus(1, ChronoUnit.HOURS)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be after end timestamp");

        // range > 90 days
        assertThatThrownBy(() -> analyticsService.getOverview(now.minus(95, ChronoUnit.DAYS), now))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot exceed 90 days");

        // consumer not found
        assertThatThrownBy(() -> analyticsService.getConsumerAnalytics(UUID.randomUUID(), now.minus(1, ChronoUnit.HOURS), now))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
