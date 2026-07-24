-- Run once after 001-security-batch1.sql and before starting the prod profile.
-- Only SHA-256 token hashes are stored; raw access and refresh tokens are never persisted.
CREATE TABLE IF NOT EXISTS auth_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    token_type VARCHAR(16) NOT NULL,
    family_id VARCHAR(36) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_token_hash (token_hash),
    KEY idx_auth_token_user (user_id),
    KEY idx_auth_token_family (family_id),
    KEY idx_auth_token_expiry (expires_at),
    CONSTRAINT fk_auth_token_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE
);
