-- ==============================================================================
-- V9: Widen usage_events rejection_reason to accommodate policy explanations
-- ==============================================================================

ALTER TABLE usage_events
    ALTER COLUMN rejection_reason TYPE VARCHAR(255);
