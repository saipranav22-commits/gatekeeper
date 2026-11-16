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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Service for computing high-performance gateway operational analytics and aggregates.
 *
 * <p>Uses server-side SQL aggregation (PostgreSQL date_trunc and FILTER) to minimize
 * memory footprint and prevent pulling large datasets into the JVM.
 */
@Service
@org.springframework.context.annotation.Profile("!foundation")
@Transactional(readOnly = true)
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final JdbcTemplate jdbcTemplate;
    private final UsageEventRepository usageEventRepository;
    private final ApiConsumerRepository consumerRepository;

    public AnalyticsService(
            JdbcTemplate jdbcTemplate,
            UsageEventRepository usageEventRepository,
            ApiConsumerRepository consumerRepository) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.usageEventRepository = Objects.requireNonNull(usageEventRepository, "usageEventRepository must not be null");
        this.consumerRepository = Objects.requireNonNull(consumerRepository, "consumerRepository must not be null");
    }

    /**
     * Aggregates global gateway performance and quota metrics over a time window.
     */
    public AnalyticsOverviewDto getOverview(Instant start, Instant end) {
        validateTimeRange(start, end);

        String overviewSql = """
            SELECT
                COUNT(*) AS total_requests,
                COUNT(*) FILTER (WHERE outcome_category = 'ALLOWED') AS allowed_requests,
                COUNT(*) FILTER (WHERE outcome_category = 'RATE_LIMITED') AS rate_limited_requests,
                COUNT(*) FILTER (WHERE outcome_category = 'SCOPE_DENIED') AS scope_denied_requests,
                COUNT(*) FILTER (WHERE http_status >= 500) AS error_requests
            FROM usage_events
            WHERE timestamp >= ? AND timestamp <= ?
            """;

        AnalyticsOverviewDto result = jdbcTemplate.queryForObject(
            overviewSql,
            (rs, rowNum) -> {
                long total = rs.getLong("total_requests");
                long allowed = rs.getLong("allowed_requests");
                long rateLimited = rs.getLong("rate_limited_requests");
                long scopeDenied = rs.getLong("scope_denied_requests");
                long errors = rs.getLong("error_requests");
                double denialPercentage = total > 0 ? (rateLimited * 100.0) / total : 0.0;

                return new AnalyticsOverviewDto(
                    total,
                    allowed,
                    rateLimited,
                    scopeDenied,
                    errors,
                    Math.round(denialPercentage * 100.0) / 100.0,
                    new HashMap<>(),
                    start,
                    end
                );
            },
            Timestamp.from(start),
            Timestamp.from(end)
        );

        // Usage breakdown by plan
        String planSql = """
            SELECT COALESCE(rate_limit_plan, 'UNKNOWN') AS plan, COUNT(*) AS cnt
            FROM usage_events
            WHERE timestamp >= ? AND timestamp <= ?
            GROUP BY rate_limit_plan
            ORDER BY cnt DESC
            """;

        Map<String, Long> usageByPlan = new LinkedHashMap<>();
        jdbcTemplate.query(
            planSql,
            rs -> {
                usageByPlan.put(rs.getString("plan"), rs.getLong("cnt"));
            },
            Timestamp.from(start),
            Timestamp.from(end)
        );

        return new AnalyticsOverviewDto(
            result.totalRequests(),
            result.allowedRequests(),
            result.rateLimitedRequests(),
            result.scopeDeniedRequests(),
            result.errorRequests(),
            result.rateLimitDenialPercentage(),
            usageByPlan,
            start,
            end
        );
    }

    /**
     * Aggregates gateway traffic into time-series buckets (hourly or daily).
     */
    public List<TimeSeriesPointDto> getTimeSeries(Instant start, Instant end, String granularity) {
        validateTimeRange(start, end);

        String dateTruncField = "daily".equalsIgnoreCase(granularity) ? "day" : "hour";

        String timeSeriesSql = String.format("""
            SELECT
                date_trunc('%s', timestamp) AS bucket,
                COUNT(*) AS total_requests,
                COUNT(*) FILTER (WHERE outcome_category = 'ALLOWED') AS allowed_requests,
                COUNT(*) FILTER (WHERE outcome_category = 'RATE_LIMITED') AS rate_limited_requests,
                COUNT(*) FILTER (WHERE outcome_category = 'SCOPE_DENIED') AS scope_denied_requests,
                COUNT(*) FILTER (WHERE http_status >= 500) AS error_requests
            FROM usage_events
            WHERE timestamp >= ? AND timestamp <= ?
            GROUP BY bucket
            ORDER BY bucket ASC
            """, dateTruncField);

        return jdbcTemplate.query(
            timeSeriesSql,
            (rs, rowNum) -> new TimeSeriesPointDto(
                rs.getTimestamp("bucket").toInstant(),
                rs.getLong("total_requests"),
                rs.getLong("allowed_requests"),
                rs.getLong("rate_limited_requests"),
                rs.getLong("scope_denied_requests"),
                rs.getLong("error_requests")
            ),
            Timestamp.from(start),
            Timestamp.from(end)
        );
    }

    /**
     * Computes usage analytics for a specific consumer.
     */
    public ConsumerAnalyticsDto getConsumerAnalytics(UUID consumerId, Instant start, Instant end) {
        if (consumerId == null) {
            throw new IllegalArgumentException("Consumer ID must not be null");
        }
        validateTimeRange(start, end);

        ApiConsumer consumer = consumerRepository.findById(consumerId)
            .orElseThrow(() -> new ResourceNotFoundException("ApiConsumer", "id", consumerId));

        String summarySql = """
            SELECT
                COUNT(*) AS total_requests,
                COUNT(*) FILTER (WHERE outcome_category = 'ALLOWED') AS allowed_requests,
                COUNT(*) FILTER (WHERE outcome_category = 'RATE_LIMITED') AS rate_limited_requests,
                COUNT(*) FILTER (WHERE outcome_category = 'SCOPE_DENIED') AS scope_denied_requests,
                MIN(timestamp) AS first_seen,
                MAX(timestamp) AS last_seen
            FROM usage_events
            WHERE consumer_id = ? AND timestamp >= ? AND timestamp <= ?
            """;

        ConsumerAnalyticsDto summary = jdbcTemplate.queryForObject(
            summarySql,
            (rs, rowNum) -> {
                Timestamp first = rs.getTimestamp("first_seen");
                Timestamp last = rs.getTimestamp("last_seen");
                return new ConsumerAnalyticsDto(
                    consumer.getId(),
                    consumer.getName(),
                    consumer.getEmail(),
                    consumer.getPlan() != null ? consumer.getPlan().getName() : "FREE",
                    rs.getLong("total_requests"),
                    rs.getLong("allowed_requests"),
                    rs.getLong("rate_limited_requests"),
                    rs.getLong("scope_denied_requests"),
                    first != null ? first.toInstant() : null,
                    last != null ? last.toInstant() : null,
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>()
                );
            },
            consumerId,
            Timestamp.from(start),
            Timestamp.from(end)
        );

        // Requests by endpoint
        String endpointSql = """
            SELECT endpoint_path, COUNT(*) AS cnt
            FROM usage_events
            WHERE consumer_id = ? AND timestamp >= ? AND timestamp <= ?
            GROUP BY endpoint_path
            ORDER BY cnt DESC
            """;

        Map<String, Long> byEndpoint = new LinkedHashMap<>();
        jdbcTemplate.query(
            endpointSql,
            rs -> { byEndpoint.put(rs.getString("endpoint_path"), rs.getLong("cnt")); },
            consumerId,
            Timestamp.from(start),
            Timestamp.from(end)
        );

        // Requests by key prefix
        String prefixSql = """
            SELECT COALESCE(key_prefix_snapshot, 'unknown') AS prefix, COUNT(*) AS cnt
            FROM usage_events
            WHERE consumer_id = ? AND timestamp >= ? AND timestamp <= ?
            GROUP BY key_prefix_snapshot
            ORDER BY cnt DESC
            """;

        Map<String, Long> byPrefix = new LinkedHashMap<>();
        jdbcTemplate.query(
            prefixSql,
            rs -> { byPrefix.put(rs.getString("prefix"), rs.getLong("cnt")); },
            consumerId,
            Timestamp.from(start),
            Timestamp.from(end)
        );

        return new ConsumerAnalyticsDto(
            summary.consumerId(),
            summary.consumerName(),
            summary.consumerEmail(),
            summary.currentPlan(),
            summary.totalRequests(),
            summary.allowedRequests(),
            summary.rateLimitedRequests(),
            summary.scopeDeniedRequests(),
            summary.firstSeen(),
            summary.lastSeen(),
            byEndpoint,
            byPrefix
        );
    }

    /**
     * Retrieves paginated detailed usage event records for a consumer.
     */
    public Page<UsageEventDetailDto> getConsumerUsageEvents(UUID consumerId, Instant start, Instant end, Pageable pageable) {
        if (consumerId == null) {
            throw new IllegalArgumentException("Consumer ID must not be null");
        }
        validateTimeRange(start, end);

        if (!consumerRepository.existsById(consumerId)) {
            throw new ResourceNotFoundException("ApiConsumer", "id", consumerId);
        }

        Page<UsageEvent> page = usageEventRepository
            .findByConsumerIdAndTimestampBetweenOrderByTimestampDesc(consumerId, start, end, pageable);

        return page.map(e -> new UsageEventDetailDto(
            e.getId(),
            e.getTimestamp(),
            e.getApiKey() != null ? e.getApiKey().getId() : null,
            e.getKeyPrefixSnapshot(),
            e.getEndpointPath(),
            e.getHttpMethod(),
            e.getHttpStatus(),
            e.getLatencyMs(),
            e.getOutcomeCategory(),
            e.getRateLimitPlan(),
            e.getCorrelationId()
        ));
    }

    /**
     * Lists top consumers ranked by total request volume within a time window.
     */
    public List<TopConsumerDto> getTopConsumers(Instant start, Instant end, int limit) {
        validateTimeRange(start, end);
        int safeLimit = Math.max(1, Math.min(limit, 100));

        String topSql = """
            SELECT
                u.consumer_id,
                COALESCE(c.name, u.consumer_email_snapshot, 'Unknown') AS consumer_name,
                COALESCE(c.email, u.consumer_email_snapshot, '') AS consumer_email,
                COALESCE(p.name, u.rate_limit_plan, 'FREE') AS plan_name,
                COUNT(*) AS total_requests,
                COUNT(*) FILTER (WHERE u.outcome_category = 'ALLOWED') AS allowed_requests,
                COUNT(*) FILTER (WHERE u.outcome_category = 'RATE_LIMITED') AS rate_limited_requests
            FROM usage_events u
            LEFT JOIN api_consumers c ON u.consumer_id = c.id
            LEFT JOIN subscription_plans p ON c.plan_id = p.id
            WHERE u.timestamp >= ? AND u.timestamp <= ?
              AND u.consumer_id IS NOT NULL
            GROUP BY u.consumer_id, c.name, c.email, p.name, u.consumer_email_snapshot, u.rate_limit_plan
            ORDER BY total_requests DESC
            LIMIT ?
            """;

        return jdbcTemplate.query(
            topSql,
            (rs, rowNum) -> {
                long total = rs.getLong("total_requests");
                long rateLimited = rs.getLong("rate_limited_requests");
                double denialPercentage = total > 0 ? (rateLimited * 100.0) / total : 0.0;

                return new TopConsumerDto(
                    (UUID) rs.getObject("consumer_id"),
                    rs.getString("consumer_name"),
                    rs.getString("consumer_email"),
                    rs.getString("plan_name"),
                    total,
                    rs.getLong("allowed_requests"),
                    rateLimited,
                    Math.round(denialPercentage * 100.0) / 100.0
                );
            },
            Timestamp.from(start),
            Timestamp.from(end),
            safeLimit
        );
    }

    private void validateTimeRange(Instant start, Instant end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("Start and end timestamps must not be null");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Start timestamp (" + start + ") cannot be after end timestamp (" + end + ")");
        }
        if (Duration.between(start, end).toDays() > 90) {
            throw new IllegalArgumentException("Analytics time range cannot exceed 90 days");
        }
    }
}
