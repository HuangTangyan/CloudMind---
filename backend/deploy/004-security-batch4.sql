-- CloudMind security batch 4
-- Apply after 003-security-batch3.sql and before starting the new backend.

ALTER TABLE auth_token
    ADD COLUMN client_ip VARCHAR(64) NULL AFTER revoked_at,
    ADD COLUMN user_agent VARCHAR(255) NULL AFTER client_ip,
    ADD COLUMN last_used_at DATETIME(6) NULL AFTER user_agent;

UPDATE auth_token
SET last_used_at = COALESCE(created_at, CURRENT_TIMESTAMP(6))
WHERE last_used_at IS NULL;

ALTER TABLE auth_token
    MODIFY COLUMN last_used_at DATETIME(6) NOT NULL;

CREATE INDEX idx_auth_token_user_type_created
    ON auth_token (user_id, token_type, created_at);
