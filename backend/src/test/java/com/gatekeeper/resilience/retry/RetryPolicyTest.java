package com.gatekeeper.resilience.retry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private RetryPolicy policy;
    private RetryConfig config;

    @BeforeEach
    void setUp() {
        // maxAttempts=3, initialBackoff=100ms, multiplier=2.0, jitter=0.5, deadline=5000ms
        config = new RetryConfig(3, 100L, 2.0, 0.5, 5000L);
        policy = new RetryPolicy(config);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "get", "HEAD", "head"})
    @DisplayName("Idempotent safe methods are allowed for retry")
    void safeMethodsAllowedForRetry(String method) {
        assertThat(policy.isSafeMethodToRetry(method)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "post", "PATCH", "patch", "PUT", "DELETE"})
    @DisplayName("Non-idempotent methods are strictly forbidden from automatic retry")
    void nonIdempotentMethodsNeverRetried(String method) {
        assertThat(policy.isSafeMethodToRetry(method)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    @DisplayName("Transient gateway/upstream error status codes are retryable")
    void transientErrorsAreRetryable(int statusCode) {
        assertThat(policy.isRetryableFailure(statusCode, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 400, 401, 403, 404, 409, 422, 429})
    @DisplayName("Client errors, rate limits, and successes are not retryable")
    void clientErrorsAndSuccessesNotRetryable(int statusCode) {
        assertThat(policy.isRetryableFailure(statusCode, null)).isFalse();
    }

    @Test
    @DisplayName("Network and timeout exceptions are classified as retryable")
    void networkExceptionsAreRetryable() {
        assertThat(policy.isRetryableFailure(0, new ConnectException("Connection refused"))).isTrue();
        assertThat(policy.isRetryableFailure(0, new HttpTimeoutException("request timed out"))).isTrue();
        assertThat(policy.isRetryableFailure(0, new IOException("Broken pipe"))).isTrue();
    }

    @Test
    @DisplayName("Backoff respects initial backoff, multiplier, and bounded jitter")
    void backoffCalculationsRespectParameters() {
        // attempt 1 (first retry): base = 100ms * (2.0^0) = 100ms. Jitter: [50ms, 150ms]
        for (int i = 0; i < 20; i++) {
            long backoff1 = policy.computeBackoffMs(1);
            assertThat(backoff1).isBetween(50L, 150L);
        }

        // attempt 2: base = 100ms * (2.0^1) = 200ms. Jitter: [100ms, 300ms]
        for (int i = 0; i < 20; i++) {
            long backoff2 = policy.computeBackoffMs(2);
            assertThat(backoff2).isBetween(100L, 300L);
        }

        // attempt 3: base = 100ms * (2.0^2) = 400ms. Jitter: [200ms, 600ms]
        for (int i = 0; i < 20; i++) {
            long backoff3 = policy.computeBackoffMs(3);
            assertThat(backoff3).isBetween(200L, 600L);
        }
    }

    @Test
    @DisplayName("Cumulative deadline permits retries within budget")
    void cumulativeDeadlineAllowsRetriesWithinBudget() {
        assertThat(policy.isWithinDeadline(1000L, 200L)).isTrue(); // 1200 <= 5000
    }

    @Test
    @DisplayName("Cumulative deadline aborts retries when budget would be exceeded")
    void cumulativeDeadlineAbortsWhenExceeded() {
        assertThat(policy.isWithinDeadline(4900L, 200L)).isFalse(); // 5100 > 5000
    }
}
