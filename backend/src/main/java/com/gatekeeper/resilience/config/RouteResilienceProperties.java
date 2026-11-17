package com.gatekeeper.resilience.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring configuration properties for the Gatekeeper Resilience Engine.
 */
@Component
@ConfigurationProperties(prefix = "gatekeeper.resilience")
public class RouteResilienceProperties {

    private boolean enabled = true;

    // Circuit Breaker Defaults
    private int defaultFailureRateThreshold = 50;
    private int defaultSlowCallRateThreshold = 50;
    private long defaultSlowCallDurationMs = 2000L;
    private int defaultSlidingWindowSize = 20;
    private int defaultMinimumNumberOfCalls = 5;
    private long defaultWaitDurationInOpenStateMs = 5000L;
    private int defaultPermittedCallsInHalfOpen = 3;

    // Bulkhead Defaults
    private int defaultMaxConcurrentCalls = 10;

    // Retry Defaults
    private int defaultMaxAttempts = 3;
    private long defaultInitialBackoffMs = 100L;
    private double defaultBackoffMultiplier = 2.0;
    private double defaultJitterFactor = 0.5;
    private long defaultMaxCumulativeDeadlineMs = 5000L;

    // Per-route overrides
    private Map<String, RouteOverride> routes = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultFailureRateThreshold() {
        return defaultFailureRateThreshold;
    }

    public void setDefaultFailureRateThreshold(int defaultFailureRateThreshold) {
        this.defaultFailureRateThreshold = defaultFailureRateThreshold;
    }

    public int getDefaultSlowCallRateThreshold() {
        return defaultSlowCallRateThreshold;
    }

    public void setDefaultSlowCallRateThreshold(int defaultSlowCallRateThreshold) {
        this.defaultSlowCallRateThreshold = defaultSlowCallRateThreshold;
    }

    public long getDefaultSlowCallDurationMs() {
        return defaultSlowCallDurationMs;
    }

    public void setDefaultSlowCallDurationMs(long defaultSlowCallDurationMs) {
        this.defaultSlowCallDurationMs = defaultSlowCallDurationMs;
    }

    public int getDefaultSlidingWindowSize() {
        return defaultSlidingWindowSize;
    }

    public void setDefaultSlidingWindowSize(int defaultSlidingWindowSize) {
        this.defaultSlidingWindowSize = defaultSlidingWindowSize;
    }

    public int getDefaultMinimumNumberOfCalls() {
        return defaultMinimumNumberOfCalls;
    }

    public void setDefaultMinimumNumberOfCalls(int defaultMinimumNumberOfCalls) {
        this.defaultMinimumNumberOfCalls = defaultMinimumNumberOfCalls;
    }

    public long getDefaultWaitDurationInOpenStateMs() {
        return defaultWaitDurationInOpenStateMs;
    }

    public void setDefaultWaitDurationInOpenStateMs(long defaultWaitDurationInOpenStateMs) {
        this.defaultWaitDurationInOpenStateMs = defaultWaitDurationInOpenStateMs;
    }

    public int getDefaultPermittedCallsInHalfOpen() {
        return defaultPermittedCallsInHalfOpen;
    }

    public void setDefaultPermittedCallsInHalfOpen(int defaultPermittedCallsInHalfOpen) {
        this.defaultPermittedCallsInHalfOpen = defaultPermittedCallsInHalfOpen;
    }

    public int getDefaultMaxConcurrentCalls() {
        return defaultMaxConcurrentCalls;
    }

    public void setDefaultMaxConcurrentCalls(int defaultMaxConcurrentCalls) {
        this.defaultMaxConcurrentCalls = defaultMaxConcurrentCalls;
    }

    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    public long getDefaultInitialBackoffMs() {
        return defaultInitialBackoffMs;
    }

    public void setDefaultInitialBackoffMs(long defaultInitialBackoffMs) {
        this.defaultInitialBackoffMs = defaultInitialBackoffMs;
    }

    public double getDefaultBackoffMultiplier() {
        return defaultBackoffMultiplier;
    }

    public void setDefaultBackoffMultiplier(double defaultBackoffMultiplier) {
        this.defaultBackoffMultiplier = defaultBackoffMultiplier;
    }

    public double getDefaultJitterFactor() {
        return defaultJitterFactor;
    }

    public void setDefaultJitterFactor(double defaultJitterFactor) {
        this.defaultJitterFactor = defaultJitterFactor;
    }

    public long getDefaultMaxCumulativeDeadlineMs() {
        return defaultMaxCumulativeDeadlineMs;
    }

    public void setDefaultMaxCumulativeDeadlineMs(long defaultMaxCumulativeDeadlineMs) {
        this.defaultMaxCumulativeDeadlineMs = defaultMaxCumulativeDeadlineMs;
    }

    public Map<String, RouteOverride> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, RouteOverride> routes) {
        this.routes = routes;
    }

    public static class RouteOverride {
        private Integer failureRateThreshold;
        private Integer slowCallRateThreshold;
        private Long slowCallDurationMs;
        private Integer slidingWindowSize;
        private Integer minimumNumberOfCalls;
        private Long waitDurationInOpenStateMs;
        private Integer permittedCallsInHalfOpen;
        private Integer maxConcurrentCalls;
        private Integer maxAttempts;
        private Long initialBackoffMs;
        private Long maxCumulativeDeadlineMs;

        public Integer getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(Integer failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }

        public Integer getSlowCallRateThreshold() { return slowCallRateThreshold; }
        public void setSlowCallRateThreshold(Integer slowCallRateThreshold) { this.slowCallRateThreshold = slowCallRateThreshold; }

        public Long getSlowCallDurationMs() { return slowCallDurationMs; }
        public void setSlowCallDurationMs(Long slowCallDurationMs) { this.slowCallDurationMs = slowCallDurationMs; }

        public Integer getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(Integer slidingWindowSize) { this.slidingWindowSize = slidingWindowSize; }

        public Integer getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
        public void setMinimumNumberOfCalls(Integer minimumNumberOfCalls) { this.minimumNumberOfCalls = minimumNumberOfCalls; }

        public Long getWaitDurationInOpenStateMs() { return waitDurationInOpenStateMs; }
        public void setWaitDurationInOpenStateMs(Long waitDurationInOpenStateMs) { this.waitDurationInOpenStateMs = waitDurationInOpenStateMs; }

        public Integer getPermittedCallsInHalfOpen() { return permittedCallsInHalfOpen; }
        public void setPermittedCallsInHalfOpen(Integer permittedCallsInHalfOpen) { this.permittedCallsInHalfOpen = permittedCallsInHalfOpen; }

        public Integer getMaxConcurrentCalls() { return maxConcurrentCalls; }
        public void setMaxConcurrentCalls(Integer maxConcurrentCalls) { this.maxConcurrentCalls = maxConcurrentCalls; }

        public Integer getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }

        public Long getInitialBackoffMs() { return initialBackoffMs; }
        public void setInitialBackoffMs(Long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }

        public Long getMaxCumulativeDeadlineMs() { return maxCumulativeDeadlineMs; }
        public void setMaxCumulativeDeadlineMs(Long maxCumulativeDeadlineMs) { this.maxCumulativeDeadlineMs = maxCumulativeDeadlineMs; }
    }
}
