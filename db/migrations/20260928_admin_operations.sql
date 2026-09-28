-- Apply to the shared service database BEFORE deploying api and admin-api.
-- Existing access tokens have implicit authVersion=0 and remain valid until revoked.
ALTER TABLE users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
-- Hibernate maps @Enumerated(STRING) to a native ENUM on MySQL. Extend the
-- accepted values before any administrator suspends a user. This also accepts
-- existing installations where the column was originally VARCHAR.
ALTER TABLE users MODIFY COLUMN status ENUM('REGISTERED', 'UNREGISTERED', 'SUSPENDED') NOT NULL;

CREATE TABLE admin_audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(60) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_id BIGINT NULL,
    reason VARCHAR(500) NOT NULL,
    before_value VARCHAR(1000) NULL,
    after_value VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_admin_audit_actor_id (actor_user_id, id),
    INDEX idx_admin_audit_target (target_type, target_id, id)
);
