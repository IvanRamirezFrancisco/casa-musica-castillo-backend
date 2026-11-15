-- ============================================================
-- V12: Crear tablas del módulo de Mantenimiento de BD
--      maintenance_logs  — historial de operaciones VACUUM/REINDEX/ANALYZE
--      maintenance_config — configuración del scheduler automático
-- ============================================================
-- ── maintenance_logs ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS maintenance_logs (
    id BIGSERIAL PRIMARY KEY,
    operation VARCHAR(50) NOT NULL,
    -- VACUUM_ANALYZE | REINDEX | ANALYZE
    target_name VARCHAR(100) NOT NULL,
    -- nombre de la tabla/índice
    target_type VARCHAR(20) NOT NULL,
    -- TABLE | INDEX | DATABASE
    executed_by VARCHAR(255),
    -- usuario o SISTEMA_AUTOMATICO
    executed_at TIMESTAMP NOT NULL,
    -- inicio de la operación
    rows_before INTEGER,
    -- dead tuples antes
    rows_after INTEGER,
    -- dead tuples después
    rows_affected INTEGER GENERATED ALWAYS AS (
        COALESCE(rows_before, 0) - COALESCE(rows_after, 0)
    ) STORED,
    -- calculado por PostgreSQL
    duration_ms INTEGER,
    -- duración en ms
    status VARCHAR(20) NOT NULL,
    -- IN_PROGRESS | SUCCESS | ERROR
    error_message TEXT,
    notes TEXT
);
-- Índices para consultas frecuentes del panel de historial
CREATE INDEX IF NOT EXISTS idx_ml_executed_at ON maintenance_logs (executed_at DESC);
CREATE INDEX IF NOT EXISTS idx_ml_target_name ON maintenance_logs (target_name);
CREATE INDEX IF NOT EXISTS idx_ml_operation ON maintenance_logs (operation);
-- ── maintenance_config ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS maintenance_config (
    id BIGSERIAL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    frequency_hours INTEGER NOT NULL DEFAULT 6,
    preferred_hour INTEGER NOT NULL DEFAULT 2,
    vacuum_threshold_dead_tuples INTEGER NOT NULL DEFAULT 20,
    vacuum_threshold_bloat_pct NUMERIC(5, 2) NOT NULL DEFAULT 30.00,
    last_auto_execution TIMESTAMP,
    next_scheduled_execution TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
-- Insertar la fila de configuración por defecto (id=1) si no existe.
-- ON CONFLICT DO NOTHING garantiza idempotencia en re-ejecuciones o migraciones parciales.
INSERT INTO maintenance_config (
        id,
        enabled,
        frequency_hours,
        preferred_hour,
        vacuum_threshold_dead_tuples,
        vacuum_threshold_bloat_pct,
        created_at,
        updated_at
    )
VALUES (
        1,
        FALSE,
        6,
        2,
        20,
        30.00,
        NOW(),
        NOW()
    ) ON CONFLICT (id) DO NOTHING;