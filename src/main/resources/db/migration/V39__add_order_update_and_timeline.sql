-- V39__add_order_update_and_timeline.sql
-- 1. Insertar ORDER_UPDATE de manera idempotente
INSERT INTO auth.permissions (name, description, category, created_at, updated_at)
VALUES ('ORDER_UPDATE', 'Actualizar estado de órdenes y envíos', 'ORDER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (name) DO NOTHING;

-- 2. Asignar ORDER_UPDATE a roles correspondientes si existen
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM auth.roles r CROSS JOIN auth.permissions p
WHERE r.name IN ('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'ROLE_STORE_MANAGER', 'ROLE_ORDER_MANAGER') 
  AND p.name = 'ORDER_UPDATE'
ON CONFLICT DO NOTHING;

-- 3. Crear tabla de timeline para órdenes
CREATE TABLE IF NOT EXISTS sales.order_timeline_events (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    previous_order_status VARCHAR(50),
    new_order_status VARCHAR(50),
    previous_payment_status VARCHAR(50),
    new_payment_status VARCHAR(50),
    previous_shipping_status VARCHAR(50),
    new_shipping_status VARCHAR(50),
    actor_user_id BIGINT,
    actor_type VARCHAR(50),
    source VARCHAR(50),
    visibility VARCHAR(20) DEFAULT 'BOTH',
    metadata_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_timeline_order FOREIGN KEY (order_id) REFERENCES sales.orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_timeline_actor FOREIGN KEY (actor_user_id) REFERENCES auth.users(id) ON DELETE SET NULL
);

-- 4. Índices recomendados
CREATE INDEX IF NOT EXISTS idx_order_timeline_order_created ON sales.order_timeline_events(order_id, created_at);
CREATE INDEX IF NOT EXISTS idx_order_timeline_event_type ON sales.order_timeline_events(event_type);
CREATE INDEX IF NOT EXISTS idx_order_timeline_actor ON sales.order_timeline_events(actor_user_id);
CREATE INDEX IF NOT EXISTS idx_order_timeline_visibility ON sales.order_timeline_events(visibility);
