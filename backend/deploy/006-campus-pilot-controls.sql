-- CloudMind campus pilot controls
-- Apply after 005-invite-codes.sql and before starting this backend with ddl-auto=validate.

ALTER TABLE invite_code_batch
    ADD COLUMN revoked_by_id BIGINT NULL AFTER created_at,
    ADD COLUMN revoked_at DATETIME(6) NULL AFTER revoked_by_id,
    ADD COLUMN revoke_reason VARCHAR(200) NULL AFTER revoked_at,
    ADD CONSTRAINT fk_invite_batch_revoked_by
        FOREIGN KEY (revoked_by_id) REFERENCES app_user (id)
        ON DELETE SET NULL,
    ADD INDEX idx_invite_batch_revoked (revoked_at);

ALTER TABLE invite_code
    ADD COLUMN revoked_by_id BIGINT NULL AFTER redeemed_at,
    ADD COLUMN revoked_at DATETIME(6) NULL AFTER revoked_by_id,
    ADD COLUMN revoke_reason VARCHAR(200) NULL AFTER revoked_at,
    ADD CONSTRAINT fk_invite_code_revoked_by
        FOREIGN KEY (revoked_by_id) REFERENCES app_user (id)
        ON DELETE SET NULL,
    ADD INDEX idx_invite_code_revoked (revoked_at);

CREATE TABLE ai_daily_usage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    usage_date DATE NOT NULL,
    used_count INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_daily_usage_user_date UNIQUE (user_id, usage_date),
    CONSTRAINT fk_ai_daily_usage_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE,
    INDEX idx_ai_daily_usage_date (usage_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE invite_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_id BIGINT NULL,
    actor_username VARCHAR(64) NOT NULL,
    action VARCHAR(40) NOT NULL,
    batch_no VARCHAR(48) NULL,
    code_fingerprint VARCHAR(16) NULL,
    result VARCHAR(16) NOT NULL,
    reason VARCHAR(200) NULL,
    client_ip VARCHAR(64) NULL,
    user_agent VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_invite_audit_created (created_at),
    INDEX idx_invite_audit_actor (actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
