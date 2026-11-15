CREATE TABLE IF NOT EXISTS sales.bank_transfer_settings (
    id BIGSERIAL PRIMARY KEY,
    bank_name VARCHAR(100) NOT NULL,
    account_holder VARCHAR(150) NOT NULL,
    clabe VARCHAR(18) NOT NULL CHECK (length(clabe) = 18 AND clabe ~ '^[0-9]+$'),
    account_number VARCHAR(30) CHECK (account_number IS NULL OR account_number ~ '^[0-9]+$'),
    reference_instructions VARCHAR(500),
    additional_instructions TEXT,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_bank_settings_updated_by FOREIGN KEY (updated_by) REFERENCES auth.users(id) ON DELETE SET NULL
);

-- Asegurar que solo exista una configuración activa a la vez
CREATE UNIQUE INDEX idx_bank_transfer_settings_active ON sales.bank_transfer_settings(active) WHERE active = true;

-- Índice para consultas de auditoría
CREATE INDEX idx_bank_transfer_settings_updated_by ON sales.bank_transfer_settings(updated_by);
