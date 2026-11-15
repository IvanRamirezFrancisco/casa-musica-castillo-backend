-- ============================================================================
-- V17: Eliminar tabla redundante store_alert_config
-- ============================================================================
-- La configuración de alertas de stock ahora vive exclusivamente en
-- system_automations.parameters (JSONB) del job INVENTORY_AUDIT_JOB.
-- Esta migración migra datos existentes y elimina la tabla obsoleta.
-- 1. Migrar datos de store_alert_config → system_automations.parameters
--    Solo si la tabla existe y tiene datos configurados.
DO $$
DECLARE v_enabled BOOLEAN;
v_threshold INTEGER;
v_emails VARCHAR;
v_current JSONB;
BEGIN -- Verificar que la tabla existe
IF EXISTS (
    SELECT 1
    FROM information_schema.tables
    WHERE table_name = 'store_alert_config'
) THEN -- Leer configuración existente
SELECT enabled,
    stock_threshold,
    notify_emails INTO v_enabled,
    v_threshold,
    v_emails
FROM store_alert_config
ORDER BY id ASC
LIMIT 1;
IF FOUND THEN -- Obtener parámetros actuales del INVENTORY_AUDIT_JOB
SELECT parameters INTO v_current
FROM system_automations
WHERE job_name = 'INVENTORY_AUDIT_JOB';
IF v_current IS NULL THEN v_current := '{}'::jsonb;
END IF;
-- Fusionar stock_threshold
IF v_threshold IS NOT NULL THEN v_current := v_current || jsonb_build_object('stock_threshold', v_threshold);
END IF;
-- Fusionar notify_emails como array JSON
IF v_emails IS NOT NULL
AND v_emails <> '' THEN v_current := v_current || jsonb_build_object(
    'notify_emails',
    (
        SELECT jsonb_agg(trim(e))
        FROM unnest(string_to_array(v_emails, ',')) AS e
        WHERE trim(e) <> ''
    )
);
END IF;
-- Actualizar system_automations
UPDATE system_automations
SET parameters = v_current,
    is_enabled = v_enabled,
    updated_at = CURRENT_TIMESTAMP
WHERE job_name = 'INVENTORY_AUDIT_JOB';
RAISE NOTICE 'Migración exitosa: store_alert_config → system_automations.parameters';
END IF;
END IF;
END $$;
-- 2. Eliminar la tabla redundante
DROP TABLE IF EXISTS store_alert_config;
-- 3. Limpiar el registro de Flyway de la V17 anterior (si existe)
DELETE FROM flyway_schema_history
WHERE version = '17'
    AND description = 'create store alert config';