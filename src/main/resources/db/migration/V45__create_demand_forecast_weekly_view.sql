CREATE SCHEMA IF NOT EXISTS analytics;

-- Vista histórica de ventas semanales para Pronóstico de Demanda (Medias Móviles SMA-12)
-- Esta vista genera series continuas (Lunes a Domingo) asegurando que las semanas
-- sin ventas queden en cero, para que el algoritmo procese correctamente la falta de demanda.

-- Se emplea America/Mexico_City para alinear los inicios de semana en la zona horaria productiva.
-- Se descarta explícitamente la semana en curso (parcial) comparando contra la semana truncada del "ahora".
-- Las ventas válidas requieren: 'PAID' y un estado distinto a 'CANCELLED', con subtotales/totales > 0.

-- NOTA: El timestamp de creación de sales.orders es timestamp sin zona horaria, por lo que 
-- se debe asumir que está guardado en UTC o zona horaria local. 
-- Para esta versión se convierte a 'America/Mexico_City' explícitamente.

DROP VIEW IF EXISTS analytics.vw_recomendacion_producto_semana_historica_v1 CASCADE;
CREATE OR REPLACE VIEW analytics.vw_recomendacion_producto_semana_historica_v1 AS
WITH valid_order_items AS (
    SELECT 
        oi.product_id,
        p.sku AS product_sku,
        p.name AS product_name,
        p.category_id,
        c.name AS category_name,
        p.stock AS current_stock,
        p.active AS product_active_current,
        oi.quantity AS units_sold,
        oi.subtotal AS revenue,
        oi.unit_price,
        o.id AS order_id,
        o.created_at AT TIME ZONE 'UTC' AT TIME ZONE 'America/Mexico_City' AS local_order_time
    FROM sales.orders o
    JOIN sales.order_items oi ON o.id = oi.order_id
    JOIN catalog.products p ON oi.product_id = p.id
    LEFT JOIN catalog.categories c ON p.category_id = c.id
    WHERE o.payment_status = 'PAID'
      AND o.status <> 'CANCELLED'
      AND o.subtotal > 0
      AND o.total > 0
      AND oi.quantity > 0
),
weekly_sales AS (
    SELECT 
        product_id,
        product_sku,
        product_name,
        category_id,
        category_name,
        current_stock,
        product_active_current,
        date_trunc('week', local_order_time)::date AS week_start,
        SUM(units_sold) AS units_sold,
        SUM(revenue) AS revenue,
        COUNT(DISTINCT order_id) AS orders_count,
        AVG(unit_price) AS avg_unit_price,
        COUNT(DISTINCT local_order_time::date) AS sales_days_count
    FROM valid_order_items
    GROUP BY 
        product_id,
        product_sku,
        product_name,
        category_id,
        category_name,
        current_stock,
        product_active_current,
        date_trunc('week', local_order_time)::date
),
product_ranges AS (
    SELECT 
        product_id,
        MIN(week_start) AS first_week,
        date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AT TIME ZONE 'America/Mexico_City')::date - interval '7 days' AS last_complete_week
    FROM weekly_sales
    GROUP BY product_id
),
continuous_weeks AS (
    SELECT 
        pr.product_id,
        g.week_start::date AS week_start
    FROM product_ranges pr
    CROSS JOIN LATERAL generate_series(pr.first_week, pr.last_complete_week, '7 days'::interval) AS g(week_start)
)
SELECT 
    cw.product_id,
    COALESCE(ws.product_sku, p.sku) AS product_sku,
    COALESCE(ws.product_name, p.name) AS product_name,
    COALESCE(ws.category_id, p.category_id) AS category_id,
    COALESCE(ws.category_name, c.name) AS category_name,
    cw.week_start,
    (cw.week_start + interval '6 days')::date AS week_end,
    COALESCE(ws.orders_count, 0) AS orders_count,
    COALESCE(ws.units_sold, 0) AS units_sold,
    COALESCE(ws.revenue, 0) AS revenue,
    COALESCE(ws.avg_unit_price, 0) AS avg_unit_price,
    COALESCE(ws.sales_days_count, 0) AS sales_days_count,
    (COALESCE(ws.units_sold, 0) > 0) AS had_sales,
    COALESCE(ws.current_stock, p.stock) AS current_stock,
    COALESCE(ws.product_active_current, p.active) AS product_active_current,
    true AS is_complete_week
FROM continuous_weeks cw
LEFT JOIN weekly_sales ws 
    ON cw.product_id = ws.product_id AND cw.week_start = ws.week_start
JOIN catalog.products p ON cw.product_id = p.id
LEFT JOIN catalog.categories c ON p.category_id = c.id
ORDER BY cw.product_id ASC, cw.week_start ASC;

-- Índices recomendados si no existieran (protegidos con IF NOT EXISTS)
CREATE INDEX IF NOT EXISTS idx_orders_payment_status ON sales.orders(payment_status);
CREATE INDEX IF NOT EXISTS idx_orders_status ON sales.orders(status);
