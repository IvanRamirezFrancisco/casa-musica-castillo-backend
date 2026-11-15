-- V33: Añadir soporte para roles de sistema y nuevos permisos críticos
-- Esta migración NO crea ROLE_STORE_MANAGER automáticamente.
-- Solo agrega infraestructura de seguridad para Protected Owner y jerarquía administrativa.
-- 1. Añadir columna is_system_role a la tabla de roles
ALTER TABLE auth.roles
ADD COLUMN IF NOT EXISTS is_system_role BOOLEAN NOT NULL DEFAULT FALSE;
-- 2. Marcar los roles existentes base como roles del sistema
UPDATE auth.roles
SET is_system_role = TRUE
WHERE name IN ('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_USER');
-- 3. Insertar nuevos permisos críticos.
-- La tabla auth.permissions requiere created_at como NOT NULL.
-- Se usan categorías existentes para mantener compatibilidad con la UI actual.
INSERT INTO auth.permissions (
        name,
        description,
        category,
        created_at,
        updated_at
    )
VALUES (
        'SYSTEM_OWNER_MANAGE',
        'Gestionar funciones reservadas del propietario del sistema',
        'SYSTEM',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'SUPER_ADMIN_MANAGE',
        'Modificar otros super administradores no protegidos',
        'SYSTEM',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'ROLE_SYSTEM_UPDATE',
        'Actualizar roles protegidos del sistema',
        'ROLE',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'ROLE_SYSTEM_DELETE',
        'Eliminar roles protegidos del sistema',
        'ROLE',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'PERMISSION_CRITICAL_ASSIGN',
        'Asignar permisos críticos a roles o usuarios',
        'PERMISSION',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'USER_ROLE_ASSIGN_SUPER_ADMIN',
        'Asignar rol SUPER_ADMIN a usuarios autorizados',
        'USER',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'USER_ROLE_REMOVE_SUPER_ADMIN',
        'Remover rol SUPER_ADMIN a usuarios no protegidos',
        'USER',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'USER_DISABLE_SUPER_ADMIN',
        'Deshabilitar cuentas SUPER_ADMIN no protegidas',
        'USER',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'USER_DELETE_SUPER_ADMIN',
        'Eliminar cuentas SUPER_ADMIN no protegidas',
        'USER',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'DATABASE_DROP',
        'Ejecutar operaciones destructivas en base de datos',
        'DATABASE',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'DATABASE_RESTORE',
        'Restaurar base de datos desde respaldo',
        'DATABASE',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ) ON CONFLICT (name) DO NOTHING;