-- ==============================================================================
-- Flyway Migration V4: Add token version and password changed timestamp to admin_users
-- ==============================================================================
-- Enables instantaneous cryptographic invalidation of previously issued JWTs
-- upon administrator password change or security credential rotation.
-- ==============================================================================

ALTER TABLE admin_users
ADD COLUMN token_version BIGINT NOT NULL DEFAULT 1,
ADD COLUMN password_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

COMMENT ON COLUMN admin_users.token_version IS 'Incrementing token version used to invalidate issued JWTs upon password change';
COMMENT ON COLUMN admin_users.password_changed_at IS 'Timestamp of the most recent password update or reset';
