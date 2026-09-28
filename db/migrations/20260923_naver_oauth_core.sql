-- MySQL 8. Run after 20260921_naver_platform_integration.sql, before
-- 20260923_naver_solution_connections.sql. This script does not approve subscriptions.
-- Tables contain encrypted short-lived proofs; restrict backups and retention accordingly.
CREATE TABLE IF NOT EXISTS naver_solution_subscriptions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    application_ref VARCHAR(128) NOT NULL,
    solution_id VARCHAR(128) NOT NULL,
    account_uid VARCHAR(255) NOT NULL,
    provider_subscription_id VARCHAR(255) NOT NULL,
    account_mapping_id VARCHAR(128) NOT NULL,
    status VARCHAR(30) NOT NULL,
    generation BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    verified_at DATETIME(6) NULL,
    CONSTRAINT uk_naver_solution_seller UNIQUE (application_ref, account_uid)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS naver_subscription_operations (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    application_ref VARCHAR(128) NOT NULL,
    account_uid VARCHAR(255) NOT NULL,
    provider_subscription_id VARCHAR(255) NOT NULL,
    operation_type VARCHAR(30) NOT NULL,
    account_mapping_id VARCHAR(128) NOT NULL,
    status VARCHAR(40) NOT NULL,
    owner_attempt_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_naver_approval_lifecycle UNIQUE (application_ref, account_uid, provider_subscription_id, operation_type)
) ENGINE=InnoDB;
-- Workspace/user/connection IDs are immutable snapshots, deliberately not cascading
-- foreign keys: operation outcome records must survive workspace removal.
CREATE TABLE IF NOT EXISTS naver_authorization_attempts (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reconnect_connection_id BIGINT NULL,
    expected_account_uid VARCHAR(255) NULL,
    status VARCHAR(40) NOT NULL,
    browser_hash VARCHAR(64) NOT NULL,
    state_hash VARCHAR(64) NOT NULL,
    provider_state TEXT NULL,
    launch_hash VARCHAR(64) NULL,
    launch_expires_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    confirmed_at DATETIME(6) NULL,
    next_reconcile_at DATETIME(6) NULL,
    recovery_attempts INT NOT NULL DEFAULT 0,
    review_revision BIGINT NOT NULL DEFAULT 0,
    idempotency_hash VARCHAR(64) NULL,
    confirmation_hash VARCHAR(64) NULL,
    proof_hash VARCHAR(64) NULL,
    encrypted_proof TEXT NULL,
    account_uid VARCHAR(255) NULL,
    account_id VARCHAR(255) NULL,
    seller_name VARCHAR(255) NULL,
    store_url VARCHAR(2048) NULL,
    provider_subscription_id VARCHAR(255) NULL,
    plan_name VARCHAR(255) NULL,
    plan_id VARCHAR(255) NULL,
    requires_approval BOOLEAN NOT NULL DEFAULT FALSE,
    subscription_id BIGINT NULL,
    subscription_generation BIGINT NOT NULL DEFAULT 0,
    operation_id VARCHAR(64) NULL,
    connection_id BIGINT NULL,
    error_message VARCHAR(500) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_naver_auth_launch UNIQUE (launch_hash),
    CONSTRAINT uk_naver_auth_proof UNIQUE (proof_hash),
    INDEX ix_naver_auth_expiry (expires_at),
    INDEX ix_naver_auth_operation (operation_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS naver_marketplace_receipts (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    browser_hash VARCHAR(64) NOT NULL,
    account_uid VARCHAR(255) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
