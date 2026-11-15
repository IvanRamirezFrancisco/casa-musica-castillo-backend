-- V36__add_payment_settings_permissions.sql
-- Inserción de permisos granulares para la configuración de pagos
-- y asignación segura al ROLE_STORE_MANAGER.

-- 1. Insertar permisos (idempotente)
INSERT INTO auth.permissions (name, description, category, created_at, updated_at)
VALUES
('BANK_TRANSFER_SETTINGS_READ', 'Permite consultar la configuración de datos bancarios para transferencias', 'SYSTEM_CONFIG', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('BANK_TRANSFER_SETTINGS_UPDATE', 'Permite actualizar la configuración de datos bancarios para transferencias', 'SYSTEM_CONFIG', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('PAYMENT_SETTINGS_READ', 'Permite consultar la configuración de pagos general', 'SYSTEM_CONFIG', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('PAYMENT_SETTINGS_UPDATE', 'Permite actualizar la configuración de pagos general', 'SYSTEM_CONFIG', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (name) DO NOTHING;

-- 2. Asignar BANK_TRANSFER_SETTINGS_READ y BANK_TRANSFER_SETTINGS_UPDATE a ROLE_STORE_MANAGER
WITH manager_role AS (
    SELECT id FROM auth.roles WHERE name = 'ROLE_STORE_MANAGER'
),
permissions_to_assign AS (
    SELECT id FROM auth.permissions
    WHERE name IN (
        'BANK_TRANSFER_SETTINGS_READ',
        'BANK_TRANSFER_SETTINGS_UPDATE'
    )
)
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM manager_role r
CROSS JOIN permissions_to_assign p
WHERE NOT EXISTS (
    SELECT 1 FROM auth.role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
);
