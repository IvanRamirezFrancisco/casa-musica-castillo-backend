-- V27__create_payment_proofs.sql
-- Fase 5: Creación de tabla para almacenamiento de comprobantes de pago

CREATE TABLE sales.payment_proofs (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    uploaded_by BIGINT NOT NULL,
    reviewed_by BIGINT NULL,
    
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(255) NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    
    reference_number VARCHAR(100) NULL,
    bank_name VARCHAR(100) NULL,
    amount_declared NUMERIC(10,2) NULL,
    transfer_date DATE NULL,
    notes TEXT NULL,
    
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REVIEW',
    rejection_reason TEXT NULL,
    
    reviewed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NULL,

    -- Foreign Keys
    CONSTRAINT fk_payment_proof_order FOREIGN KEY (order_id) REFERENCES sales.orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_payment_proof_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES auth.users(id) ON DELETE CASCADE,
    CONSTRAINT fk_payment_proof_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES auth.users(id) ON DELETE SET NULL,

    -- Constraints de negocio
    CONSTRAINT chk_payment_proof_status CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_payment_proof_content_type CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    CONSTRAINT chk_payment_proof_file_size CHECK (file_size_bytes > 0 AND file_size_bytes <= 5242880),
    CONSTRAINT chk_payment_proof_amount CHECK (amount_declared IS NULL OR amount_declared > 0)
);

-- Índices regulares
CREATE INDEX idx_payment_proofs_order_id ON sales.payment_proofs(order_id);
CREATE INDEX idx_payment_proofs_status ON sales.payment_proofs(status);
CREATE INDEX idx_payment_proofs_uploaded_by ON sales.payment_proofs(uploaded_by);
CREATE INDEX idx_payment_proofs_created_at ON sales.payment_proofs(created_at);

-- Índices únicos parciales
CREATE UNIQUE INDEX idx_unique_pending_proof_per_order 
    ON sales.payment_proofs(order_id) 
    WHERE status = 'PENDING_REVIEW';

CREATE UNIQUE INDEX idx_unique_approved_proof_per_order 
    ON sales.payment_proofs(order_id) 
    WHERE status = 'APPROVED';
