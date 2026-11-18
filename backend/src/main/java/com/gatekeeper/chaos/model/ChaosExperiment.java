package com.gatekeeper.chaos.model;

import com.gatekeeper.chaos.metrics.ReliabilityMetrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mutable execution entity tracking the lifecycle, configuration, telemetry,
 * and outcomes of a single controlled chaos engineering experiment.
 */
public class ChaosExperiment {

    private final String id;
    private final ChaosScenarioType scenarioType;
    private final ChaosTarget target;
    private final FaultType faultType;
    private final Map<String, Object> parameters;

    private volatile ExperimentState state;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private String failureReason;
    private String cleanupStatus;

    private final List<ExperimentAssertion> assertions = Collections.synchronizedList(new ArrayList<>());
    private volatile ReliabilityMetrics metrics;

    public ChaosExperiment(ChaosScenarioType scenarioType, ChaosTarget target, FaultType faultType) {
        this(UUID.randomUUID().toString().substring(0, 8), scenarioType, target, faultType, new HashMap<>());
    }

    public ChaosExperiment(ChaosScenarioType scenarioType, ChaosTarget target, FaultType faultType, Map<String, Object> parameters) {
        this(UUID.randomUUID().toString().substring(0, 8), scenarioType, target, faultType, parameters);
    }

    public ChaosExperiment(String id, ChaosScenarioType scenarioType, ChaosTarget target, FaultType faultType, Map<String, Object> parameters) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.scenarioType = Objects.requireNonNull(scenarioType, "scenarioType must not be null");
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.faultType = Objects.requireNonNull(faultType, "faultType must not be null");
        this.parameters = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
        this.state = ExperimentState.CREATED;
        this.createdAt = Instant.now();
        this.cleanupStatus = "PENDING";
    }

    public String getId() {
        return id;
    }

    public ChaosScenarioType getScenarioType() {
        return scenarioType;
    }

    public ChaosTarget getTarget() {
        return target;
    }

    public FaultType getFaultType() {
        return faultType;
    }

    public Map<String, Object> getParameters() {
        return Collections.unmodifiableMap(parameters);
    }

    public void setParameter(String key, Object value) {
        this.parameters.put(key, value);
    }

    public ExperimentState getState() {
        return state;
    }

    public void transitionTo(ExperimentState newState) {
        this.state = newState;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void markStarted() {
        this.startedAt = Instant.now();
        this.state = ExperimentState.RUNNING;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void markCompleted(ExperimentState finalState) {
        this.completedAt = Instant.now();
        this.state = finalState;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getCleanupStatus() {
        return cleanupStatus;
    }

    public void setCleanupStatus(String cleanupStatus) {
        this.cleanupStatus = cleanupStatus;
    }

    public List<ExperimentAssertion> getAssertions() {
        synchronized (assertions) {
            return new ArrayList<>(assertions);
        }
    }

    public void addAssertion(ExperimentAssertion assertion) {
        if (assertion != null) {
            this.assertions.add(assertion);
        }
    }

    public ReliabilityMetrics getMetrics() {
        return metrics;
    }

    public void setMetrics(ReliabilityMetrics metrics) {
        this.metrics = metrics;
    }
}
