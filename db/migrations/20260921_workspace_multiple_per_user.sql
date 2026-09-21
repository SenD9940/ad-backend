-- MySQL: 대상 데이터베이스를 선택한 뒤 실행합니다.
-- user_id 외래 키와 조회 성능을 위한 일반 인덱스를 먼저 확보합니다.
SET @workspace_owner_index_sql = IF(
    EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'workspaces'
          AND non_unique = 1
          AND seq_in_index = 1
          AND column_name = 'user_id'
    ),
    'SELECT 1',
    'ALTER TABLE `workspaces` ADD INDEX `idx_workspaces_user_id` (`user_id`)'
);
PREPARE workspace_owner_index_stmt FROM @workspace_owner_index_sql;
EXECUTE workspace_owner_index_stmt;
DEALLOCATE PREPARE workspace_owner_index_stmt;

-- Hibernate가 생성한 이름에 의존하지 않고 user_id 단일 컬럼 UNIQUE만 제거합니다.
-- 기본 키, 복합 UNIQUE, 외래 키, 데이터는 유지하며 재실행도 가능합니다.
SELECT GROUP_CONCAT(CONCAT('DROP INDEX `', REPLACE(index_name, '`', '``'), '`') SEPARATOR ', ')
INTO @workspace_owner_unique_drops
FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'workspaces'
      AND non_unique = 0
      AND index_name <> 'PRIMARY'
    GROUP BY index_name
    HAVING COUNT(*) = 1 AND MAX(column_name) = 'user_id'
) AS owner_unique_indexes;

SET @workspace_owner_unique_sql = IF(
    @workspace_owner_unique_drops IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE `workspaces` ', @workspace_owner_unique_drops)
);
PREPARE workspace_owner_unique_stmt FROM @workspace_owner_unique_sql;
EXECUTE workspace_owner_unique_stmt;
DEALLOCATE PREPARE workspace_owner_unique_stmt;
