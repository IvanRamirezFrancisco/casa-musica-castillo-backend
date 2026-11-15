-- ============================================================
-- V18 — Expand permissions for granular RBAC
-- ============================================================
-- Adds permissions for modules that were missing:
--   DATABASE (4), CUSTOMER (2), REPORT (2), PRODUCT extras (2)
-- Then assigns them to ROLE_ADMIN (id=2) and ROLE_SUPER_ADMIN (id=3).
-- ROLE_USER (id=1) does NOT receive any of these.
-- ============================================================
-- 1) Insert new permissions (IDENTITY column — no explicit ID needed)
INSERT INTO public.permissions (name, description, category, created_at)
VALUES -- Base de Datos
    (
        'DATABASE_VIEW',
        'Ver metricas de base de datos',
        'DATABASE',
        NOW()
    ),
    (
        'DATABASE_BACKUP',
        'Crear y descargar respaldos',
        'DATABASE',
        NOW()
    ),
    (
        'DATABASE_MAINTAIN',
        'Ejecutar VACUUM, REINDEX y ANALYZE',
        'DATABASE',
        NOW()
    ),
    (
        'DATABASE_AUTOMATE',
        'Gestionar automatizaciones del sistema',
        'DATABASE',
        NOW()
    ),
    -- Clientes
    (
        'CUSTOMER_READ',
        'Ver lista y detalle de clientes',
        'CUSTOMER',
        NOW()
    ),
    (
        'CUSTOMER_MANAGE',
        'Activar, bloquear y resetear clientes',
        'CUSTOMER',
        NOW()
    ),
    -- Reportes / Exportacion
    (
        'REPORT_VIEW',
        'Ver reportes y estadisticas',
        'REPORT',
        NOW()
    ),
    (
        'REPORT_EXPORT',
        'Exportar datos a CSV',
        'REPORT',
        NOW()
    ),
    -- Marcas y Categorias (extend PRODUCT category)
    (
        'BRAND_MANAGE',
        'Crear, editar y eliminar marcas',
        'PRODUCT',
        NOW()
    ),
    (
        'CATEGORY_MANAGE',
        'Crear, editar y eliminar categorias',
        'PRODUCT',
        NOW()
    ) ON CONFLICT (name) DO NOTHING;
-- 2) Assign ALL new permissions to ROLE_ADMIN (id=2) and ROLE_SUPER_ADMIN (id=3)
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id,
    p.id
FROM public.roles r
    CROSS JOIN public.permissions p
WHERE r.id IN (2, 3)
    AND p.name IN (
        'DATABASE_VIEW',
        'DATABASE_BACKUP',
        'DATABASE_MAINTAIN',
        'DATABASE_AUTOMATE',
        'CUSTOMER_READ',
        'CUSTOMER_MANAGE',
        'REPORT_VIEW',
        'REPORT_EXPORT',
        'BRAND_MANAGE',
        'CATEGORY_MANAGE'
    ) ON CONFLICT DO NOTHING;