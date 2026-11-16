-- ==============================================================================
-- V2: Consumers, API Keys, and Scopes
-- ==============================================================================

-- Consumers Table
CREATE TABLE api_consumers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL,
    plan_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_api_consumers_email UNIQUE (email),
    CONSTRAINT fk_api_consumers_plan FOREIGN KEY (plan_id)
        REFERENCES subscription_plans(id) ON DELETE RESTRICT
);

CREATE INDEX idx_api_consumers_email ON api_consumers(email);
CREATE INDEX idx_api_consumers_plan_id ON api_consumers(plan_id);
CREATE INDEX idx_api_consumers_status_deleted ON api_consumers(status, is_deleted);

-- API Keys Table
-- key_prefix: Exact 24-character public lookup identifier (e.g., gk_live_8f3a9b1c2d4e5f6a)
-- secret_digest: SHA-256 cryptographic hex digest of the raw key secret
-- version: Concurrency control counter for optimistic locking
CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    consumer_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    key_prefix VARCHAR(24) NOT NULL,
    secret_digest VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(255),
    last_used_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_api_keys_key_prefix UNIQUE (key_prefix),
    CONSTRAINT fk_api_keys_consumer FOREIGN KEY (consumer_id)
        REFERENCES api_consumers(id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX idx_api_keys_prefix ON api_keys(key_prefix);
CREATE INDEX idx_api_keys_consumer_id ON api_keys(consumer_id);
CREATE INDEX idx_api_keys_status_active ON api_keys(status, is_deleted);

-- API Key Scopes Table (Granular permissions per key)
CREATE TABLE api_key_scopes (
    api_key_id UUID NOT NULL,
    scope VARCHAR(50) NOT NULL,
    PRIMARY KEY (api_key_id, scope),
    CONSTRAINT fk_api_key_scopes_key FOREIGN KEY (api_key_id)
        REFERENCES api_keys(id) ON DELETE CASCADE
);

CREATE INDEX idx_api_key_scopes_scope ON api_key_scopes(scope);
