package com.gatekeeper.analytics.controller;

import com.gatekeeper.analytics.dto.AnalyticsOverviewDto;
import com.gatekeeper.analytics.dto.ConsumerAnalyticsDto;
import com.gatekeeper.analytics.dto.TimeSeriesPointDto;
import com.gatekeeper.analytics.dto.TopConsumerDto;
import com.gatekeeper.analytics.dto.UsageEventDetailDto;
import com.gatekeeper.analytics.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Administrative REST APIs for operational analytics, telemetry aggregation, and usage auditing.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@Profile("!foundation")
@Tag(name = "Analytics", description = "Operational usage tracking, gateway telemetry, and traffic analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = Objects.requireNonNull(analyticsService, "analyticsService must not be null");
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get global gateway analytics overview", description = "Aggregates total requests, success/rate-limited/scope-denied counts, and plan usage.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Overview analytics retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Bad Request - invalid date range"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges")
    })
    public ResponseEntity<AnalyticsOverviewDto> getOverview(
            @Parameter(description = "Start timestamp (ISO-8601). Defaults to 24 hours ago.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End timestamp (ISO-8601). Defaults to current time.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {

        Instant effectiveEnd = end != null ? end : Instant.now();
        Instant effectiveStart = start != null ? start : effectiveEnd.minus(Duration.ofHours(24));

        return ResponseEntity.ok(analyticsService.getOverview(effectiveStart, effectiveEnd));
    }

    @GetMapping("/timeseries")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get traffic time-series aggregation", description = "Returns bucketed gateway traffic counts over time (hourly or daily).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Time-series points retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Bad Request - invalid date range or granularity"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges")
    })
    public ResponseEntity<List<TimeSeriesPointDto>> getTimeSeries(
            @Parameter(description = "Start timestamp (ISO-8601). Defaults to 7 days ago.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End timestamp (ISO-8601). Defaults to current time.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @Parameter(description = "Bucket granularity: 'hourly' or 'daily'", example = "hourly")
            @RequestParam(defaultValue = "hourly") String granularity) {

        Instant effectiveEnd = end != null ? end : Instant.now();
        Instant effectiveStart = start != null ? start : effectiveEnd.minus(Duration.ofDays(7));

        return ResponseEntity.ok(analyticsService.getTimeSeries(effectiveStart, effectiveEnd, granularity));
    }

    @GetMapping("/consumers/{consumerId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get specific consumer analytics", description = "Aggregates usage, rate-limit statistics, endpoint distribution, and key usage for a consumer.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Consumer analytics retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Bad Request - invalid date range"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Not Found - consumer does not exist")
    })
    public ResponseEntity<ConsumerAnalyticsDto> getConsumerAnalytics(
            @PathVariable UUID consumerId,
            @Parameter(description = "Start timestamp (ISO-8601). Defaults to 24 hours ago.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End timestamp (ISO-8601). Defaults to current time.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {

        Instant effectiveEnd = end != null ? end : Instant.now();
        Instant effectiveStart = start != null ? start : effectiveEnd.minus(Duration.ofHours(24));

        return ResponseEntity.ok(analyticsService.getConsumerAnalytics(consumerId, effectiveStart, effectiveEnd));
    }

    @GetMapping("/consumers/{consumerId}/usage")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get paginated consumer usage events", description = "Returns detailed individual usage audit events for a consumer.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated usage events retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Bad Request - invalid parameters or date range"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges"),
        @ApiResponse(responseCode = "404", description = "Not Found - consumer does not exist")
    })
    public ResponseEntity<Page<UsageEventDetailDto>> getConsumerUsageEvents(
            @PathVariable UUID consumerId,
            @Parameter(description = "Start timestamp (ISO-8601). Defaults to 24 hours ago.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End timestamp (ISO-8601). Defaults to current time.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @Parameter(description = "Page number (0-based)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1 to 100)", example = "20")
            @RequestParam(defaultValue = "20") int size) {

        Instant effectiveEnd = end != null ? end : Instant.now();
        Instant effectiveStart = start != null ? start : effectiveEnd.minus(Duration.ofHours(24));
        int safeSize = Math.max(1, Math.min(size, 100));
        Pageable pageable = PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "timestamp"));

        return ResponseEntity.ok(analyticsService.getConsumerUsageEvents(consumerId, effectiveStart, effectiveEnd, pageable));
    }

    @GetMapping("/top-consumers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'ANALYST')")
    @Operation(summary = "Get top consumers by traffic volume", description = "Ranks consumers by total gateway request volume.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Top consumers retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Bad Request - invalid date range"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient role privileges")
    })
    public ResponseEntity<List<TopConsumerDto>> getTopConsumers(
            @Parameter(description = "Start timestamp (ISO-8601). Defaults to 24 hours ago.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End timestamp (ISO-8601). Defaults to current time.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @Parameter(description = "Maximum number of consumers to return (1 to 100)", example = "10")
            @RequestParam(defaultValue = "10") int limit) {

        Instant effectiveEnd = end != null ? end : Instant.now();
        Instant effectiveStart = start != null ? start : effectiveEnd.minus(Duration.ofHours(24));

        return ResponseEntity.ok(analyticsService.getTopConsumers(effectiveStart, effectiveEnd, limit));
    }
}
