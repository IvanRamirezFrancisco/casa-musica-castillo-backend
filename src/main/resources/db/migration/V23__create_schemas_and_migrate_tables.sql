-- =====================================================================
-- V23: Crear schemas y migrar tablas desde public a schemas organizados
-- =====================================================================
-- Schemas:
--   auth     → Usuarios, roles, permisos, sesiones, tokens
--   security → Auditoría, intentos de login, recovery, settings
--   catalog  → Productos, categorías, marcas, imágenes, reseñas
--   sales    → Órdenes, carrito, cupones, wishlists
--   customer → Direcciones
--   ops      → Automatizaciones, backups, mantenimiento, inventario
-- =====================================================================
-- ── 1. CREAR SCHEMAS ────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS security;
CREATE SCHEMA IF NOT EXISTS catalog;
CREATE SCHEMA IF NOT EXISTS sales;
CREATE SCHEMA IF NOT EXISTS customer;
CREATE SCHEMA IF NOT EXISTS ops;
-- ── 2. MOVER TABLAS AL SCHEMA auth ─────────────────────────────────
ALTER TABLE public.users
SET SCHEMA auth;
ALTER TABLE public.roles
SET SCHEMA auth;
ALTER TABLE public.permissions
SET SCHEMA auth;
ALTER TABLE public.user_roles
SET SCHEMA auth;
ALTER TABLE public.role_permissions
SET SCHEMA auth;
ALTER TABLE public.active_sessions
SET SCHEMA auth;
ALTER TABLE public.refresh_tokens
SET SCHEMA auth;
ALTER TABLE public.backup_codes
SET SCHEMA auth;
ALTER TABLE public.two_factor_tokens
SET SCHEMA auth;
ALTER TABLE public.verification_tokens
SET SCHEMA auth;
-- ── 3. MOVER TABLAS AL SCHEMA security ─────────────────────────────
ALTER TABLE public.login_attempts
SET SCHEMA security;
ALTER TABLE public.password_reset_tokens
SET SCHEMA security;
ALTER TABLE public.password_recovery_attempts
SET SCHEMA security;
ALTER TABLE public.audit_logs
SET SCHEMA security;
ALTER TABLE public.security_settings
SET SCHEMA security;
ALTER TABLE public.staff_invitations
SET SCHEMA security;
ALTER TABLE public.countries
SET SCHEMA security;
-- ── 4. MOVER TABLAS AL SCHEMA catalog ──────────────────────────────
ALTER TABLE public.products
SET SCHEMA catalog;
ALTER TABLE public.categories
SET SCHEMA catalog;
ALTER TABLE public.brands
SET SCHEMA catalog;
ALTER TABLE public.product_images
SET SCHEMA catalog;
ALTER TABLE public.product_attributes
SET SCHEMA catalog;
ALTER TABLE public.product_price_history
SET SCHEMA catalog;
ALTER TABLE public.product_reviews
SET SCHEMA catalog;
ALTER TABLE public.review_helpfulness
SET SCHEMA catalog;
-- ── 5. MOVER TABLAS AL SCHEMA sales ────────────────────────────────
ALTER TABLE public.orders
SET SCHEMA sales;
ALTER TABLE public.order_items
SET SCHEMA sales;
ALTER TABLE public.shopping_carts
SET SCHEMA sales;
ALTER TABLE public.cart_items
SET SCHEMA sales;
ALTER TABLE public.coupons
SET SCHEMA sales;
ALTER TABLE public.coupon_usage
SET SCHEMA sales;
ALTER TABLE public.coupon_applicable_categories
SET SCHEMA sales;
ALTER TABLE public.coupon_applicable_products
SET SCHEMA sales;
ALTER TABLE public.wishlists
SET SCHEMA sales;
-- ── 6. MOVER TABLAS AL SCHEMA customer ─────────────────────────────
ALTER TABLE public.addresses
SET SCHEMA customer;
-- ── 7. MOVER TABLAS AL SCHEMA ops ──────────────────────────────────
ALTER TABLE public.system_automations
SET SCHEMA ops;
ALTER TABLE public.automation_execution_logs
SET SCHEMA ops;
ALTER TABLE public.backup_logs
SET SCHEMA ops;
ALTER TABLE public.maintenance_config
SET SCHEMA ops;
ALTER TABLE public.maintenance_logs
SET SCHEMA ops;
ALTER TABLE public.inventory_prediction_config
SET SCHEMA ops;
-- ── 8. CONFIGURAR search_path PARA LA SESIÓN DE MIGRACIÓN ──────────
-- El search_path a nivel de DATABASE se configura fuera de Flyway
-- (en application.yml vía spring.datasource.hikari.connection-init-sql)
-- porque ALTER DATABASE no puede ejecutarse dentro de una transacción.
SET search_path TO auth,
    security,
    catalog,
    sales,
    customer,
    ops,
    public;
-- ── 9. RECREAR STORED PROCEDURES CON REFERENCIAS CROSS-SCHEMA ─────
-- sp_calculate_coupon_discount
CREATE OR REPLACE PROCEDURE sales.sp_calculate_coupon_discount(
        IN p_coupon_id bigint,
        IN p_amount numeric,
        OUT p_discount numeric
    ) LANGUAGE plpgsql AS $$
DECLARE v_coupon RECORD;
BEGIN p_discount := 0;
SELECT * INTO v_coupon
FROM sales.coupons
WHERE id = p_coupon_id
    AND is_active = true
    AND (
        valid_until IS NULL
        OR valid_until > NOW()
    )
    AND (
        usage_limit IS NULL
        OR times_used < usage_limit
    );
IF NOT FOUND THEN RETURN;
END IF;
IF v_coupon.discount_type = 'PERCENTAGE' THEN p_discount := ROUND(p_amount * v_coupon.discount_value / 100, 2);
ELSE p_discount := LEAST(v_coupon.discount_value, p_amount);
END IF;
IF v_coupon.maximum_discount IS NOT NULL
AND p_discount > v_coupon.maximum_discount THEN p_discount := v_coupon.maximum_discount;
END IF;
END;
$$;
-- sp_apply_coupon_to_cart
CREATE OR REPLACE PROCEDURE sales.sp_apply_coupon_to_cart(
        IN p_cart_id bigint,
        IN p_coupon_id bigint
    ) LANGUAGE plpgsql AS $$
DECLARE v_subtotal NUMERIC(10, 2);
v_discount NUMERIC(10, 2);
v_coupon RECORD;
BEGIN
SELECT subtotal INTO v_subtotal
FROM sales.shopping_carts
WHERE id = p_cart_id;
IF NOT FOUND THEN RETURN;
END IF;
SELECT * INTO v_coupon
FROM sales.coupons
WHERE id = p_coupon_id
    AND is_active = true;
IF NOT FOUND THEN RETURN;
END IF;
CALL sales.sp_calculate_coupon_discount(p_coupon_id, v_subtotal, v_discount);
UPDATE sales.shopping_carts
SET discount = v_discount,
    coupon_code = v_coupon.code,
    total = subtotal + COALESCE(tax, 0) - v_discount
WHERE id = p_cart_id;
END;
$$;
-- sp_apply_coupon_to_order
CREATE OR REPLACE PROCEDURE sales.sp_apply_coupon_to_order(
        IN p_order_id bigint,
        IN p_coupon_id bigint
    ) LANGUAGE plpgsql AS $$
DECLARE v_subtotal NUMERIC(10, 2);
v_discount NUMERIC(10, 2);
v_coupon RECORD;
BEGIN
SELECT subtotal INTO v_subtotal
FROM sales.orders
WHERE id = p_order_id;
IF NOT FOUND THEN RETURN;
END IF;
SELECT * INTO v_coupon
FROM sales.coupons
WHERE id = p_coupon_id
    AND is_active = true;
IF NOT FOUND THEN RETURN;
END IF;
CALL sales.sp_calculate_coupon_discount(p_coupon_id, v_subtotal, v_discount);
UPDATE sales.orders
SET discount = v_discount,
    total = subtotal + COALESCE(tax, 0) + COALESCE(shipping, 0) - v_discount
WHERE id = p_order_id;
UPDATE sales.coupons
SET times_used = COALESCE(times_used, 0) + 1
WHERE id = p_coupon_id;
END;
$$;
-- sp_calculate_order_totals
CREATE OR REPLACE PROCEDURE sales.sp_calculate_order_totals(IN p_order_id bigint) LANGUAGE plpgsql AS $$
DECLARE v_subtotal NUMERIC(10, 2);
v_discount NUMERIC(10, 2);
v_tax NUMERIC(10, 2);
v_shipping NUMERIC(10, 2);
BEGIN
SELECT COALESCE(SUM(subtotal), 0) INTO v_subtotal
FROM sales.order_items
WHERE order_id = p_order_id;
SELECT COALESCE(discount, 0),
    COALESCE(tax, 0),
    COALESCE(shipping, 0) INTO v_discount,
    v_tax,
    v_shipping
FROM sales.orders
WHERE id = p_order_id;
UPDATE sales.orders
SET subtotal = v_subtotal,
    total = v_subtotal + v_tax + v_shipping - v_discount,
    updated_at = NOW()
WHERE id = p_order_id;
END;
$$;
-- sp_cancel_order
CREATE OR REPLACE PROCEDURE sales.sp_cancel_order(IN p_order_id bigint, IN p_reason text) LANGUAGE plpgsql AS $$
DECLARE v_item RECORD;
BEGIN
UPDATE sales.orders
SET status = 'CANCELLED',
    cancellation_reason = p_reason,
    cancelled_at = NOW(),
    updated_at = NOW()
WHERE id = p_order_id;
FOR v_item IN
SELECT product_id,
    quantity
FROM sales.order_items
WHERE order_id = p_order_id LOOP
UPDATE catalog.products
SET stock = stock + v_item.quantity,
    updated_at = NOW()
WHERE id = v_item.product_id;
END LOOP;
END;
$$;
-- sp_generate_order_number
CREATE OR REPLACE PROCEDURE sales.sp_generate_order_number(OUT p_order_number character varying) LANGUAGE plpgsql AS $$ BEGIN p_order_number := 'ORD-' || TO_CHAR(NOW(), 'YYYYMMDD') || '-' || LPAD(
        CAST(FLOOR(RANDOM() * 99999 + 1) AS TEXT),
        5,
        '0'
    );
END;
$$;
-- sp_transfer_cart_to_order
CREATE OR REPLACE PROCEDURE sales.sp_transfer_cart_to_order(IN p_cart_id bigint, OUT p_order_id bigint) LANGUAGE plpgsql AS $$
DECLARE v_cart RECORD;
v_item RECORD;
v_order_no VARCHAR;
BEGIN
SELECT * INTO v_cart
FROM sales.shopping_carts
WHERE id = p_cart_id
    AND status = 'ACTIVE';
IF NOT FOUND THEN RAISE EXCEPTION 'Carrito no encontrado o no activo';
END IF;
CALL sales.sp_generate_order_number(v_order_no);
INSERT INTO sales.orders (
        order_number,
        user_id,
        subtotal,
        tax,
        shipping,
        discount,
        total,
        status,
        payment_status,
        shipping_status,
        created_at,
        updated_at
    )
VALUES (
        v_order_no,
        v_cart.user_id,
        v_cart.subtotal,
        v_cart.tax,
        0,
        COALESCE(v_cart.discount, 0),
        v_cart.total,
        'PENDING',
        'PENDING',
        'PENDING',
        NOW(),
        NOW()
    )
RETURNING id INTO p_order_id;
FOR v_item IN
SELECT ci.*,
    p.name,
    p.sku
FROM sales.cart_items ci
    JOIN catalog.products p ON p.id = ci.product_id
WHERE ci.cart_id = p_cart_id LOOP
INSERT INTO sales.order_items (
        order_id,
        product_id,
        product_name,
        product_sku,
        unit_price,
        quantity,
        subtotal,
        created_at
    )
VALUES (
        p_order_id,
        v_item.product_id,
        v_item.name,
        v_item.sku,
        v_item.unit_price,
        v_item.quantity,
        v_item.subtotal,
        NOW()
    );
UPDATE catalog.products
SET stock = stock - v_item.quantity,
    updated_at = NOW()
WHERE id = v_item.product_id;
END LOOP;
UPDATE sales.shopping_carts
SET status = 'CONVERTED',
    updated_at = NOW()
WHERE id = p_cart_id;
END;
$$;
-- sp_recalculate_product_rating
CREATE OR REPLACE PROCEDURE catalog.sp_recalculate_product_rating(IN p_product_id bigint) LANGUAGE plpgsql AS $$
DECLARE v_count INTEGER;
v_avg NUMERIC(3, 2);
v_five INTEGER;
v_four INTEGER;
v_three INTEGER;
v_two INTEGER;
v_one INTEGER;
BEGIN
SELECT COUNT(*),
    COALESCE(AVG(rating), 0),
    COUNT(*) FILTER (
        WHERE rating = 5
    ),
    COUNT(*) FILTER (
        WHERE rating = 4
    ),
    COUNT(*) FILTER (
        WHERE rating = 3
    ),
    COUNT(*) FILTER (
        WHERE rating = 2
    ),
    COUNT(*) FILTER (
        WHERE rating = 1
    ) INTO v_count,
    v_avg,
    v_five,
    v_four,
    v_three,
    v_two,
    v_one
FROM catalog.product_reviews
WHERE product_id = p_product_id
    AND status = 'APPROVED';
UPDATE catalog.products
SET review_count = v_count,
    average_rating = v_avg,
    five_star_count = v_five,
    four_star_count = v_four,
    three_star_count = v_three,
    two_star_count = v_two,
    one_star_count = v_one,
    updated_at = NOW()
WHERE id = p_product_id;
END;
$$;
-- sp_update_user_stats
CREATE OR REPLACE PROCEDURE auth.sp_update_user_stats(IN p_user_id bigint) LANGUAGE plpgsql AS $$ BEGIN
UPDATE auth.users u
SET total_orders = sub.cnt,
    total_spent = sub.total
FROM (
        SELECT COUNT(*) AS cnt,
            COALESCE(SUM(total), 0) AS total
        FROM sales.orders
        WHERE user_id = p_user_id
            AND status NOT IN ('CANCELLED')
    ) sub
WHERE u.id = p_user_id;
END;
$$;
-- sp_check_wishlist_back_in_stock
CREATE OR REPLACE PROCEDURE sales.sp_check_wishlist_back_in_stock(IN p_user_id bigint) LANGUAGE plpgsql AS $$ BEGIN
UPDATE sales.wishlists w
SET notified_back_in_stock = true,
    updated_at = NOW()
FROM catalog.products p
WHERE w.product_id = p.id
    AND w.user_id = p_user_id
    AND p.stock > 0
    AND w.notified_back_in_stock = false;
END;
$$;
-- sp_check_wishlist_discounts
CREATE OR REPLACE PROCEDURE sales.sp_check_wishlist_discounts(IN p_user_id bigint) LANGUAGE plpgsql AS $$ BEGIN
UPDATE sales.wishlists w
SET notified_discount = true,
    updated_at = NOW()
FROM catalog.products p
WHERE w.product_id = p.id
    AND w.user_id = p_user_id
    AND p.discount_price IS NOT NULL
    AND p.discount_price < p.price
    AND w.notified_discount = false;
END;
$$;
-- sp_get_wishlist_with_price_comparison
CREATE OR REPLACE PROCEDURE sales.sp_get_wishlist_with_price_comparison(IN p_user_id bigint) LANGUAGE plpgsql AS $$ BEGIN -- Procedimiento de consulta de comparación de precios en wishlist
    -- El resultado se obtiene vía consulta directa en el servicio
    PERFORM 1;
END;
$$;
-- sp_move_wishlist_to_cart
CREATE OR REPLACE PROCEDURE sales.sp_move_wishlist_to_cart(IN p_wishlist_id bigint) LANGUAGE plpgsql AS $$
DECLARE v_cart_id BIGINT;
v_wishlist RECORD;
BEGIN
SELECT * INTO v_wishlist
FROM sales.wishlists
WHERE id = p_wishlist_id;
IF NOT FOUND THEN RETURN;
END IF;
SELECT id INTO v_cart_id
FROM sales.shopping_carts
WHERE user_id = v_wishlist.user_id
    AND status = 'ACTIVE'
LIMIT 1;
IF v_cart_id IS NULL THEN
INSERT INTO sales.shopping_carts (
        user_id,
        subtotal,
        tax,
        total,
        status,
        created_at,
        updated_at
    )
VALUES (
        v_wishlist.user_id,
        0,
        0,
        0,
        'ACTIVE',
        NOW(),
        NOW()
    )
RETURNING id INTO v_cart_id;
END IF;
INSERT INTO sales.cart_items (
        cart_id,
        product_id,
        quantity,
        unit_price,
        subtotal,
        added_at
    )
SELECT v_cart_id,
    v_wishlist.product_id,
    1,
    p.price,
    p.price,
    NOW()
FROM catalog.products p
WHERE p.id = v_wishlist.product_id;
DELETE FROM sales.wishlists
WHERE id = p_wishlist_id;
END;
$$;
-- ── 10. ELIMINAR STORED PROCEDURES ANTIGUOS DEL SCHEMA public ──────
DROP PROCEDURE IF EXISTS public.sp_calculate_coupon_discount(bigint, numeric);
DROP PROCEDURE IF EXISTS public.sp_apply_coupon_to_cart(bigint, bigint);
DROP PROCEDURE IF EXISTS public.sp_apply_coupon_to_order(bigint, bigint);
DROP PROCEDURE IF EXISTS public.sp_calculate_order_totals(bigint);
DROP PROCEDURE IF EXISTS public.sp_cancel_order(bigint, text);
DROP PROCEDURE IF EXISTS public.sp_generate_order_number();
DROP PROCEDURE IF EXISTS public.sp_transfer_cart_to_order(bigint);
DROP PROCEDURE IF EXISTS public.sp_check_wishlist_back_in_stock(bigint);
DROP PROCEDURE IF EXISTS public.sp_check_wishlist_discounts(bigint);
DROP PROCEDURE IF EXISTS public.sp_get_wishlist_with_price_comparison(bigint);
DROP PROCEDURE IF EXISTS public.sp_move_wishlist_to_cart(bigint);
DROP PROCEDURE IF EXISTS public.sp_recalculate_product_rating(bigint);
DROP PROCEDURE IF EXISTS public.sp_update_user_stats(bigint);
-- ── 11. GRANT USAGE EN TODOS LOS SCHEMAS AL ROL DE LA APLICACIÓN ──
-- (Ajustar 'spring_app' si tu rol tiene otro nombre)
DO $$
DECLARE v_schema TEXT;
v_role TEXT := 'spring_app';
BEGIN FOR v_schema IN
SELECT unnest(
        ARRAY ['auth','security','catalog','sales','customer','ops']
    ) LOOP EXECUTE format(
        'GRANT USAGE ON SCHEMA %I TO %I',
        v_schema,
        v_role
    );
EXECUTE format(
    'GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA %I TO %I',
    v_schema,
    v_role
);
EXECUTE format(
    'GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA %I TO %I',
    v_schema,
    v_role
);
EXECUTE format(
    'ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON TABLES TO %I',
    v_schema,
    v_role
);
EXECUTE format(
    'ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON SEQUENCES TO %I',
    v_schema,
    v_role
);
END LOOP;
EXCEPTION
WHEN OTHERS THEN RAISE NOTICE 'Permisos: algunos grants no se aplicaron (normal en Railway): %',
SQLERRM;
END;
$$;