CREATE TABLE IF NOT EXISTS sales.shipping_settings (
    id BIGSERIAL PRIMARY KEY,
    pickup_enabled BOOLEAN DEFAULT true,
    pickup_label VARCHAR(100) DEFAULT 'Recoger en tienda',
    pickup_address TEXT,
    pickup_instructions TEXT,
    pickup_hours VARCHAR(255),
    local_delivery_enabled BOOLEAN DEFAULT true,
    local_delivery_cost NUMERIC(12,2) DEFAULT 50.00,
    local_delivery_state VARCHAR(100) DEFAULT 'Hidalgo',
    local_delivery_city VARCHAR(100) DEFAULT 'Huejutla de Reyes',
    local_delivery_postal_codes TEXT,
    external_shipping_enabled BOOLEAN DEFAULT true,
    external_shipping_mode VARCHAR(50) DEFAULT 'QUOTE_REQUIRED',
    external_shipping_fixed_cost NUMERIC(12,2),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO sales.shipping_settings (id, pickup_enabled, pickup_label, pickup_address, pickup_instructions, local_delivery_cost)
VALUES (1, true, 'Recoger en tienda', 'Casa de Música Castillo, Huejutla de Reyes, Hgo.', 'Presenta tu identificación oficial vigente al recoger tu pedido.', 50.00)
ON CONFLICT (id) DO NOTHING;

-- Add shipping fields to orders
ALTER TABLE sales.orders 
ADD COLUMN IF NOT EXISTS shipping_method VARCHAR(50),
ADD COLUMN IF NOT EXISTS shipping_label VARCHAR(100),
ADD COLUMN IF NOT EXISTS delivery_type VARCHAR(50),
ADD COLUMN IF NOT EXISTS pickup_address_snapshot TEXT;
