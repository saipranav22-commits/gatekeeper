-- ==============================================================================
-- Flyway Migration V8: Versioned & Explainable Policy Engine Schema
-- ==============================================================================

-- 1. Policy Definitions Table
CREATE TABLE policy_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_key VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    active_version_id UUID,
    shadow_version_id UUID,
    version_lock BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_policy_definitions_key UNIQUE (policy_key)
);

CREATE INDEX idx_policy_definitions_key ON policy_definitions(policy_key);

-- 2. Policy Versions Table (Immutable document versions)
CREATE TABLE policy_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_definition_id UUID NOT NULL REFERENCES policy_definitions(id) ON DELETE CASCADE,
    version_number INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    rules_json TEXT NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100) NOT NULL,
    activated_at TIMESTAMPTZ,
    activated_by VARCHAR(100),
    CONSTRAINT uk_policy_version UNIQUE (policy_definition_id, version_number),
    CONSTRAINT chk_policy_version_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED', 'ROLLED_BACK'))
);

CREATE INDEX idx_policy_versions_def ON policy_versions(policy_definition_id);
CREATE INDEX idx_policy_versions_def_ver ON policy_versions(policy_definition_id, version_number);

-- 3. Add FKs from policy_definitions to policy_versions
ALTER TABLE policy_definitions
    ADD CONSTRAINT fk_policy_definitions_active_version
    FOREIGN KEY (active_version_id) REFERENCES policy_versions(id) ON DELETE SET NULL;

ALTER TABLE policy_definitions
    ADD CONSTRAINT fk_policy_definitions_shadow_version
    FOREIGN KEY (shadow_version_id) REFERENCES policy_versions(id) ON DELETE SET NULL;

-- 4. Policy Activation Audit Table
CREATE TABLE policy_activation_audits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_definition_id UUID NOT NULL REFERENCES policy_definitions(id) ON DELETE CASCADE,
    version_number INT NOT NULL,
    previous_version_number INT,
    action VARCHAR(30) NOT NULL,
    actor VARCHAR(100) NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_policy_audit_action CHECK (action IN ('ACTIVATED', 'ROLLED_BACK', 'DEACTIVATED', 'SHADOW_SET', 'SHADOW_CLEARED'))
);

CREATE INDEX idx_policy_audits_def_time ON policy_activation_audits(policy_definition_id, created_at DESC);

-- 5. Enrich usage_events with policy telemetry fields
ALTER TABLE usage_events
    ADD COLUMN IF NOT EXISTS policy_decision VARCHAR(30) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS matched_rule_id VARCHAR(64) DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_usage_events_policy_decision ON usage_events(policy_decision);
