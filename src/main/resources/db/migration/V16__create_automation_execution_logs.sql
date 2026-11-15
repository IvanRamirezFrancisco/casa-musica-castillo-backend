-- ==============================================================================
-- Migración V16: Historial de Ejecuciones de Automatizaciones
-- ==============================================================================
-- 1. FK en system_automations.updated_by → users(id)
-- 2. Tabla automation_execution_logs con relación a system_automations
-- 3. Índices optimizados para consultas de historial paginado
-- ==============================================================================
-- ──────────────────────────────────────────────────────────────────────────────
-- 1. Foreign Key: system_automations.updated_by → users.id
-- ──────────────────────────────────────────────────────────────────────────────
DO $$ BEGIN IF NOT EXISTS (
    SELECT 1
    FROM information_schema.table_constraints
    WHERE constraint_name = 'fk_automations_updated_by'
        AND table_name = 'system_automations'
) THEN
ALTER TABLE public.system_automations
ADD CONSTRAINT fk_automations_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id) ON DELETE
SET NULL;
END IF;
END $$;
-- ──────────────────────────────────────────────────────────────────────────────
-- 2. Tabla de historial de ejecuciones
-- ──────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.automation_execution_logs (
    id BIGSERIAL PRIMARY KEY,
    automation_id BIGINT NOT NULL REFERENCES public.system_automations(id) ON DELETE CASCADE,
    started_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITHOUT TIME ZONE,
    status VARCHAR(20) NOT NULL,
    triggered_by VARCHAR(100) NOT NULL,
    duration_ms BIGINT,
    result_summary TEXT,
    error_message TEXT,
    CONSTRAINT chk_exec_log_status CHECK (
        status IN ('SUCCESS', 'FAILED', 'IN_PROGRESS', 'CANCELLED')
    )
);
-- ──────────────────────────────────────────────────────────────────────────────
-- 3. Índices para rendimiento de consultas paginadas
-- ──────────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_exec_logs_automation ON public.automation_execution_logs(automation_id);
CREATE INDEX IF NOT EXISTS idx_exec_logs_started ON public.automation_execution_logs(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_exec_logs_automation_started ON public.automation_execution_logs(automation_id, started_at DESC);
-- ──────────────────────────────────────────────────────────────────────────────
-- Comentarios de diccionario de datos
-- ──────────────────────────────────────────────────────────────────────────────
COMMENT ON TABLE public.automation_execution_logs IS 'Historial detallado de cada ejecución de las automatizaciones del sistema. Cada fila representa una corrida individual.';
COMMENT ON COLUMN public.automation_execution_logs.automation_id IS 'FK a system_automations.id — identifica qué automatización se ejecutó.';
COMMENT ON COLUMN public.automation_execution_logs.triggered_by IS 'Quién disparó la ejecución: SCHEDULER (cron automático) o email del usuario (ejecución manual).';
COMMENT ON COLUMN public.automation_execution_logs.result_summary IS 'Resumen legible del resultado, ej: "3 tablas limpiadas, 45 registros eliminados".';