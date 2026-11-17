-- ==============================================================================
-- Flyway Migration V7: Add Resilience Telemetry Fields
-- ==============================================================================
-- Enriches usage_events with resilience metrics:
-- - circuit_state: State of the circuit breaker during request evaluation (CLOSED, OPEN, HALF_OPEN)
-- - retry_count: Number of retry attempts executed for transient failures
-- - bulkhead_rejected: Flag indicating if the request was rejected due to concurrency saturation
-- ==============================================================================

ALTER TABLE usage_events
    ADD COLUMN IF NOT EXISTS circuit_state VARCHAR(20) DEFAULT 'CLOSED',
    ADD COLUMN IF NOT EXISTS retry_count INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS bulkhead_rejected BOOLEAN DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_usage_events_circuit_state
    ON usage_events (circuit_state);
