-- Update sales.orders to link to authorization id
ALTER TABLE sales.orders
ADD COLUMN IF NOT EXISTS picked_up_by_authorization_id BIGINT,
ADD CONSTRAINT fk_order_picked_up_auth FOREIGN KEY (picked_up_by_authorization_id) REFERENCES sales.order_pickup_authorizations(id) ON DELETE SET NULL;

-- Update sales.order_pickup_verification_attempts for traceability
ALTER TABLE sales.order_pickup_verification_attempts
ADD COLUMN IF NOT EXISTS pickup_authorization_id BIGINT,
ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45),
ADD COLUMN IF NOT EXISTS user_agent TEXT,
ADD CONSTRAINT fk_pickup_attempt_auth FOREIGN KEY (pickup_authorization_id) REFERENCES sales.order_pickup_authorizations(id) ON DELETE SET NULL;
