-- MySQL 8. 기존 플랫폼 공통 테이블 마이그레이션 이후 실행합니다.
-- 운영 데이터는 보존합니다. 실행 전 백업하고 오류 시 중단합니다. 재실행 가능합니다.
-- OAuth 토큰은 애플리케이션의 기존 AES-GCM 암호화로 저장됩니다.

SELECT column_type, is_nullable, column_default, column_comment
INTO @imweb_type, @imweb_nullable, @imweb_default, @imweb_comment
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'platform_connections' AND column_name = 'provider_type';
SET @imweb_sql = IF(LEFT(@imweb_type, 5) = 'enum(' AND LOCATE('''IMWEB''', @imweb_type) = 0,
    CONCAT('ALTER TABLE platform_connections MODIFY COLUMN provider_type ',
        LEFT(@imweb_type, CHAR_LENGTH(@imweb_type) - 1), ',''IMWEB'')',
        IF(@imweb_nullable = 'YES', ' NULL', ' NOT NULL'),
        IF(@imweb_default IS NULL, IF(@imweb_nullable = 'YES', ' DEFAULT NULL', ''), CONCAT(' DEFAULT ', QUOTE(@imweb_default))),
        ' COMMENT ', QUOTE(@imweb_comment)), 'SELECT 1');
PREPARE imweb_stmt FROM @imweb_sql;
EXECUTE imweb_stmt;
DEALLOCATE PREPARE imweb_stmt;

SELECT column_type, is_nullable, column_default, column_comment
INTO @imweb_type, @imweb_nullable, @imweb_default, @imweb_comment
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'platform_assets' AND column_name = 'platform_type';
SET @imweb_sql = IF(LEFT(@imweb_type, 5) = 'enum(' AND LOCATE('''IMWEB''', @imweb_type) = 0,
    CONCAT('ALTER TABLE platform_assets MODIFY COLUMN platform_type ',
        LEFT(@imweb_type, CHAR_LENGTH(@imweb_type) - 1), ',''IMWEB'')',
        IF(@imweb_nullable = 'YES', ' NULL', ' NOT NULL'),
        IF(@imweb_default IS NULL, IF(@imweb_nullable = 'YES', ' DEFAULT NULL', ''), CONCAT(' DEFAULT ', QUOTE(@imweb_default))),
        ' COMMENT ', QUOTE(@imweb_comment)), 'SELECT 1');
PREPARE imweb_stmt FROM @imweb_sql;
EXECUTE imweb_stmt;
DEALLOCATE PREPARE imweb_stmt;

SELECT column_type, is_nullable, column_default, column_comment
INTO @imweb_type, @imweb_nullable, @imweb_default, @imweb_comment
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'platform_assets' AND column_name = 'asset_type';
SET @imweb_sql = IF(LEFT(@imweb_type, 5) = 'enum(' AND LOCATE('''STORE''', @imweb_type) = 0,
    CONCAT('ALTER TABLE platform_assets MODIFY COLUMN asset_type ',
        LEFT(@imweb_type, CHAR_LENGTH(@imweb_type) - 1), ',''STORE'')',
        IF(@imweb_nullable = 'YES', ' NULL', ' NOT NULL'),
        IF(@imweb_default IS NULL, IF(@imweb_nullable = 'YES', ' DEFAULT NULL', ''), CONCAT(' DEFAULT ', QUOTE(@imweb_default))),
        ' COMMENT ', QUOTE(@imweb_comment)), 'SELECT 1');
PREPARE imweb_stmt FROM @imweb_sql;
EXECUTE imweb_stmt;
DEALLOCATE PREPARE imweb_stmt;

SELECT column_type INTO @imweb_connection_id_type FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'platform_connections' AND column_name = 'id';
SET @imweb_sql = CONCAT('CREATE TABLE IF NOT EXISTS imweb_connections (',
    'connection_id ', @imweb_connection_id_type, ' NOT NULL, ',
    'client_id VARCHAR(255) NOT NULL, access_token TEXT NOT NULL, refresh_token TEXT NOT NULL, ',
    'expires_at DATETIME(6) NOT NULL, granted_scopes VARCHAR(1000) NULL, credential_version BIGINT NOT NULL DEFAULT 0, ',
    'PRIMARY KEY (connection_id), CONSTRAINT fk_imweb_connections_connection ',
    'FOREIGN KEY (connection_id) REFERENCES platform_connections (id)) ENGINE=InnoDB');
PREPARE imweb_stmt FROM @imweb_sql;
EXECUTE imweb_stmt;
DEALLOCATE PREPARE imweb_stmt;

SELECT column_type INTO @imweb_asset_id_type FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'platform_assets' AND column_name = 'id';
SET @imweb_sql = CONCAT('CREATE TABLE IF NOT EXISTS imweb_assets (',
    'asset_id ', @imweb_asset_id_type, ' NOT NULL, ',
    'site_code VARCHAR(100) NOT NULL, unit_code VARCHAR(100) NOT NULL, currency VARCHAR(3) NOT NULL, store_url VARCHAR(2048) NULL, ',
    'PRIMARY KEY (asset_id), CONSTRAINT fk_imweb_assets_asset ',
    'FOREIGN KEY (asset_id) REFERENCES platform_assets (id)) ENGINE=InnoDB');
PREPARE imweb_stmt FROM @imweb_sql;
EXECUTE imweb_stmt;
DEALLOCATE PREPARE imweb_stmt;
