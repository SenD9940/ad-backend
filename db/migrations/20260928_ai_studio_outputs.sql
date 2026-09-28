-- Apply after 20260928_ai_studio_templates.sql. No provider keys are stored in this table.
CREATE TABLE ai_studio_outputs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    idempotency_key VARCHAR(36) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    title VARCHAR(150) NOT NULL,
    status VARCHAR(20) NOT NULL,
    image_key VARCHAR(512) NULL,
    image_content_type VARCHAR(30) NULL,
    detail_html TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_ai_output_request (workspace_id, created_by, idempotency_key),
    INDEX idx_ai_output_workspace (workspace_id, status, kind, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
