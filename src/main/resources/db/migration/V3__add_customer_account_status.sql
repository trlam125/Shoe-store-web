-- Add account status and registration time for the admin customer-management module.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN;

UPDATE users
SET enabled = TRUE
WHERE enabled IS NULL;

ALTER TABLE users
    ALTER COLUMN enabled SET DEFAULT TRUE;
ALTER TABLE users
    ALTER COLUMN enabled SET NOT NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;

UPDATE users u
SET created_at = COALESCE(
        (SELECT MIN(o.created_at) FROM orders o WHERE o.user_id = u.id),
        CURRENT_TIMESTAMP
    )
WHERE created_at IS NULL;

ALTER TABLE users
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE users
    ALTER COLUMN created_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_users_role
    ON users(role);
CREATE INDEX IF NOT EXISTS idx_users_enabled
    ON users(enabled);
CREATE INDEX IF NOT EXISTS idx_users_created_at
    ON users(created_at);
