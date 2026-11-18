package com.gatekeeper.chaos.metrics;

import com.gatekeeper.chaos.model.ChaosScenarioType;
import com.gatekeeper.chaos.model.ExperimentAssertion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured reliability metrics captured during a chaos engineering experiment.
 * Internal decision latency is separated from client round-trip latency.
 */
public class ReliabilityMetrics {

    private final String experimentId;
    private final ChaosScenarioType scenarioType;
    private final String targetService;
    private final Instant startTime;
    private final Instant endTime;
    private final long durationMs;
    private final String injectedFaultType;

    private final int totalRequests;
    private final int successfulRequests;
    private final int failedRequests;
    private final Map<Integer, Integer> statusDistribution;
    private final Map<String, Integer> errorClassification;

    private final double gatewayLatencyP50Ms;
    private final double gatewayLatencyP95Ms;
    private final double gatewayLatencyP99Ms;

    private final double upstreamLatencyP50Ms;
    private final double upstreamLatencyP95Ms;
    private final double upstreamLatencyP99Ms;

    private final List<String> circuitBreakerTransitions;
    private final int bulkheadRejectionCount;
    private final int retryAttemptCount;
    private final int peakConcurrency;
    private final long recoveryDurationMs;
    private final double healthyServiceAvailability;
    private final String cleanupStatus;
    private final List<ExperimentAssertion> assertions;

    private ReliabilityMetrics(Builder builder) {
        this.experimentId = builder.experimentId;
        this.scenarioType = builder.scenarioType;
        this.targetService = builder.targetService;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.durationMs = builder.durationMs;
        this.injectedFaultType = builder.injectedFaultType;
        this.totalRequests = builder.totalRequests;
        this.successfulRequests = builder.successfulRequests;
        this.failedRequests = builder.failedRequests;
        this.statusDistribution = Collections.unmodifiableMap(new HashMap<>(builder.statusDistribution));
        this.errorClassification = Collections.unmodifiableMap(new HashMap<>(builder.errorClassification));
        this.gatewayLatencyP50Ms = builder.gatewayLatencyP50Ms;
        this.gatewayLatencyP95Ms = builder.gatewayLatencyP95Ms;
        this.gatewayLatencyP99Ms = builder.gatewayLatencyP99Ms;
        this.upstreamLatencyP50Ms = builder.upstreamLatencyP50Ms;
        this.upstreamLatencyP95Ms = builder.upstreamLatencyP95Ms;
        this.upstreamLatencyP99Ms = builder.upstreamLatencyP99Ms;
        this.circuitBreakerTransitions = Collections.unmodifiableList(new ArrayList<>(builder.circuitBreakerTransitions));
        this.bulkheadRejectionCount = builder.bulkheadRejectionCount;
        this.retryAttemptCount = builder.retryAttemptCount;
        this.peakConcurrency = builder.peakConcurrency;
        this.recoveryDurationMs = builder.recoveryDurationMs;
        this.healthyServiceAvailability = builder.healthyServiceAvailability;
        this.cleanupStatus = builder.cleanupStatus;
        this.assertions = Collections.unmodifiableList(new ArrayList<>(builder.assertions));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getExperimentId() { return experimentId; }
    public ChaosScenarioType getScenarioType() { return scenarioType; }
    public String getTargetService() { return targetService; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public long getDurationMs() { return durationMs; }
    public String getInjectedFaultType() { return injectedFaultType; }
    public int getTotalRequests() { return totalRequests; }
    public int getSuccessfulRequests() { return successfulRequests; }
    public int getFailedRequests() { return failedRequests; }
    public Map<Integer, Integer> getStatusDistribution() { return statusDistribution; }
    public Map<String, Integer> getErrorClassification() { return errorClassification; }
    public double getGatewayLatencyP50Ms() { return gatewayLatencyP50Ms; }
    public double getGatewayLatencyP95Ms() { return gatewayLatencyP95Ms; }
    public double getGatewayLatencyP99Ms() { return gatewayLatencyP99Ms; }
    public double getUpstreamLatencyP50Ms() { return upstreamLatencyP50Ms; }
    public double getUpstreamLatencyP95Ms() { return upstreamLatencyP95Ms; }
    public double getUpstreamLatencyP99Ms() { return upstreamLatencyP99Ms; }
    public List<String> getCircuitBreakerTransitions() { return circuitBreakerTransitions; }
    public int getBulkheadRejectionCount() { return bulkheadRejectionCount; }
    public int getRetryAttemptCount() { return retryAttemptCount; }
    public int getPeakConcurrency() { return peakConcurrency; }
    public long getRecoveryDurationMs() { return recoveryDurationMs; }
    public double getHealthyServiceAvailability() { return healthyServiceAvailability; }
    public String getCleanupStatus() { return cleanupStatus; }
    public List<ExperimentAssertion> getAssertions() { return assertions; }

    public static class Builder {
        private String experimentId;
        private ChaosScenarioType scenarioType;
        private String targetService;
        private Instant startTime;
        private Instant endTime;
        private long durationMs;
        private String injectedFaultType;
        private int totalRequests;
        private int successfulRequests;
        private int failedRequests;
        private Map<Integer, Integer> statusDistribution = new HashMap<>();
        private Map<String, Integer> errorClassification = new HashMap<>();
        private double gatewayLatencyP50Ms;
        private double gatewayLatencyP95Ms;
        private double gatewayLatencyP99Ms;
        private double upstreamLatencyP50Ms;
        private double upstreamLatencyP95Ms;
        private double upstreamLatencyP99Ms;
        private List<String> circuitBreakerTransitions = new ArrayList<>();
        private int bulkheadRejectionCount;
        private int retryAttemptCount;
        private int peakConcurrency;
        private long recoveryDurationMs;
        private double healthyServiceAvailability;
        private String cleanupStatus = "SUCCESS";
        private List<ExperimentAssertion> assertions = new ArrayList<>();

        public Builder experimentId(String experimentId) { this.experimentId = experimentId; return this; }
        public Builder scenarioType(ChaosScenarioType scenarioType) { this.scenarioType = scenarioType; return this; }
        public Builder targetService(String targetService) { this.targetService = targetService; return this; }
        public Builder startTime(Instant startTime) { this.startTime = startTime; return this; }
        public Builder endTime(Instant endTime) { this.endTime = endTime; return this; }
        public Builder durationMs(long durationMs) { this.durationMs = durationMs; return this; }
        public Builder injectedFaultType(String injectedFaultType) { this.injectedFaultType = injectedFaultType; return this; }
        public Builder totalRequests(int totalRequests) { this.totalRequests = totalRequests; return this; }
        public Builder successfulRequests(int successfulRequests) { this.successfulRequests = successfulRequests; return this; }
        public Builder failedRequests(int failedRequests) { this.failedRequests = failedRequests; return this; }
        public Builder statusDistribution(Map<Integer, Integer> statusDistribution) { if (statusDistribution != null) this.statusDistribution.putAll(statusDistribution); return this; }
        public Builder errorClassification(Map<String, Integer> errorClassification) { if (errorClassification != null) this.errorClassification.putAll(errorClassification); return this; }
        public Builder gatewayLatencyP50Ms(double p50) { this.gatewayLatencyP50Ms = p50; return this; }
        public Builder gatewayLatencyP95Ms(double p95) { this.gatewayLatencyP95Ms = p95; return this; }
        public Builder gatewayLatencyP99Ms(double p99) { this.gatewayLatencyP99Ms = p99; return this; }
        public Builder upstreamLatencyP50Ms(double p50) { this.upstreamLatencyP50Ms = p50; return this; }
        public Builder upstreamLatencyP95Ms(double p95) { this.upstreamLatencyP95Ms = p95; return this; }
        public Builder upstreamLatencyP99Ms(double p99) { this.upstreamLatencyP99Ms = p99; return this; }
        public Builder circuitBreakerTransitions(List<String> transitions) { if (transitions != null) this.circuitBreakerTransitions.addAll(transitions); return this; }
        public Builder bulkheadRejectionCount(int count) { this.bulkheadRejectionCount = count; return this; }
        public Builder retryAttemptCount(int count) { this.retryAttemptCount = count; return this; }
        public Builder peakConcurrency(int peak) { this.peakConcurrency = peak; return this; }
        public Builder recoveryDurationMs(long duration) { this.recoveryDurationMs = duration; return this; }
        public Builder healthyServiceAvailability(double availability) { this.healthyServiceAvailability = availability; return this; }
        public Builder cleanupStatus(String cleanupStatus) { this.cleanupStatus = cleanupStatus; return this; }
        public Builder assertions(List<ExperimentAssertion> assertions) { if (assertions != null) this.assertions.addAll(assertions); return this; }

        public ReliabilityMetrics build() {
            return new ReliabilityMetrics(this);
        }
    }
}
