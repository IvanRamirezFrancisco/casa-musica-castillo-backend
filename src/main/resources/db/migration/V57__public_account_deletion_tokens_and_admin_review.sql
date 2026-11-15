-- Solicitudes públicas sin sesión (tokens de verificación SHA-256 de un solo uso),
-- trazabilidad de revisión administrativa y desvinculación segura de pedidos concluidos.

CREATE TABLE IF NOT EXISTS auth.account_deletion_tokens (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    request_ip VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_account_deletion_tokens_user_unused
    ON auth.account_deletion_tokens(user_id)
    WHERE used_at IS NULL;

ALTER TABLE auth.account_deletion_requests
    ADD COLUMN IF NOT EXISTS initiation_channel VARCHAR(32) NOT NULL DEFAULT 'AUTHENTICATED',
    ADD COLUMN IF NOT EXISTS review_reason VARCHAR(120),
    ADD COLUMN IF NOT EXISTS reviewed_by BIGINT REFERENCES auth.users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolution_notes VARCHAR(500);

ALTER TABLE sales.orders
    ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE sales.coupon_usage
    ALTER COLUMN user_id DROP NOT NULL;
