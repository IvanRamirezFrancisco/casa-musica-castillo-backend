-- V26__add_order_cancellation_fields.sql
-- Agregar campos para rastrear quién canceló el pedido y desde dónde (ADMIN, CUSTOMER)

ALTER TABLE sales.orders
    ADD COLUMN IF NOT EXISTS cancelled_by BIGINT NULL,
    ADD COLUMN IF NOT EXISTS cancel_source VARCHAR(20) NULL;

-- Restricción opcional para asegurar valores válidos sin afectar registros nulos
ALTER TABLE sales.orders
    ADD CONSTRAINT chk_orders_cancel_source
    CHECK (cancel_source IS NULL OR cancel_source IN ('CUSTOMER', 'ADMIN', 'SYSTEM'));
