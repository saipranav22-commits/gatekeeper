-- ==============================================================================
-- V5: Enhance Usage Events for Asynchronous Telemetry & Analytics
-- ==============================================================================

-- 1. Add fields for structured gateway outcome, subscription plan, and correlation tracking
ALTER TABLE usage_events
    ADD COLUMN outcome_category VARCHAR(32) NOT NULL DEFAULT 'ALLOWED',
    ADD COLUMN rate_limit_plan VARCHAR(32),
    ADD COLUMN correlation_id VARCHAR(64);

-- 2. Performance indexes for time-series aggregation, consumer breakdowns, and correlation lookup
CREATE INDEX idx_usage_events_outcome_timestamp ON usage_events(outcome_category, timestamp DESC);
CREATE INDEX idx_usage_events_consumer_timestamp_status ON usage_events(consumer_id, timestamp DESC, http_status);
CREATE INDEX idx_usage_events_correlation_id ON usage_events(correlation_id);
CREATE INDEX idx_usage_events_plan_timestamp ON usage_events(rate_limit_plan, timestamp DESC);

-- 3. Dead-letter queue table for unprocessable or poison stream events
CREATE TABLE usage_event_dead_letter (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stream_message_id VARCHAR(64),
    payload TEXT NOT NULL,
    error_message TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dead_letter_created_at ON usage_event_dead_letter(created_at DESC);
