-- V34__add_store_staff_roles_and_permissions.sql
-- Creación de roles y permisos operativos para personal de tienda (seguridad y segmentación de personal)
-- Migración idempotente.

-- 1. Insertar nuevos permisos de personal de tienda (categoría STORE_STAFF)
INSERT INTO auth.permissions (name, description, category, created_at, updated_at)
VALUES
('STORE_STAFF_READ', 'Permite ver el listado y detalles de personal operativo', 'STORE_STAFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('STORE_STAFF_CREATE', 'Permite crear o invitar personal operativo', 'STORE_STAFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('STORE_STAFF_UPDATE', 'Permite actualizar información de personal operativo', 'STORE_STAFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('STORE_STAFF_DISABLE', 'Permite habilitar/deshabilitar personal operativo', 'STORE_STAFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('STORE_STAFF_DELETE', 'Permite eliminar permanentemente personal operativo', 'STORE_STAFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('STORE_STAFF_ASSIGN_ROLE', 'Permite asignar roles operativos inferiores', 'STORE_STAFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('STORE_STAFF_MANAGE', 'Permiso contenedor para gestión total de personal de tienda', 'STORE_STAFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (name) DO NOTHING;

-- 2. Insertar roles operativos (is_system_role = FALSE)
INSERT INTO auth.roles (name, description, is_system_role, created_at, updated_at)
VALUES
('ROLE_STORE_MANAGER', 'Gerente o encargado principal de la tienda física / operativa', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('ROLE_STORE_STAFF', 'Personal general de atención en tienda', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('ROLE_CATALOG_MANAGER', 'Encargado de productos, marcas y categorías', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('ROLE_ORDER_MANAGER', 'Encargado del procesamiento y despacho de órdenes', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('ROLE_PAYMENT_ASSISTANT', 'Auxiliar enfocado en validación de pagos y cobranza', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (name) DO NOTHING;

-- 3. Asignar permisos a ROLE_STORE_MANAGER de forma segura e idempotente
WITH manager_role AS (
    SELECT id FROM auth.roles WHERE name = 'ROLE_STORE_MANAGER'
),
permissions_to_assign AS (
    SELECT id FROM auth.permissions
    WHERE name IN (
        'DASHBOARD_VIEW',
        'PRODUCT_READ', 'PRODUCT_CREATE', 'PRODUCT_UPDATE',
        'BRAND_MANAGE',
        'CATEGORY_MANAGE',
        'ORDER_READ', 'ORDER_UPDATE',
        'PAYMENT_READ', 'PAYMENT_PROCESS',
        'CUSTOMER_READ',
        'STORE_STAFF_READ', 'STORE_STAFF_CREATE', 'STORE_STAFF_UPDATE', 'STORE_STAFF_DISABLE', 'STORE_STAFF_ASSIGN_ROLE', 'STORE_STAFF_MANAGE'
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

-- 4. Asignar permisos a ROLE_STORE_STAFF
WITH staff_role AS (
    SELECT id FROM auth.roles WHERE name = 'ROLE_STORE_STAFF'
),
permissions_to_assign AS (
    SELECT id FROM auth.permissions
    WHERE name IN (
        'DASHBOARD_VIEW',
        'PRODUCT_READ',
        'ORDER_READ'
    )
)
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM staff_role r
CROSS JOIN permissions_to_assign p
WHERE NOT EXISTS (
    SELECT 1 FROM auth.role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
);

-- 5. Asignar permisos a ROLE_CATALOG_MANAGER
WITH catalog_role AS (
    SELECT id FROM auth.roles WHERE name = 'ROLE_CATALOG_MANAGER'
),
permissions_to_assign AS (
    SELECT id FROM auth.permissions
    WHERE name IN (
        'DASHBOARD_VIEW',
        'PRODUCT_READ', 'PRODUCT_CREATE', 'PRODUCT_UPDATE', 'PRODUCT_DELETE',
        'BRAND_MANAGE',
        'CATEGORY_MANAGE'
    )
)
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM catalog_role r
CROSS JOIN permissions_to_assign p
WHERE NOT EXISTS (
    SELECT 1 FROM auth.role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
);

-- 6. Asignar permisos a ROLE_ORDER_MANAGER
WITH order_role AS (
    SELECT id FROM auth.roles WHERE name = 'ROLE_ORDER_MANAGER'
),
permissions_to_assign AS (
    SELECT id FROM auth.permissions
    WHERE name IN (
        'DASHBOARD_VIEW',
        'ORDER_READ', 'ORDER_UPDATE',
        'CUSTOMER_READ',
        'PAYMENT_READ'
    )
)
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM order_role r
CROSS JOIN permissions_to_assign p
WHERE NOT EXISTS (
    SELECT 1 FROM auth.role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
);

-- 7. Asignar permisos a ROLE_PAYMENT_ASSISTANT
WITH payment_role AS (
    SELECT id FROM auth.roles WHERE name = 'ROLE_PAYMENT_ASSISTANT'
),
permissions_to_assign AS (
    SELECT id FROM auth.permissions
    WHERE name IN (
        'DASHBOARD_VIEW',
        'ORDER_READ',
        'PAYMENT_READ', 'PAYMENT_PROCESS'
    )
)
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM payment_role r
CROSS JOIN permissions_to_assign p
WHERE NOT EXISTS (
    SELECT 1 FROM auth.role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
);
