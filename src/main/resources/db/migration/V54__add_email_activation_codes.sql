ALTER TABLE auth.verification_tokens
    ADD COLUMN code_digest VARCHAR(64),
    ADD COLUMN code_attempts SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE auth.users ADD COLUMN pending_self_registration BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_verification_tokens_user_type_created
    ON auth.verification_tokens (user_id, token_type, created_at DESC);
