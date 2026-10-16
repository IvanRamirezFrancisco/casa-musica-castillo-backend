-- V44__create_clustering_live_view_and_exclusions.sql

-- 1. Tablas de exclusión
CREATE TABLE analytics.purchase_clustering_excluded_categories (
    category_id BIGINT PRIMARY KEY REFERENCES catalog.categories(id),
    reason VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE analytics.purchase_clustering_excluded_products (
    product_id BIGINT PRIMARY KEY REFERENCES catalog.products(id),
    reason VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Inserciones seguras
INSERT INTO analytics.purchase_clustering_excluded_categories (category_id, reason)
SELECT c.id, 'Categoría utilizada para pruebas de imágenes'
FROM catalog.categories c
WHERE c.id = 22
ON CONFLICT (category_id) DO NOTHING;

INSERT INTO analytics.purchase_clustering_excluded_products (product_id, reason)
SELECT p.id, 'Producto de prueba excluido de la analítica de clustering'
FROM catalog.products p
WHERE p.sku IN ('SKU-TEST-002', 'SKU-TEST-003')
ON CONFLICT (product_id) DO NOTHING;

-- 3. Vista Operativa en Vivo (live_v1)
CREATE VIEW analytics.vw_clustering_order_features_live_v1 AS
 WITH valid_orders AS (
         SELECT o.id AS order_id,
            o.order_number,
            o.created_at,
            round((o.subtotal)::numeric, 2) AS subtotal,
            round(COALESCE(o.discount, (0)::numeric), 2) AS discount,
            round(COALESCE(o.shipping, (0)::numeric), 2) AS shipping,
            round((o.total)::numeric, 2) AS total,
            COALESCE(NULLIF(btrim((o.payment_method)::text), ''::text), 'UNKNOWN'::text) AS payment_method,
            COALESCE(NULLIF(btrim((o.delivery_type)::text), ''::text), NULLIF(btrim((o.shipping_method)::text), ''::text), 'UNKNOWN'::text) AS delivery_type
           FROM sales.orders o
          WHERE (((o.payment_status)::text = 'PAID'::text) 
            AND (COALESCE((o.status)::text, 'UNKNOWN'::text) <> 'CANCELLED'::text) 
            AND (o.order_number IS NOT NULL) 
            AND (o.created_at IS NOT NULL) 
            AND (o.subtotal IS NOT NULL) 
            AND (o.subtotal > (0)::numeric) 
            AND (o.total IS NOT NULL) 
            AND (o.total > (0)::numeric)
            AND NOT EXISTS (
                SELECT 1
                FROM sales.order_items oi_excluded
                JOIN catalog.products p_excluded ON p_excluded.id = oi_excluded.product_id
                WHERE oi_excluded.order_id = o.id
                  AND (
                      EXISTS (
                          SELECT 1
                          FROM analytics.purchase_clustering_excluded_products ep
                          WHERE ep.product_id = oi_excluded.product_id
                            AND ep.active = TRUE
                      )
                      OR
                      EXISTS (
                          SELECT 1
                          FROM analytics.purchase_clustering_excluded_categories ec
                          WHERE ec.category_id = p_excluded.category_id
                            AND ec.active = TRUE
                      )
                  )
            ))
        ), consolidated_items AS (
         SELECT oi.order_id,
            oi.product_id,
            COALESCE(p.category_id, ('-1'::integer)::bigint) AS category_id,
            (sum(oi.quantity))::integer AS quantity,
            round(sum(oi.subtotal), 2) AS line_subtotal,
            round((sum(oi.subtotal) / (NULLIF(sum(oi.quantity), 0))::numeric), 2) AS effective_unit_price
           FROM ((sales.order_items oi
             JOIN valid_orders vo_1 ON ((vo_1.order_id = oi.order_id)))
             JOIN catalog.products p ON ((p.id = oi.product_id)))
          WHERE ((oi.order_id IS NOT NULL) AND (oi.product_id IS NOT NULL) AND (oi.quantity IS NOT NULL) AND (oi.quantity > 0) AND (oi.subtotal IS NOT NULL) AND (oi.subtotal > (0)::numeric))
          GROUP BY oi.order_id, oi.product_id, COALESCE(p.category_id, ('-1'::integer)::bigint)
         HAVING (sum(oi.quantity) > 0)
        ), order_features AS (
         SELECT ci.order_id,
            (sum(ci.quantity))::integer AS total_units,
            (count(*))::integer AS unique_products,
            (count(DISTINCT ci.category_id))::integer AS unique_categories,
            (COALESCE(sum(ci.quantity) FILTER (WHERE (ci.category_id = 10)), (0)::bigint))::integer AS accessory_units,
            (COALESCE(sum(ci.quantity) FILTER (WHERE (ci.category_id = ANY (ARRAY[(17)::bigint, (20)::bigint, (21)::bigint]))), (0)::bigint))::integer AS instrument_units,
            (COALESCE(sum(ci.quantity) FILTER (WHERE (ci.category_id = 17)), (0)::bigint))::integer AS violin_units,
            (COALESCE(sum(ci.quantity) FILTER (WHERE (ci.category_id = 20)), (0)::bigint))::integer AS jarana_units,
            (COALESCE(sum(ci.quantity) FILTER (WHERE (ci.category_id = 21)), (0)::bigint))::integer AS quinta_units,
            (COALESCE(sum(ci.quantity) FILTER (WHERE (ci.category_id <> ALL (ARRAY[(10)::bigint, (17)::bigint, (20)::bigint, (21)::bigint]))), (0)::bigint))::integer AS other_units,
            (COALESCE(sum(ci.line_subtotal) FILTER (WHERE (ci.category_id = 10)), 0.00))::numeric(12,2) AS accessory_amount,
            (COALESCE(sum(ci.line_subtotal) FILTER (WHERE (ci.category_id = ANY (ARRAY[(17)::bigint, (20)::bigint, (21)::bigint]))), 0.00))::numeric(12,2) AS instrument_amount,
            (COALESCE(sum(ci.line_subtotal) FILTER (WHERE (ci.category_id <> ALL (ARRAY[(10)::bigint, (17)::bigint, (20)::bigint, (21)::bigint]))), 0.00))::numeric(12,2) AS other_amount,
            round(sum(ci.line_subtotal), 2) AS items_subtotal,
            round((sum(ci.line_subtotal) / (NULLIF(sum(ci.quantity), 0))::numeric), 2) AS avg_unit_price,
            max(ci.effective_unit_price) AS max_unit_price,
            min(ci.effective_unit_price) AS min_unit_price,
            max(ci.line_subtotal) AS largest_product_amount
           FROM consolidated_items ci
          GROUP BY ci.order_id
        )
 SELECT vo.order_id,
    vo.order_number,
    vo.created_at,
    vo.subtotal,
    vo.discount,
    vo.shipping,
    vo.total,
    ofe.items_subtotal,
    round((ofe.items_subtotal - vo.subtotal), 2) AS subtotal_difference,
    round(((vo.discount * 100.0) / NULLIF(vo.subtotal, (0)::numeric)), 4) AS discount_pct,
    round(((vo.shipping * 100.0) / NULLIF(vo.subtotal, (0)::numeric)), 4) AS shipping_pct,
    ofe.total_units,
    ofe.unique_products,
    ofe.unique_categories,
    round(((ofe.total_units)::numeric / (NULLIF(ofe.unique_products, 0))::numeric), 4) AS units_per_product,
    ofe.accessory_units,
    ofe.instrument_units,
    ofe.violin_units,
    ofe.jarana_units,
    ofe.quinta_units,
    ofe.other_units,
    round((ofe.accessory_amount / NULLIF(ofe.items_subtotal, (0)::numeric)), 4) AS accessory_value_share,
    round((ofe.instrument_amount / NULLIF(ofe.items_subtotal, (0)::numeric)), 4) AS instrument_value_share,
    round((ofe.other_amount / NULLIF(ofe.items_subtotal, (0)::numeric)), 4) AS other_value_share,
    round((ofe.largest_product_amount / NULLIF(ofe.items_subtotal, (0)::numeric)), 4) AS largest_product_value_share,
    ofe.avg_unit_price,
    ofe.max_unit_price,
    ofe.min_unit_price,
    (EXTRACT(hour FROM vo.created_at))::integer AS hour_of_day,
    (EXTRACT(isodow FROM vo.created_at))::integer AS day_of_week,
        CASE
            WHEN (EXTRACT(isodow FROM vo.created_at) = ANY (ARRAY[(6)::numeric, (7)::numeric])) THEN 1
            ELSE 0
        END AS is_weekend,
    vo.payment_method,
    vo.delivery_type,
    CASE 
        WHEN vo.order_number LIKE 'MLSEED-V1-%' THEN 'TRAINING_SEED_V1'::character varying(30)
        ELSE 'LIVE_OPERATIONAL'::character varying(30)
    END AS data_origin
   FROM (valid_orders vo
     JOIN order_features ofe ON ((ofe.order_id = vo.order_id)));
