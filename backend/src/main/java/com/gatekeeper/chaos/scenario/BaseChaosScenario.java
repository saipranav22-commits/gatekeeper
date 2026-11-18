package com.gatekeeper.chaos.scenario;

import com.gatekeeper.chaos.metrics.ReliabilityMetrics;
import com.gatekeeper.chaos.model.ChaosExperiment;
import com.gatekeeper.chaos.model.ExperimentAssertion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base abstract scenario providing shared HTTP execution, percentile math,
 * and sibling route isolation verification.
 */
public abstract class BaseChaosScenario implements ChaosScenario {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    protected final List<Long> clientLatencies = Collections.synchronizedList(new ArrayList<>());
    protected final List<Long> upstreamLatencies = Collections.synchronizedList(new ArrayList<>());
    protected final Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
    protected final Map<String, AtomicInteger> errorCounts = new ConcurrentHashMap<>();
    protected final List<String> circuitBreakerEvents = Collections.synchronizedList(new ArrayList<>());

    protected volatile Instant experimentStartTime;
    protected volatile Instant experimentEndTime;
    protected volatile int bulkheadRejections = 0;
    protected volatile int retryAttemptsRecorded = 0;
    protected volatile int peakConcurrencyObserved = 0;
    protected volatile long recoveryDurationMs = 0L;
    protected volatile double healthyAvailabilityPct = 100.0;

    protected void recordResponse(int statusCode, long clientLatencyMs, long upstreamLatencyMs, String errorType) {
        statusCounts.computeIfAbsent(statusCode, k -> new AtomicInteger(0)).incrementAndGet();
        clientLatencies.add(clientLatencyMs);
        if (upstreamLatencyMs >= 0) {
            upstreamLatencies.add(upstreamLatencyMs);
        }
        if (errorType != null && !errorType.isBlank()) {
            errorCounts.computeIfAbsent(errorType, k -> new AtomicInteger(0)).incrementAndGet();
        }
    }

    protected double calculatePercentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted;
        synchronized (values) {
            sorted = new ArrayList<>(values);
        }
        Collections.sort(sorted);
        int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    protected HttpResponse<String> sendSafePost(String url, String jsonBody, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");

            if (headers != null) {
                headers.forEach(builder::header);
            }

            builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : ""));
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            log.warn("Safe POST to {} failed: {}", url, ex.getMessage());
            return null;
        }
    }

    protected HttpResponse<String> sendSafeGet(String url, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10));

            if (headers != null) {
                headers.forEach(builder::header);
            }

            builder.GET();
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            log.warn("Safe GET to {} failed: {}", url, ex.getMessage());
            return null;
        }
    }

    @Override
    public ReliabilityMetrics computeMetrics(ChaosExperiment experiment) {
        Map<Integer, Integer> finalStatusMap = new ConcurrentHashMap<>();
        statusCounts.forEach((k, v) -> finalStatusMap.put(k, v.get()));

        Map<String, Integer> finalErrorMap = new ConcurrentHashMap<>();
        errorCounts.forEach((k, v) -> finalErrorMap.put(k, v.get()));

        int total = finalStatusMap.values().stream().mapToInt(Integer::intValue).sum();
        int success = finalStatusMap.getOrDefault(200, 0) + finalStatusMap.getOrDefault(201, 0);
        int failed = total - success;

        long duration = (experimentStartTime != null && experimentEndTime != null)
            ? Duration.between(experimentStartTime, experimentEndTime).toMillis()
            : 0L;

        return ReliabilityMetrics.builder()
            .experimentId(experiment.getId())
            .scenarioType(getScenarioType())
            .targetService(experiment.getTarget().getServiceKey())
            .startTime(experimentStartTime != null ? experimentStartTime : Instant.now())
            .endTime(experimentEndTime != null ? experimentEndTime : Instant.now())
            .durationMs(duration)
            .injectedFaultType(experiment.getFaultType().name())
            .totalRequests(total)
            .successfulRequests(success)
            .failedRequests(failed)
            .statusDistribution(finalStatusMap)
            .errorClassification(finalErrorMap)
            .gatewayLatencyP50Ms(calculatePercentile(clientLatencies, 50.0))
            .gatewayLatencyP95Ms(calculatePercentile(clientLatencies, 95.0))
            .gatewayLatencyP99Ms(calculatePercentile(clientLatencies, 99.0))
            .upstreamLatencyP50Ms(calculatePercentile(upstreamLatencies, 50.0))
            .upstreamLatencyP95Ms(calculatePercentile(upstreamLatencies, 95.0))
            .upstreamLatencyP99Ms(calculatePercentile(upstreamLatencies, 99.0))
            .circuitBreakerTransitions(new ArrayList<>(circuitBreakerEvents))
            .bulkheadRejectionCount(bulkheadRejections)
            .retryAttemptCount(retryAttemptsRecorded)
            .peakConcurrency(peakConcurrencyObserved)
            .recoveryDurationMs(recoveryDurationMs)
            .healthyServiceAvailability(healthyAvailabilityPct)
            .cleanupStatus(experiment.getCleanupStatus())
            .assertions(evaluateAssertions(experiment))
            .build();
    }
}
