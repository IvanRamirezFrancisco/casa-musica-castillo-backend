\set ON_ERROR_STOP on
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'castillo_staging' OR current_user NOT IN ('castillo_staging', 'castillo_staging_migrator') THEN
        RAISE EXCEPTION 'Fixtures require the dedicated staging database and role';
    END IF;
END $$;

INSERT INTO catalog.categories (name, active, created_at)
VALUES ('Instrumentos de prueba', true, now())
ON CONFLICT (name) DO NOTHING;

INSERT INTO catalog.products
    (sku, name, description, price, stock, active, featured, created_at, category_id,
     average_rating, review_count, one_star_count, two_star_count, three_star_count, four_star_count, five_star_count)
SELECT fixture.sku, fixture.name, 'Producto sintetico de staging', fixture.price, 20, true, true, now(), c.id,
       0, 0, 0, 0, 0, 0, 0
FROM (VALUES ('STG-GUITAR-01', 'Guitarra de prueba uno', 500),
             ('STG-GUITAR-02', 'Guitarra de prueba dos', 700)) AS fixture(sku, name, price)
JOIN catalog.categories c ON c.name = 'Instrumentos de prueba'
ON CONFLICT (sku) DO NOTHING;

\if :{?fixture_username}
INSERT INTO sales.orders
    (order_number, created_at, payment_status, shipping_status, status, subtotal, total, user_id)
SELECT 'STG-' || u.id, now(), 'PENDING', 'PENDING', 'PENDING', 500, 500, u.id
FROM auth.users u
WHERE u.username = :'fixture_username' AND u.username LIKE 'stg-%'
  AND u.email = 'castillo.lab.' || substring(u.username from 5) || '@gmail.com'
ON CONFLICT (order_number) DO NOTHING;

INSERT INTO sales.order_items
    (created_at, product_name, product_sku, quantity, subtotal, unit_price, order_id, product_id)
SELECT now(), p.name, p.sku, 1, 500, 500, o.id, p.id
FROM sales.orders o
JOIN auth.users u ON u.id = o.user_id
JOIN catalog.products p ON p.sku = 'STG-GUITAR-01'
WHERE o.order_number = 'STG-' || u.id AND u.username = :'fixture_username' AND u.username LIKE 'stg-%'
  AND u.email = 'castillo.lab.' || substring(u.username from 5) || '@gmail.com'
  AND NOT EXISTS (SELECT 1 FROM sales.order_items oi WHERE oi.order_id = o.id);
\endif
COMMIT;
