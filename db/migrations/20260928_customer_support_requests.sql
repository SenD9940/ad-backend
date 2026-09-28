-- Apply after 20260928_admin_operations.sql and 20260928_support_sessions.sql.
-- Existing administrator-created tickets retain their source and assigned administrator.
ALTER TABLE support_tickets
    MODIFY COLUMN assigned_admin_id BIGINT NULL,
    ADD COLUMN request_source VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    ADD COLUMN terms_version VARCHAR(80) NULL,
    ADD COLUMN terms_snapshot TEXT NULL;
