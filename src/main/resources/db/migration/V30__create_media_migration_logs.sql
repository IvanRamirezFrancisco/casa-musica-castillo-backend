-- ============================================================
-- V30: Tabla de auditoría para migración de medios a Cloudinary
-- Schema: ops
-- Propósito: Registrar historial de migraciones de imágenes
--            locales a Cloudinary (Fase 6D).
-- NOTA: Esta migración solo crea estructura de BD.
--       NO ejecuta subidas a Cloudinary.
-- ============================================================

CREATE TABLE IF NOT EXISTS ops.media_migration_logs (
    id              BIGSERIAL PRIMARY KEY,
    entity_type     VARCHAR(50)  NOT NULL,   -- 'PRODUCT_IMAGE_URL', 'PRODUCT_GALLERY_IMAGE', 'BRAND_LOGO'
    entity_id       BIGINT       NOT NULL,   -- product_id o brand_id
    old_url         TEXT,                    -- URL original (local)
    new_url         TEXT,                    -- URL nueva (Cloudinary)
    public_id       VARCHAR(500),            -- public_id en Cloudinary
    status          VARCHAR(50)  NOT NULL,   -- Ver estados abajo
    error_message   TEXT,                    -- Detalle del error si status=FAILED
    action          VARCHAR(100),            -- 'DRY_RUN', 'EXECUTE', 'REPORT'
    migrated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Índices para consultas rápidas por entidad, estado y fecha
CREATE INDEX IF NOT EXISTS idx_media_migration_entity_type
    ON ops.media_migration_logs (entity_type);

CREATE INDEX IF NOT EXISTS idx_media_migration_entity_id
    ON ops.media_migration_logs (entity_id);

CREATE INDEX IF NOT EXISTS idx_media_migration_status
    ON ops.media_migration_logs (status);

CREATE INDEX IF NOT EXISTS idx_media_migration_migrated_at
    ON ops.media_migration_logs (migrated_at DESC);

COMMENT ON TABLE ops.media_migration_logs IS
    'Historial de migración de imágenes locales a Cloudinary (Fase 6D). '
    'Solo registra eventos de análisis y migración. No contiene credenciales.';

COMMENT ON COLUMN ops.media_migration_logs.status IS
    'Estados posibles: SCANNED, DRY_RUN, MIGRATED, '
    'SKIPPED_ALREADY_CLOUDINARY, SKIPPED_ALREADY_HAS_GALLERY, '
    'MISSING_LOCAL_FILE, INVALID_FILE, INVALID_PATH, FAILED, '
    'CLOUDINARY_WITHOUT_GALLERY, ORPHAN_CANDIDATE';
