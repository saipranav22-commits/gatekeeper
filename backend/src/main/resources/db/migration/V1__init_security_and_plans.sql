-- ==============================================================================
-- V1: Admin Users and Subscription Plans
-- ==============================================================================

-- Admin Users Table (Credentials are NOT seeded here; managed via bootstrap or admin API)
CREATE TABLE admin_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL DEFAULT 'ROLE_ADMIN',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_admin_users_username UNIQUE (username),
    CONSTRAINT uk_admin_users_email UNIQUE (email)
);

CREATE INDEX idx_admin_users_username ON admin_users(username);
CREATE INDEX idx_admin_users_email ON admin_users(email);

-- Subscription Plans Table (Token Bucket Semantics)
-- bucket_capacity: Maximum burst capacity of the token bucket
-- refill_tokens: Number of tokens added each period
-- refill_period_seconds: Duration in seconds for each refill period
CREATE TABLE subscription_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    bucket_capacity BIGINT NOT NULL,
    refill_tokens BIGINT NOT NULL,
    refill_period_seconds INTEGER NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_subscription_plans_name UNIQUE (name),
    CONSTRAINT chk_bucket_capacity_positive CHECK (bucket_capacity > 0),
    CONSTRAINT chk_refill_tokens_positive CHECK (refill_tokens > 0),
    CONSTRAINT chk_refill_period_positive CHECK (refill_period_seconds > 0)
);

CREATE INDEX idx_subscription_plans_name ON subscription_plans(name);
CREATE INDEX idx_subscription_plans_active ON subscription_plans(is_active);

-- Seed Subscription Plans (Standard Token Bucket Rate Limit Defaults)
-- FREE: 60 tokens max, refills 60 tokens every 60 seconds (1 req/s sustained)
-- PRO: 600 tokens max, refills 600 tokens every 60 seconds (10 req/s sustained)
-- ENTERPRISE: 6000 tokens max, refills 6000 tokens every 60 seconds (100 req/s sustained)
INSERT INTO subscription_plans (id, name, description, bucket_capacity, refill_tokens, refill_period_seconds, is_active)
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'FREE', 'Free tier for personal projects and experimentation (60 req/min)', 60, 60, 60, TRUE),
    ('a0000000-0000-0000-0000-000000000002', 'PRO', 'Professional tier for production workloads and growing applications (600 req/min)', 600, 600, 60, TRUE),
    ('a0000000-0000-0000-0000-000000000003', 'ENTERPRISE', 'Enterprise tier for high-throughput mission-critical services (6000 req/min)', 6000, 6000, 60, TRUE);
