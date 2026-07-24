-- CloudMind time-limited membership validity
-- Apply after 006-campus-pilot-controls.sql and before starting the backend with ddl-auto=validate.

ALTER TABLE app_user
    ADD COLUMN membership_expires_at DATETIME(6) NULL AFTER role,
    ADD COLUMN membership_fallback_role VARCHAR(20) NULL AFTER membership_expires_at,
    ADD COLUMN membership_fallback_quota_bytes BIGINT NULL AFTER membership_fallback_role,
    ADD INDEX idx_app_user_membership_expiry (membership_expires_at);

ALTER TABLE invite_code_batch
    ADD COLUMN membership_days INT NOT NULL DEFAULT 30 AFTER target_role;
