-- ============================================================================
-- V22: Registrar job de limpieza de cuentas en system_automations
-- Limpia: invitaciones expiradas, tokens de reset, tokens de verificación
-- ============================================================================
INSERT INTO system_automations (
        job_name,
        job_group,
        display_name,
        description,
        icon_name,
        is_enabled,
        cron_expression,
        timezone,
        parameters,
        created_at,
        updated_at
    )
VALUES (
        'ACCOUNT_CLEANUP_JOB',
        'MAINTENANCE',
        'Limpieza de Cuentas',
        'Limpia invitaciones expiradas, tokens de reset de contraseña caducados y tokens de verificación vencidos. Mantiene la base de datos libre de registros obsoletos.',
        'person-x-fill',
        true,
        '0 0 3 * * ?',
        'America/Mexico_City',
        '{}',
        NOW(),
        NOW()
    ) ON CONFLICT (job_name) DO NOTHING;