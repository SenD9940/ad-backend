-- Apply to the shared service database before deploying either api or admin-api.
-- Monetary values are manually recorded KRW support fees; no payment provider is invoked.
CREATE TABLE support_tickets (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    customer_user_id BIGINT NOT NULL,
    assigned_admin_id BIGINT NOT NULL,
    title VARCHAR(150) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    access_mode VARCHAR(20) NOT NULL,
    amount_krw BIGINT NOT NULL,
    payment_status VARCHAR(20) NOT NULL,
    payment_reference VARCHAR(200) NULL,
    payment_recorded_at DATETIME(6) NULL,
    payment_recorded_by BIGINT NULL,
    status VARCHAR(20) NOT NULL,
    approved_at DATETIME(6) NULL,
    approval_expires_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_support_ticket_customer (customer_user_id, id),
    INDEX idx_support_ticket_workspace (workspace_id, id),
    INDEX idx_support_ticket_status (status, id)
);

CREATE TABLE support_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    admin_user_id BIGINT NOT NULL,
    customer_user_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    admin_auth_version BIGINT NOT NULL,
    customer_auth_version BIGINT NOT NULL,
    access_mode VARCHAR(20) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    CONSTRAINT uk_support_session_token UNIQUE (token_hash),
    INDEX idx_support_session_ticket (ticket_id, id),
    INDEX idx_support_session_customer (customer_user_id, ended_at)
);

CREATE TABLE support_actions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    ticket_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    customer_user_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    path VARCHAR(500) NOT NULL,
    status_code INT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    INDEX idx_support_action_ticket (ticket_id, id),
    INDEX idx_support_action_session (session_id, id)
);
