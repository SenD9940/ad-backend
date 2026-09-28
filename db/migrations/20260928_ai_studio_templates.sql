CREATE TABLE ai_template_images (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    image_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(20) NOT NULL,
    byte_size BIGINT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    uploaded_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_ai_template_image_key UNIQUE (image_key)
);

CREATE TABLE ai_templates (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    kind VARCHAR(30) NOT NULL,
    title VARCHAR(150) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    category VARCHAR(80) NOT NULL,
    prompt VARCHAR(6000) NOT NULL,
    preview_image_key VARCHAR(512) NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    INDEX idx_ai_template_published_kind (published, kind, id)
);
