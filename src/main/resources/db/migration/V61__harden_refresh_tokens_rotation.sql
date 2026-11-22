-- Harden auth.refresh_tokens for SHA-256 digest storage, rotation families, reuse detection, and revocation.
ALTER TABLE auth.refresh_tokens ADD COLUMN family_id VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE auth.refresh_tokens ADD COLUMN session_jti VARCHAR(255);
ALTER TABLE auth.refresh_tokens ADD COLUMN absolute_expires_at TIMESTAMP;
ALTER TABLE auth.refresh_tokens ADD COLUMN last_used_at TIMESTAMP;
ALTER TABLE auth.refresh_tokens ADD COLUMN rotated_at TIMESTAMP;
ALTER TABLE auth.refresh_tokens ADD COLUMN revoked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE auth.refresh_tokens ADD COLUMN revoked_at TIMESTAMP;
ALTER TABLE auth.refresh_tokens ADD COLUMN revoke_reason VARCHAR(64);
ALTER TABLE auth.refresh_tokens ADD COLUMN client_type VARCHAR(16) NOT NULL DEFAULT 'WEB'
    CHECK (client_type IN ('WEB', 'ANDROID'));
ALTER TABLE auth.refresh_tokens ADD COLUMN user_agent TEXT;
ALTER TABLE auth.refresh_tokens ADD COLUMN ip_address VARCHAR(45);

UPDATE auth.refresh_tokens
SET family_id = 'legacy-' || id,
    absolute_expires_at = expiry_date,
    revoked = TRUE,
    revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP),
    revoke_reason = COALESCE(revoke_reason, 'LEGACY_PRE_ROTATION')
WHERE family_id = '' OR absolute_expires_at IS NULL;

ALTER TABLE auth.refresh_tokens ALTER COLUMN family_id DROP DEFAULT;
ALTER TABLE auth.refresh_tokens ALTER COLUMN absolute_expires_at SET NOT NULL;

CREATE INDEX idx_refresh_tokens_user_active ON auth.refresh_tokens(user_id, revoked, expiry_date);
CREATE INDEX idx_refresh_tokens_family ON auth.refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_session_jti ON auth.refresh_tokens(session_jti);

DO $$
DECLARE fk_name text;
BEGIN
    SELECT tc.constraint_name INTO fk_name
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
      ON tc.constraint_name = kcu.constraint_name
     AND tc.table_schema = kcu.table_schema
    WHERE tc.table_schema = 'auth'
      AND tc.table_name = 'refresh_tokens'
      AND tc.constraint_type = 'FOREIGN KEY'
      AND kcu.column_name = 'user_id'
    LIMIT 1;
    IF fk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE auth.refresh_tokens DROP CONSTRAINT %I', fk_name);
    END IF;
END $$;

ALTER TABLE auth.refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_user
    FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;
