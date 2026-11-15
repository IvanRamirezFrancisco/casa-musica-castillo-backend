-- ============================================================
-- V13: Corrige category_id NULL en inventory_prediction_config
--      y actualiza stock_reference con el stock real actual
-- ============================================================
-- 1. Asignar category_id correcto a Jaranas Huastecas
UPDATE inventory_prediction_config
SET category_id = (
    SELECT id
    FROM categories
    WHERE name = 'Jaranas Huastecas'
    LIMIT 1
  )
WHERE section_key = 'jaranas'
  AND category_id IS NULL;
-- 2. Asignar category_id correcto a Quintas Huapangueras
UPDATE inventory_prediction_config
SET category_id = (
    SELECT id
    FROM categories
    WHERE name = 'Quintas Huapangueras'
    LIMIT 1
  )
WHERE section_key = 'quintas'
  AND category_id IS NULL;
-- 3. Asignar category_id correcto a Violines (por si acaso)
UPDATE inventory_prediction_config
SET category_id = (
    SELECT id
    FROM categories
    WHERE name = 'Violines'
    LIMIT 1
  )
WHERE section_key = 'violines'
  AND category_id IS NULL;
-- 4. Asignar category_id correcto a Accesorios (por si acaso)
UPDATE inventory_prediction_config
SET category_id = (
    SELECT id
    FROM categories
    WHERE name = 'Accesorios'
    LIMIT 1
  )
WHERE section_key = 'accesorios'
  AND category_id IS NULL;
-- 5. Actualizar stock_reference al stock actual cuando sea mayor que el registrado
--    Esto evita que iCurrent > i0, lo que invalidaba el modelo de decaimiento.
UPDATE inventory_prediction_config c
SET stock_reference = GREATEST(
    c.stock_reference,
    COALESCE(
      (
        SELECT SUM(p.stock)
        FROM products p
        WHERE p.category_id = c.category_id
          AND p.active = true
      ),
      0
    )
  )
WHERE c.category_id IS NOT NULL;
-- 6. Actualizar reference_date al dia de hoy para secciones donde el stock
--    actual supera el stock_reference original (reabastecimiento reciente).
--    Esto hace que t=0 y k caiga a K_MIN, mostrando la grafica correctamente.
UPDATE inventory_prediction_config c
SET reference_date = CURRENT_DATE
WHERE c.category_id IS NOT NULL
  AND COALESCE(
    (
      SELECT SUM(p.stock)
      FROM products p
      WHERE p.category_id = c.category_id
        AND p.active = true
    ),
    0
  ) >= c.stock_reference;