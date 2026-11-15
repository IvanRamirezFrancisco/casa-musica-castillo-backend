-- V49__add_help_center_permission_and_sync_super_admin.sql
-- Creación del permiso para Help Center y sincronización del propietario.

-- 1. Insertar permiso HELP_MANAGE de manera idempotente
INSERT INTO auth.permissions (name, description, category, created_at, updated_at)
VALUES (
    'HELP_MANAGE',
    'Administrar categorías y preguntas del Centro de Ayuda',
    'HELP',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (name) DO NOTHING;

-- 2. Asignar HELP_MANAGE explícitamente a ROLE_ADMIN y ROLE_SUPER_ADMIN
WITH roles_to_update AS (
    SELECT id FROM auth.roles WHERE name IN ('ROLE_ADMIN', 'ROLE_SUPER_ADMIN')
),
new_permission AS (
    SELECT id FROM auth.permissions WHERE name = 'HELP_MANAGE'
)
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles_to_update r
CROSS JOIN new_permission p
WHERE NOT EXISTS (
    SELECT 1 FROM auth.role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
);

-- 3. INVARIANTE: Sincronizar absolutamente TODOS los permisos a ROLE_SUPER_ADMIN
-- Esto garantiza que SUPER_ADMIN realmente sea el dueño irrestricto en base de datos.
WITH super_admin_role AS (
    SELECT id FROM auth.roles WHERE name = 'ROLE_SUPER_ADMIN'
),
all_permissions AS (
    SELECT id FROM auth.permissions
)
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM super_admin_role r
CROSS JOIN all_permissions p
WHERE NOT EXISTS (
    SELECT 1 FROM auth.role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
);
