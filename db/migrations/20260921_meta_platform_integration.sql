-- MySQL 8: 대상 데이터베이스를 선택한 뒤 배포 전에 전체 파일을 스크립트로 실행합니다.
-- 같은 연결에서 순서대로 실행하고, 오류가 발생하면 중단하도록 설정합니다.
-- 구분자는 기본 세미콜론(;)입니다. 프로시저나 별도 구분자 설정은 사용하지 않습니다.
-- 기존 연결/자산과 기존 자격 증명 컬럼은 삭제하지 않습니다.
-- 기존 Meta 연결은 다시 인증해야 암호화된 meta_connections 레코드가 생성됩니다.

-- 제약을 만족하지 않는 데이터는 임시 테이블의 중복 키 오류(1062)로 DDL 전에 차단합니다.
-- 오류에 나온 검사명에 해당하는 기존 데이터를 정리한 뒤 처음부터 다시 실행합니다.
-- 임시 테이블만 생성/삭제하며, 기존 연결/자산 데이터는 변경하지 않습니다.
DROP TEMPORARY TABLE IF EXISTS tmp_meta_integration_checks;
CREATE TEMPORARY TABLE tmp_meta_integration_checks (
    check_name VARCHAR(128) NOT NULL PRIMARY KEY
);
INSERT INTO tmp_meta_integration_checks (check_name) VALUES
    ('platform_assets_has_null_required_fields'),
    ('platform_connections_has_duplicate_accounts'),
    ('platform_assets_has_duplicate_external_ids');

INSERT INTO tmp_meta_integration_checks (check_name)
SELECT 'platform_assets_has_null_required_fields'
WHERE EXISTS (
    SELECT 1 FROM platform_assets
    WHERE platform_type IS NULL OR asset_type IS NULL OR external_id IS NULL
);

INSERT INTO tmp_meta_integration_checks (check_name)
SELECT 'platform_connections_has_duplicate_accounts'
WHERE EXISTS (
    SELECT 1 FROM platform_connections
    WHERE external_account_id IS NOT NULL
    GROUP BY workspace_id, provider_type, external_account_id
    HAVING COUNT(*) > 1
);

INSERT INTO tmp_meta_integration_checks (check_name)
SELECT 'platform_assets_has_duplicate_external_ids'
WHERE EXISTS (
    SELECT 1 FROM platform_assets
    WHERE connection_id IS NOT NULL
    GROUP BY connection_id, platform_type, asset_type, external_id
    HAVING COUNT(*) > 1
);
DROP TEMPORARY TABLE tmp_meta_integration_checks;

ALTER TABLE platform_connections
    MODIFY COLUMN provider_type ENUM('META', 'THREADS', 'GOOGLE', 'NAVER', 'COUPANG') NOT NULL;

-- 구버전 토큰은 보존하되 새 연결에는 이 컬럼들을 쓰지 않으므로 NULL을 허용합니다.
-- 새 엔티티로 이미 생성된 스키마에서는 기존 컬럼이 없을 수 있습니다.
SELECT GROUP_CONCAT(
    CASE column_name
        WHEN 'access_token' THEN 'MODIFY COLUMN access_token TEXT NULL'
        WHEN 'refresh_token' THEN 'MODIFY COLUMN refresh_token TEXT NULL'
        WHEN 'expires_at' THEN 'MODIFY COLUMN expires_at DATETIME(6) NULL'
    END SEPARATOR ', '
) INTO @meta_legacy_credentials_changes
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'platform_connections'
  AND column_name IN ('access_token', 'refresh_token', 'expires_at');

SET @meta_legacy_credentials_sql = IF(
    @meta_legacy_credentials_changes IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE platform_connections ', @meta_legacy_credentials_changes)
);
PREPARE meta_legacy_credentials_stmt FROM @meta_legacy_credentials_sql;
EXECUTE meta_legacy_credentials_stmt;
DEALLOCATE PREPARE meta_legacy_credentials_stmt;

-- connection_id의 기존 NULL 허용 및 외래 키 SET NULL 정책은 유지합니다.
-- 연결이 삭제된 자산 기록을 보존하며, 연결 선택 API는 유효한 connection_id를 항상 지정합니다.
ALTER TABLE platform_assets
    MODIFY COLUMN platform_type ENUM('FACEBOOK', 'INSTAGRAM', 'THREADS', 'GOOGLE_ADS', 'NAVER_ADS', 'NAVER_SMART_STORE', 'COUPANG') NOT NULL,
    MODIFY COLUMN asset_type ENUM('AD_ACCOUNT', 'PAGE', 'PROFILE', 'STORE') NOT NULL,
    MODIFY COLUMN external_id VARCHAR(255) NOT NULL;

SET @meta_connection_unique_sql = IF(
    EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'platform_connections'
          AND index_name = 'uk_platform_connections_workspace_provider_account'
    ),
    'SELECT 1',
    'ALTER TABLE platform_connections ADD CONSTRAINT uk_platform_connections_workspace_provider_account UNIQUE (workspace_id, provider_type, external_account_id)'
);
PREPARE meta_connection_unique_stmt FROM @meta_connection_unique_sql;
EXECUTE meta_connection_unique_stmt;
DEALLOCATE PREPARE meta_connection_unique_stmt;

SET @meta_asset_unique_sql = IF(
    EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'platform_assets'
          AND index_name = 'uk_platform_assets_connection_platform_type_external'
    ),
    'SELECT 1',
    'ALTER TABLE platform_assets ADD CONSTRAINT uk_platform_assets_connection_platform_type_external UNIQUE (connection_id, platform_type, asset_type, external_id)'
);
PREPARE meta_asset_unique_stmt FROM @meta_asset_unique_sql;
EXECUTE meta_asset_unique_stmt;
DEALLOCATE PREPARE meta_asset_unique_stmt;

CREATE TABLE IF NOT EXISTS meta_connections (
    connection_id BIGINT NOT NULL,
    access_token TEXT NOT NULL,
    expires_at DATETIME(6) NULL,
    granted_scopes TEXT NULL,
    PRIMARY KEY (connection_id),
    CONSTRAINT fk_meta_connections_connection
        FOREIGN KEY (connection_id) REFERENCES platform_connections (id)
);

CREATE TABLE IF NOT EXISTS meta_assets (
    asset_id BIGINT NOT NULL,
    facebook_page_id VARCHAR(255) NULL,
    PRIMARY KEY (asset_id),
    CONSTRAINT fk_meta_assets_asset
        FOREIGN KEY (asset_id) REFERENCES platform_assets (id)
);

UPDATE platform_connections c
LEFT JOIN meta_connections m ON m.connection_id = c.id
SET c.requires_reauth = TRUE
WHERE c.provider_type = 'META' AND m.connection_id IS NULL;
