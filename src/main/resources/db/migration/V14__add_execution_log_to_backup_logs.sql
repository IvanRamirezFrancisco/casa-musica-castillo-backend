-- ============================================================
-- V14 — Columna execution_log en backup_logs
-- ============================================================
-- Almacena la bitácora técnica completa de cada ejecución de respaldo:
--   - Hitos internos del proceso (inicio, verificación, subida, limpieza)
--   - Salida estándar (stdout) y de errores (stderr) de pg_dump
--   - Timestamps de cada paso
--
-- Se persiste tanto en respaldos COMPLETED como en FAILED,
-- por lo que el DBA siempre tiene visibilidad completa del proceso.
-- El campo es nullable: registros históricos anteriores a esta migración
-- tendrán NULL en esta columna.
-- ============================================================
ALTER TABLE backup_logs
ADD COLUMN IF NOT EXISTS execution_log TEXT;
COMMENT ON COLUMN backup_logs.execution_log IS 'Bitácora técnica completa del proceso: hitos internos + stdout/stderr de pg_dump. Presente en COMPLETED y FAILED. NULL para registros anteriores a V14.';