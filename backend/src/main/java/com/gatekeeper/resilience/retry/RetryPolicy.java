package com.gatekeeper.resilience.retry;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Evaluates retry safety based on HTTP method idempotency, failure classification,
 * and computes exponential backoff with full jitter.
 */
public class RetryPolicy {

    private static final Set<String> IDEMPOTENT_SAFE_METHODS = Set.of("GET", "HEAD");
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(502, 503, 504);

    private final RetryConfig config;

    public RetryPolicy(RetryConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public RetryConfig getConfig() {
        return config;
    }

    /**
     * Determines if an HTTP method is fundamentally safe to retry.
     * GET and HEAD are safe. POST and PATCH are never retried automatically.
     */
    public boolean isSafeMethodToRetry(String httpMethod) {
        if (httpMethod == null) return false;
        return IDEMPOTENT_SAFE_METHODS.contains(httpMethod.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Determines if a status code or exception represents a transient failure worth retrying.
     */
    public boolean isRetryableFailure(int statusCode, Throwable throwable) {
        if (RETRYABLE_STATUS_CODES.contains(statusCode)) {
            return true;
        }

        if (throwable != null) {
            return throwable instanceof ConnectException
                || throwable instanceof HttpTimeoutException
                || throwable instanceof IOException;
        }

        return false;
    }

    /**
     * Computes exponential backoff with jitter for the given retry attempt (1-based index).
     *
     * @param attempt 1 for first retry, 2 for second, etc.
     * @return delay in milliseconds
     */
    public long computeBackoffMs(int attempt) {
        if (attempt <= 0) return 0L;

        double baseDelay = config.initialBackoffMs() * Math.pow(config.backoffMultiplier(), attempt - 1);
        double jitterMin = baseDelay * (1.0 - config.jitterFactor());
        double jitterMax = baseDelay * (1.0 + config.jitterFactor());

        long min = (long) Math.max(1, jitterMin);
        long max = (long) Math.max(min + 1, jitterMax);

        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    /**
     * Checks if the cumulative elapsed time plus next backoff is within the maximum allowed deadline.
     *
     * @param elapsedMs cumulative time already spent on attempts and backoffs
     * @param nextBackoffMs intended backoff duration before the next attempt
     * @return true if within deadline budget; false otherwise
     */
    public boolean isWithinDeadline(long elapsedMs, long nextBackoffMs) {
        return (elapsedMs + nextBackoffMs) <= config.maxCumulativeDeadlineMs();
    }
}
