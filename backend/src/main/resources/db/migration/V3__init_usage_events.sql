-- ==============================================================================
-- V3: Usage Events
-- ==============================================================================

-- Usage Events Table (Audit & Analytics Log)
-- consumer_id and api_key_id are intentionally nullable to capture requests with
-- invalid, expired, revoked, or absent credentials.
-- Snapshots retain context even if the consumer or key is subsequently soft-deleted or modified.
CREATE TABLE usage_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    consumer_id UUID,
    api_key_id UUID,
    consumer_email_snapshot VARCHAR(150),
    key_prefix_snapshot VARCHAR(24),
    endpoint_path VARCHAR(255) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    http_status INTEGER NOT NULL,
    latency_ms INTEGER NOT NULL,
    rate_limited BOOLEAN NOT NULL DEFAULT FALSE,
    rejection_reason VARCHAR(50),
    ip_address VARCHAR(45),
    user_agent VARCHAR(255),
    CONSTRAINT fk_usage_events_consumer FOREIGN KEY (consumer_id)
        REFERENCES api_consumers(id) ON DELETE SET NULL,
    CONSTRAINT fk_usage_events_api_key FOREIGN KEY (api_key_id)
        REFERENCES api_keys(id) ON DELETE SET NULL
);

CREATE INDEX idx_usage_events_consumer_timestamp ON usage_events(consumer_id, timestamp DESC);
CREATE INDEX idx_usage_events_key_timestamp ON usage_events(api_key_id, timestamp DESC);
CREATE INDEX idx_usage_events_rate_limited_timestamp ON usage_events(rate_limited, timestamp DESC);
CREATE INDEX idx_usage_events_timestamp ON usage_events(timestamp DESC);
