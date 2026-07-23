-- Run once before starting the application with the prod profile.
-- Existing administrators will be required to change their password after this column is added.
ALTER TABLE app_user
    ADD COLUMN password_changed_at DATETIME(6) NULL;
