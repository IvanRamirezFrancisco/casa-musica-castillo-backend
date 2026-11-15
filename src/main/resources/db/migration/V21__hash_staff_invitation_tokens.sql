-- ============================================================================
-- V21: Migrar tokens de invitación a hash SHA-256
-- SEGURIDAD: Los tokens en texto plano ya no se almacenan.
--            Se guardan como hash SHA-256 (hex, 64 chars).
-- ============================================================================
-- Habilitar la extensión criptográfica de PostgreSQL (requerido para la función digest)
CREATE EXTENSION IF NOT EXISTS pgcrypto;
-- 1. Renombrar columna token → token_hash
ALTER TABLE staff_invitations
    RENAME COLUMN token TO token_hash;
-- 2. Convertir tokens existentes (si los hay) a SHA-256 hash
--    encode(digest(value, 'sha256'), 'hex') genera un hash hex de 64 chars
UPDATE staff_invitations
SET token_hash = encode(digest(token_hash, 'sha256'), 'hex')
WHERE token_hash IS NOT NULL
    AND LENGTH(token_hash) <> 64;
-- 3. Garantizar que el índice único se mantiene (renombrar si existe)
DROP INDEX IF EXISTS idx_staff_invitations_token;
CREATE UNIQUE INDEX IF NOT EXISTS idx_staff_invitations_token_hash ON staff_invitations (token_hash);
-- 4. Comentario de documentación
COMMENT ON COLUMN staff_invitations.token_hash IS 'SHA-256 hash del token de invitación. El token en texto plano solo se envía por email, nunca se persiste.';