-- V35__cleanup_store_staff_roles_and_permissions.sql
-- Limpieza segura de los roles y permisos operativos (store-staff) que ya no se usarán,
-- asegurando no romper integridad si ya hay usuarios asignados a dichos roles.

-- 1. Eliminar permisos de auth.role_permissions para los roles operativos si NO tienen usuarios
DELETE FROM auth.role_permissions rp
USING auth.roles r
WHERE rp.role_id = r.id
  AND r.name IN ('ROLE_STORE_MANAGER', 'ROLE_STORE_STAFF', 'ROLE_CATALOG_MANAGER', 'ROLE_ORDER_MANAGER', 'ROLE_PAYMENT_ASSISTANT')
  AND NOT EXISTS (
      SELECT 1 FROM auth.user_roles ur WHERE ur.role_id = r.id
  );

-- 2. Eliminar los roles operativos si NO tienen usuarios
DELETE FROM auth.roles r
WHERE r.name IN ('ROLE_STORE_MANAGER', 'ROLE_STORE_STAFF', 'ROLE_CATALOG_MANAGER', 'ROLE_ORDER_MANAGER', 'ROLE_PAYMENT_ASSISTANT')
  AND NOT EXISTS (
      SELECT 1 FROM auth.user_roles ur WHERE ur.role_id = r.id
  );

-- 3. Eliminar los permisos STORE_STAFF_* si ya no están referenciados por NINGÚN rol en role_permissions
DELETE FROM auth.permissions p
WHERE p.category = 'STORE_STAFF'
  AND NOT EXISTS (
      SELECT 1 FROM auth.role_permissions rp WHERE rp.permission_id = p.id
  );
