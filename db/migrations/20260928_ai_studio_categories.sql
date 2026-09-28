-- Apply after 20260928_ai_studio_templates.sql and before deploying category-aware API servers.
-- Preserve each existing template's category; blank labels become 미분류.
CREATE TABLE ai_studio_categories (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_ai_studio_category_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO ai_studio_categories (name, created_at, updated_at)
SELECT MIN(normalized_name), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM (
    SELECT CONVERT(COALESCE(NULLIF(TRIM(category), ''), '미분류') USING utf8mb4)
        COLLATE utf8mb4_unicode_ci AS normalized_name
    FROM ai_templates
) normalized
GROUP BY normalized_name;

ALTER TABLE ai_templates ADD COLUMN category_id BIGINT NULL;

UPDATE ai_templates template
JOIN ai_studio_categories category
    ON category.name = CONVERT(COALESCE(NULLIF(TRIM(template.category), ''), '미분류') USING utf8mb4)
        COLLATE utf8mb4_unicode_ci
SET template.category_id = category.id;

ALTER TABLE ai_templates
    MODIFY category_id BIGINT NOT NULL,
    ADD INDEX idx_ai_template_category (category_id, published, kind, id),
    ADD CONSTRAINT fk_ai_template_category FOREIGN KEY (category_id)
        REFERENCES ai_studio_categories (id) ON DELETE RESTRICT,
    DROP COLUMN category;
