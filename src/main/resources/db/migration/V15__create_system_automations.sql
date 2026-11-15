-- ==============================================================================
-- Migración Flyway: Creación de la tabla central de Automatizaciones del Sistema
-- ==============================================================================
CREATE TABLE public.system_automations (
    id BIGSERIAL PRIMARY KEY,
    -- Identificadores del Job
    job_name VARCHAR(100) NOT NULL UNIQUE,
    -- ID interno para el backend (ej. 'BACKUP_DB')
    job_group VARCHAR(50) NOT NULL,
    -- Categoría para agrupar en UI (ej. 'SECURITY', 'MAINTENANCE')
    -- Datos visuales para el Frontend (UI/UX)
    display_name VARCHAR(150) NOT NULL,
    -- Título en la tarjeta (ej. 'Respaldo Automático')
    description TEXT,
    -- Descripción técnica para el usuario
    icon_name VARCHAR(50),
    -- Nombre del icono de Material/Lucide (ej. 'cloud_upload')
    -- Configuración de Ejecución
    is_enabled BOOLEAN NOT NULL DEFAULT false,
    -- Estado del Toggle Switch (Activo/Inactivo)
    cron_expression VARCHAR(100) NOT NULL,
    -- Expresión Cron de Spring (ej. '0 0 3 * * ?' -> 3:00 AM)
    timezone VARCHAR(50) NOT NULL DEFAULT 'America/Mexico_City',
    -- Zona horaria para evaluar el Cron
    parameters JSONB DEFAULT '{}'::jsonb,
    -- Configuraciones dinámicas (ej. correos, umbrales)
    -- Metadatos de Auditoría y Estado en Vivo
    last_execution TIMESTAMP WITHOUT TIME ZONE,
    -- Cuándo empezó a correr por última vez
    next_execution TIMESTAMP WITHOUT TIME ZONE,
    -- Cuándo está programado para correr de nuevo
    last_duration_ms BIGINT,
    -- Cuánto tiempo tomó la última ejecución en milisegundos
    last_status VARCHAR(20) DEFAULT 'PENDING',
    -- SUCCESS, FAILED, IN_PROGRESS, PENDING
    error_message TEXT,
    -- Si falló, la causa del error
    -- Trazabilidad
    updated_by BIGINT,
    -- ID del usuario que modificó el cron por última vez
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_automation_status CHECK (
        last_status IN ('SUCCESS', 'FAILED', 'IN_PROGRESS', 'PENDING')
    )
);
-- ==============================================================================
-- Comentarios para Diccionario de Datos
-- ==============================================================================
COMMENT ON TABLE public.system_automations IS 'Tabla central del motor de automatización. Controla los cron jobs dinámicos del sistema.';
COMMENT ON COLUMN public.system_automations.parameters IS 'Almacena configuraciones específicas del job en formato JSON (ej. {"notify_email": "admin@empresa.com"}).';
COMMENT ON COLUMN public.system_automations.timezone IS 'Zona horaria de referencia para la expresión cron.';
-- ==============================================================================
-- Índices para optimizar búsquedas del motor en el Backend
-- ==============================================================================
CREATE INDEX idx_automations_enabled ON public.system_automations (is_enabled);
CREATE INDEX idx_automations_group ON public.system_automations (job_group);
-- ==============================================================================
-- Datos Semilla (Seed Data)
-- ==============================================================================
INSERT INTO public.system_automations (
        job_name,
        job_group,
        display_name,
        description,
        icon_name,
        is_enabled,
        cron_expression,
        parameters
    )
VALUES (
        'BACKUP_DATABASE_JOB',
        'SECURITY',
        'Respaldo de Base de Datos',
        'Genera un volcado comprimido de PostgreSQL y lo sube de forma segura a Supabase Storage.',
        'cloud_upload',
        true,
        '0 0 3 * * ?',
        '{"compression_level": 6, "retention_days": 30}'::jsonb
    ),
    (
        'SESSION_CLEANUP_JOB',
        'SECURITY',
        'Limpieza de Sesiones Expiradas',
        'Elimina tokens JWT revocados y sesiones de usuario inactivas para liberar espacio y mejorar seguridad.',
        'shield_moon',
        true,
        '0 0 4 * * ?',
        '{}'::jsonb
    ),
    (
        'DB_MAINTENANCE_JOB',
        'MAINTENANCE',
        'Mantenimiento PostgreSQL (VACUUM)',
        'Ejecuta VACUUM y ANALYZE en tablas con alta rotación para prevenir degradación de rendimiento.',
        'database',
        false,
        '0 0 2 * * SUN',
        '{"run_analyze": true}'::jsonb
    ),
    (
        'INVENTORY_AUDIT_JOB',
        'ALERTS',
        'Auditoría de Inventario Crítico',
        'Escanea el inventario y envía un reporte por correo de los productos que han alcanzado su nivel crítico.',
        'inventory',
        false,
        '0 0 8 * * 1-5',
        '{"notify_emails": ["admin@casamusica.com"], "threshold": 5}'::jsonb
    );