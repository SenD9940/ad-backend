CREATE TABLE workspace_invitations (
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT uk_workspace_invitations_token_hash UNIQUE (token_hash)
);
