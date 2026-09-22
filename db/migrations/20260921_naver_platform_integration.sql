-- MySQL 8: Meta 공통 구조 마이그레이션 적용 후 대상 데이터베이스에서 실행합니다.
-- 같은 연결에서 전체 파일을 순서대로 실행하고, 오류가 발생하면 중단합니다.
-- 프로시저/DELIMITER를 사용하지 않으며 재실행할 수 있습니다.
-- 기존 공통 테이블의 connection_id NULL 허용 및 외래 키 SET NULL 정책은 변경하지 않습니다.

-- ENUM으로 관리 중인 경우 STORE만 추가하고 기존 값/NULL 허용/기본값/설명은 유지합니다.
-- VARCHAR 컬럼이면 STORE 저장이 가능하므로 타입을 변경하지 않습니다.
SELECT column_type, is_nullable, column_default, column_comment
INTO @naver_asset_type, @naver_asset_nullable, @naver_asset_default, @naver_asset_comment
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'platform_assets' AND column_name = 'asset_type';

SET @naver_asset_type_sql = IF(
    LEFT(@naver_asset_type, 5) = 'enum(' AND LOCATE('''STORE''', @naver_asset_type) = 0,
    CONCAT(
        'ALTER TABLE platform_assets MODIFY COLUMN asset_type ',
        LEFT(@naver_asset_type, CHAR_LENGTH(@naver_asset_type) - 1), ',''STORE'')',
        IF(@naver_asset_nullable = 'YES', ' NULL', ' NOT NULL'),
        IF(@naver_asset_default IS NULL,
           IF(@naver_asset_nullable = 'YES', ' DEFAULT NULL', ''),
           CONCAT(' DEFAULT ', QUOTE(@naver_asset_default))),
        ' COMMENT ', QUOTE(@naver_asset_comment)
    ),
    'SELECT 1'
);
PREPARE naver_asset_type_stmt FROM @naver_asset_type_sql;
EXECUTE naver_asset_type_stmt;
DEALLOCATE PREPARE naver_asset_type_stmt;

-- 기존 ID가 BIGINT UNSIGNED인 경우에도 외래 키 양쪽의 타입을 동일하게 맞춥니다.
SELECT column_type INTO @naver_connection_id_type
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'platform_connections' AND column_name = 'id';

SET @naver_connection_sql = CONCAT(
    'CREATE TABLE IF NOT EXISTS naver_connections (',
    'connection_id ', @naver_connection_id_type, ' NOT NULL, ',
    'client_id VARCHAR(255) NOT NULL, ',
    'client_secret TEXT NOT NULL, ',
    'token_type ENUM(''SELF'', ''SELLER'') NOT NULL, ',
    'account_id VARCHAR(255) NULL, ',
    'access_token TEXT NOT NULL, ',
    'expires_at DATETIME(6) NOT NULL, ',
    'PRIMARY KEY (connection_id), ',
    'CONSTRAINT fk_naver_connections_connection ',
    'FOREIGN KEY (connection_id) REFERENCES platform_connections (id)',
    ') ENGINE=InnoDB'
);
PREPARE naver_connection_stmt FROM @naver_connection_sql;
EXECUTE naver_connection_stmt;
DEALLOCATE PREPARE naver_connection_stmt;

SELECT column_type INTO @naver_asset_id_type
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'platform_assets' AND column_name = 'id';

SET @naver_asset_sql = CONCAT(
    'CREATE TABLE IF NOT EXISTS naver_assets (',
    'asset_id ', @naver_asset_id_type, ' NOT NULL, ',
    'channel_type VARCHAR(30) NOT NULL, ',
    'channel_url VARCHAR(2048) NULL, ',
    'PRIMARY KEY (asset_id), ',
    'CONSTRAINT fk_naver_assets_asset ',
    'FOREIGN KEY (asset_id) REFERENCES platform_assets (id)',
    ') ENGINE=InnoDB'
);
PREPARE naver_asset_stmt FROM @naver_asset_sql;
EXECUTE naver_asset_stmt;
DEALLOCATE PREPARE naver_asset_stmt;
