-- MySQL 8.0.16+: 20260923_naver_oauth_core.sql 이후 같은 세션에서 실행합니다.
-- 기존 연결은 MANUAL로 유지합니다. 실제 DB에는 자동 실행하지 않습니다.
-- 수동 시크릿은 유지하며 SOLUTION 연결에서만 서버 자격 증명을 참조합니다.
ALTER TABLE naver_connections MODIFY COLUMN client_id VARCHAR(255) NULL,
    MODIFY COLUMN client_secret TEXT NULL, MODIFY COLUMN access_token TEXT NULL,
    MODIFY COLUMN expires_at DATETIME(6) NULL;

SET @naver_solution_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'naver_connections' AND column_name = 'credential_source'), 'SELECT 1', 'ALTER TABLE naver_connections ADD COLUMN credential_source ENUM(''MANUAL'', ''SOLUTION'') NOT NULL DEFAULT ''MANUAL''');
PREPARE naver_solution_stmt FROM @naver_solution_ddl;
EXECUTE naver_solution_stmt;
DEALLOCATE PREPARE naver_solution_stmt;

SET @naver_solution_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'naver_connections' AND column_name = 'application_ref'), 'SELECT 1', 'ALTER TABLE naver_connections ADD COLUMN application_ref VARCHAR(128) NULL');
PREPARE naver_solution_stmt FROM @naver_solution_ddl;
EXECUTE naver_solution_stmt;
DEALLOCATE PREPARE naver_solution_stmt;

SET @naver_solution_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'naver_connections' AND column_name = 'solution_subscription_id'), 'SELECT 1', 'ALTER TABLE naver_connections ADD COLUMN solution_subscription_id BIGINT NULL');
PREPARE naver_solution_stmt FROM @naver_solution_ddl;
EXECUTE naver_solution_stmt;
DEALLOCATE PREPARE naver_solution_stmt;

SET @naver_solution_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'naver_connections' AND column_name = 'bound_subscription_generation'), 'SELECT 1', 'ALTER TABLE naver_connections ADD COLUMN bound_subscription_generation BIGINT NULL');
PREPARE naver_solution_stmt FROM @naver_solution_ddl;
EXECUTE naver_solution_stmt;
DEALLOCATE PREPARE naver_solution_stmt;

SET @naver_solution_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'naver_connections' AND column_name = 'credential_version'), 'SELECT 1', 'ALTER TABLE naver_connections ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0');
PREPARE naver_solution_stmt FROM @naver_solution_ddl;
EXECUTE naver_solution_stmt;
DEALLOCATE PREPARE naver_solution_stmt;

-- FK 타입을 신규 구독 테이블의 실제 ID와 일치시킵니다.
SELECT column_type INTO @naver_solution_parent_type FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'naver_solution_subscriptions' AND column_name = 'id';
SET @naver_solution_ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'naver_connections' AND constraint_name = 'fk_naver_solution_subscription'), 'SELECT 1', CONCAT('ALTER TABLE naver_connections MODIFY COLUMN solution_subscription_id ', @naver_solution_parent_type, ' NULL, ADD CONSTRAINT fk_naver_solution_subscription FOREIGN KEY (solution_subscription_id) REFERENCES naver_solution_subscriptions(id)'));
PREPARE naver_solution_stmt FROM @naver_solution_ddl;
EXECUTE naver_solution_stmt;
DEALLOCATE PREPARE naver_solution_stmt;

CREATE TABLE IF NOT EXISTS naver_solution_event_inbox (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  solution_id VARCHAR(128) NOT NULL, event_id VARCHAR(255) NOT NULL, change_type VARCHAR(64) NOT NULL,
  account_uid VARCHAR(255) NOT NULL, account_mapping_id VARCHAR(128) NULL,
  state VARCHAR(30) NOT NULL, received_at TIMESTAMP(6) NOT NULL, checked_at TIMESTAMP(6) NULL, next_attempt_at TIMESTAMP(6) NULL,
  attempts INT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_naver_solution_event_kind UNIQUE (solution_id, event_id, change_type),
  KEY ix_naver_solution_event_pending (state, next_attempt_at, id)
) ENGINE=InnoDB;

SET @naver_solution_ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'naver_connections' AND constraint_name = 'ck_naver_solution_credentials'), 'SELECT 1', 'ALTER TABLE naver_connections ADD CONSTRAINT ck_naver_solution_credentials CHECK ((credential_source = ''MANUAL'' AND client_id IS NOT NULL AND client_secret IS NOT NULL AND solution_subscription_id IS NULL AND application_ref IS NULL AND bound_subscription_generation IS NULL) OR (credential_source = ''SOLUTION'' AND client_id IS NULL AND client_secret IS NULL AND application_ref IS NOT NULL AND solution_subscription_id IS NOT NULL AND bound_subscription_generation IS NOT NULL AND bound_subscription_generation > 0 AND token_type = ''SELLER'' AND account_id IS NOT NULL))');
PREPARE naver_solution_stmt FROM @naver_solution_ddl;
EXECUTE naver_solution_stmt;
DEALLOCATE PREPARE naver_solution_stmt;

SET @naver_solution_ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'naver_connections' AND constraint_name = 'ck_naver_solution_token_pair'), 'SELECT 1', 'ALTER TABLE naver_connections ADD CONSTRAINT ck_naver_solution_token_pair CHECK ((access_token IS NULL AND expires_at IS NULL) OR (access_token IS NOT NULL AND expires_at IS NOT NULL))');
PREPARE naver_solution_stmt FROM @naver_solution_ddl;
EXECUTE naver_solution_stmt;
DEALLOCATE PREPARE naver_solution_stmt;
