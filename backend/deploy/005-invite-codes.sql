-- CloudMind invite-code MVP
-- Apply after 004-security-batch4.sql and before starting the new backend.

CREATE TABLE invite_code_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_no VARCHAR(48) NOT NULL,
    target_role VARCHAR(16) NOT NULL,
    total_count INT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    note VARCHAR(200) NULL,
    export_format VARCHAR(8) NOT NULL,
    created_by_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_invite_code_batch_no UNIQUE (batch_no),
    CONSTRAINT fk_invite_batch_created_by
        FOREIGN KEY (created_by_id) REFERENCES app_user (id)
        ON DELETE SET NULL,
    INDEX idx_invite_batch_created (created_at),
    INDEX idx_invite_batch_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE invite_code (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    redeemed_by_id BIGINT NULL,
    redeemed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_invite_code_hash UNIQUE (code_hash),
    CONSTRAINT fk_invite_code_batch
        FOREIGN KEY (batch_id) REFERENCES invite_code_batch (id),
    CONSTRAINT fk_invite_code_redeemed_by
        FOREIGN KEY (redeemed_by_id) REFERENCES app_user (id)
        ON DELETE SET NULL,
    INDEX idx_invite_code_batch (batch_id),
    INDEX idx_invite_code_redeemed (redeemed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
