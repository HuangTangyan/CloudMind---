-- CloudMind campus account onboarding
-- Apply after 007-membership-validity.sql and before starting the backend with ddl-auto=validate.

ALTER TABLE app_user
    ADD COLUMN username_changed_at DATETIME(6) NULL AFTER username;

-- Existing accounts keep their current usernames. Only accounts created by an
-- administrator after this migration must replace their temporary usernames.
UPDATE app_user
SET username_changed_at = COALESCE(created_at, CURRENT_TIMESTAMP(6))
WHERE username_changed_at IS NULL;
