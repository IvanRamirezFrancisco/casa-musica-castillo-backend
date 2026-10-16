CREATE TABLE IF NOT EXISTS sales.order_pickup_authorizations (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    phone VARCHAR(50),
    relationship VARCHAR(100),
    is_primary BOOLEAN DEFAULT false,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pickup_auth_order FOREIGN KEY (order_id) REFERENCES sales.orders (id) ON DELETE CASCADE
);

-- Add pickup fields to orders
ALTER TABLE sales.orders
ADD COLUMN IF NOT EXISTS pickup_status VARCHAR(50) DEFAULT 'NOT_APPLICABLE',
ADD COLUMN IF NOT EXISTS pickup_code_hash VARCHAR(255),
ADD COLUMN IF NOT EXISTS pickup_code_encrypted VARCHAR(255),
ADD COLUMN IF NOT EXISTS pickup_code_last4 VARCHAR(4),
ADD COLUMN IF NOT EXISTS pickup_ready_at TIMESTAMP WITHOUT TIME ZONE,
ADD COLUMN IF NOT EXISTS picked_up_at TIMESTAMP WITHOUT TIME ZONE,
ADD COLUMN IF NOT EXISTS picked_up_by_name VARCHAR(255),
ADD COLUMN IF NOT EXISTS verified_by_admin_id BIGINT;

CREATE TABLE IF NOT EXISTS sales.order_pickup_verification_attempts (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    admin_id BIGINT NOT NULL,
    attempted_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    success BOOLEAN DEFAULT false,
    failure_reason VARCHAR(255),
    CONSTRAINT fk_pickup_attempt_order FOREIGN KEY (order_id) REFERENCES sales.orders (id) ON DELETE CASCADE
);
