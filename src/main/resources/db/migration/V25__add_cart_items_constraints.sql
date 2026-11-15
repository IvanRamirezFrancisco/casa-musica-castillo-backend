-- =============================================================================
-- V25__add_cart_items_constraints.sql
-- FASE 3 - Casa de Música Castillo - 2026-06-06
-- =============================================================================
-- Objetivo: Garantizar integridad de datos en el carrito de compras
-- 1. Unificar items duplicados por cart_id y product_id.
-- 2. Restricción UNIQUE en (cart_id, product_id).
-- 3. Restricción CHECK en quantity > 0.
-- =============================================================================

-- ----------------------------------------------------------------------------
-- PASO 1: Unificar items duplicados (si los hay)
-- Consolidamos la cantidad y el subtotal en el item más antiguo, y eliminamos los demás.
-- ----------------------------------------------------------------------------
WITH duplicates AS (
    SELECT cart_id, product_id
    FROM sales.cart_items
    GROUP BY cart_id, product_id
    HAVING COUNT(*) > 1
),
merged_data AS (
    SELECT 
        ci.cart_id, 
        ci.product_id,
        MIN(ci.id) AS id_to_keep,
        SUM(ci.quantity) AS total_quantity,
        SUM(ci.subtotal) AS total_subtotal
    FROM sales.cart_items ci
    JOIN duplicates d ON ci.cart_id = d.cart_id AND ci.product_id = d.product_id
    GROUP BY ci.cart_id, ci.product_id
)
-- 1.1 Actualizar el registro que vamos a conservar
UPDATE sales.cart_items t
SET 
    quantity = md.total_quantity,
    subtotal = md.total_subtotal,
    updated_at = NOW()
FROM merged_data md
WHERE t.id = md.id_to_keep;

-- 1.2 Eliminar los registros duplicados sobrantes
WITH duplicates AS (
    SELECT cart_id, product_id
    FROM sales.cart_items
    GROUP BY cart_id, product_id
    HAVING COUNT(*) > 1
),
merged_data AS (
    SELECT 
        ci.cart_id, 
        ci.product_id,
        MIN(ci.id) AS id_to_keep
    FROM sales.cart_items ci
    JOIN duplicates d ON ci.cart_id = d.cart_id AND ci.product_id = d.product_id
    GROUP BY ci.cart_id, ci.product_id
)
DELETE FROM sales.cart_items ci
USING merged_data md
WHERE ci.cart_id = md.cart_id 
  AND ci.product_id = md.product_id 
  AND ci.id != md.id_to_keep;

-- ----------------------------------------------------------------------------
-- PASO 2: Eliminar items con cantidad <= 0 (si los hubiera, aunque no debería)
-- ----------------------------------------------------------------------------
DELETE FROM sales.cart_items WHERE quantity <= 0;

-- ----------------------------------------------------------------------------
-- PASO 3: Agregar constraint UNIQUE para (cart_id, product_id)
-- ----------------------------------------------------------------------------
ALTER TABLE sales.cart_items
ADD CONSTRAINT uk_cart_item_cart_product UNIQUE (cart_id, product_id);

-- ----------------------------------------------------------------------------
-- PASO 4: Agregar constraint CHECK para quantity > 0
-- ----------------------------------------------------------------------------
ALTER TABLE sales.cart_items
ADD CONSTRAINT chk_cart_item_quantity CHECK (quantity > 0);
