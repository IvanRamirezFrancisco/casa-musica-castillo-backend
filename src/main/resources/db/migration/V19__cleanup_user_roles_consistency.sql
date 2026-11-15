-- ============================================================
-- V19 — Cleanup inconsistent user_roles & remove test role
-- ============================================================
-- Must run in a single transaction (Flyway default).
--
-- 1) Remove ROLE_USER from any user who already has ROLE_ADMIN
--    or ROLE_SUPER_ADMIN (admins should not also be "customers").
-- 2) Delete the ROLE_VW_ADMIN test role (and its bindings).
-- ============================================================
BEGIN;
-- 1) Remove redundant ROLE_USER from admin-level users
DELETE FROM public.user_roles
WHERE role_id = 1 -- ROLE_USER
    AND user_id IN (
        SELECT DISTINCT user_id
        FROM public.user_roles
        WHERE role_id IN (2, 3) -- ROLE_ADMIN or ROLE_SUPER_ADMIN
    );
-- 2) Clean up ROLE_VW_ADMIN (test role) if it exists
DELETE FROM public.role_permissions
WHERE role_id IN (
        SELECT id
        FROM public.roles
        WHERE name = 'ROLE_VW_ADMIN'
    );
DELETE FROM public.user_roles
WHERE role_id IN (
        SELECT id
        FROM public.roles
        WHERE name = 'ROLE_VW_ADMIN'
    );
DELETE FROM public.roles
WHERE name = 'ROLE_VW_ADMIN';
COMMIT;