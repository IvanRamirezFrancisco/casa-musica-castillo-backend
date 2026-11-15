-- =============================================================================
-- V24__enforce_single_active_cart_per_user.sql
-- FASE 1.1 - Casa de Música Castillo - 2026-05-15
-- =============================================================================
-- Objetivo: Garantizar que cada usuario autenticado tenga como máximo UN carrito
-- con status = 'ACTIVE'. Los duplicados existentes se marcan como 'ABANDONED'
-- (no se borran carritos ni items).
-- Los carritos anónimos (user_id IS NULL) no se tocan.
-- =============================================================================

-- ----------------------------------------------------------------------------
-- PASO 1: Limpiar carritos ACTIVE duplicados existentes
-- Conservar el más reciente por usuario (updated_at DESC, created_at DESC, id DESC).
-- Marcar todos los demás como ABANDONED.
-- ----------------------------------------------------------------------------
WITH ranked_active_carts AS (
    SELECT
        id,
        user_id,
        ROW_NUMBER() OVER (
            PARTITION BY user_id
            ORDER BY
                updated_at DESC NULLS LAST,
                created_at DESC NULLS LAST,
                id          DESC
        ) AS rn
    FROM sales.shopping_carts
    WHERE status   = 'ACTIVE'
      AND user_id IS NOT NULL
)
UPDATE sales.shopping_carts sc
SET    status     = 'ABANDONED',
       updated_at = NOW()
FROM   ranked_active_carts ranked
WHERE  sc.id   = ranked.id
  AND  ranked.rn > 1;

-- ----------------------------------------------------------------------------
-- PASO 2: Crear índice único parcial para prevenir duplicados futuros
-- El índice sólo aplica sobre filas con status = 'ACTIVE' y user_id NOT NULL.
-- Permite múltiples carritos ABANDONED/EXPIRED/CONVERTED por usuario.
-- Permite múltiples carritos anónimos (user_id IS NULL).
-- ----------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS ux_shopping_carts_one_active_per_user
    ON sales.shopping_carts (user_id)
    WHERE status = 'ACTIVE'
      AND user_id IS NOT NULL;

-- ----------------------------------------------------------------------------
-- VERIFICACIÓN (resultado esperado: 0 filas)
-- Ejecutar manualmente para confirmar el estado tras aplicar la migración:
--
-- SELECT
--     user_id,
--     status,
--     COUNT(*) AS total
-- FROM sales.shopping_carts
-- WHERE  status  = 'ACTIVE'
--   AND  user_id IS NOT NULL
-- GROUP  BY user_id, status
-- HAVING COUNT(*) > 1;
-- ----------------------------------------------------------------------------
