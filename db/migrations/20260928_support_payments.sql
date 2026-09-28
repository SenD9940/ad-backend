-- Apply after 20260928_customer_support_requests.sql, before deploying api/admin-api.
-- No payment keys, real orders, or customer charges are created by this migration.
CREATE TABLE support_payments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id VARCHAR(64) NOT NULL,
    ticket_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    customer_user_id BIGINT NOT NULL,
    customer_key VARCHAR(50) NOT NULL,
    amount_krw BIGINT NOT NULL,
    payment_key VARCHAR(1024) NULL,
    payment_key_hash VARCHAR(64) NULL,
    status VARCHAR(30) NOT NULL,
    verification_revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    confirmed_at DATETIME(6) NULL,
    last_checked_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_support_payment_order UNIQUE (order_id),
    CONSTRAINT uk_support_payment_key_hash UNIQUE (payment_key_hash),
    INDEX idx_support_payment_ticket (ticket_id, id),
    INDEX idx_support_payment_status (status, updated_at)
);
