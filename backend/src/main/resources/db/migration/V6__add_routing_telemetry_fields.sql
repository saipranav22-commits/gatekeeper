-- ==============================================================================
-- V6: Add Routing Telemetry Fields to Usage Events
-- ==============================================================================
-- Captures upstream route identification, upstream response status, and
-- upstream request latency for proxied traffic while preserving historical data.
-- ==============================================================================

ALTER TABLE usage_events
    ADD COLUMN route_id VARCHAR(100),
    ADD COLUMN upstream_status INTEGER,
    ADD COLUMN upstream_latency_ms INTEGER;

CREATE INDEX idx_usage_events_route_id ON usage_events(route_id);
