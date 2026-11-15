-- ============================================================
-- V31 — Create payments model (Fase 7A)
-- Tablas: sales.payments, sales.payment_events
-- Permisos: PAYMENT_READ, PAYMENT_MANAGE
-- ============================================================

-- ── 1. TABLE: sales.payments ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sales.payments (
    id                      BIGSERIAL PRIMARY KEY,
    order_id                BIGINT NOT NULL,
    created_by_user_id      BIGINT NULL,
    provider                VARCHAR(40) NOT NULL,
    method                  VARCHAR(40) NULL,
    status                  VARCHAR(40) NOT NULL,
    amount                  NUMERIC(10,2) NOT NULL,
    currency                VARCHAR(10) NOT NULL DEFAULT 'MXN',
    provider_payment_id     VARCHAR(150) NULL,
    provider_preference_id  VARCHAR(150) NULL,
    provider_order_id       VARCHAR(150) NULL,
    checkout_url            TEXT NULL,
    external_reference      VARCHAR(150) NOT NULL,
    idempotency_key         VARCHAR(150) NOT NULL,
    payer_email             VARCHAR(150) NULL,
    payer_id                VARCHAR(150) NULL,
    metadata_json           JSONB NULL,
    request_id              VARCHAR(100) NULL,
    provider_payload_hash   VARCHAR(100) NULL,
    paid_at                 TIMESTAMP NULL,
    expires_at              TIMESTAMP NULL,
    cancelled_at            TIMESTAMP NULL,
    failure_reason          TEXT NULL,
    raw_provider_status     VARCHAR(100) NULL,
    admin_notes             TEXT NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NULL,

    -- Foreign keys
    CONSTRAINT fk_payments_order
        FOREIGN KEY (order_id) REFERENCES sales.orders(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payments_user
        FOREIGN KEY (created_by_user_id) REFERENCES auth.users(id) ON DELETE SET NULL,

    -- Business constraints
    CONSTRAINT chk_payments_amount
        CHECK (amount > 0),
    CONSTRAINT chk_payments_currency
        CHECK (currency = 'MXN'),
    CONSTRAINT chk_payments_provider
        CHECK (provider IN ('BANK_TRANSFER','MERCADO_PAGO','PAYPAL','STRIPE','OPENPAY','MANUAL')),
    CONSTRAINT chk_payments_status
        CHECK (status IN ('CREATED','PENDING','PROCESSING','AUTHORIZED','PAID','REJECTED','CANCELLED','EXPIRED','FAILED','REFUNDED')),
    CONSTRAINT chk_payments_method
        CHECK (method IS NULL OR method IN ('BANK_TRANSFER','CARD','WALLET','CASH','PAYPAL','UNKNOWN')),

    -- Uniqueness
    CONSTRAINT uq_payments_idempotency_key     UNIQUE (idempotency_key),
    CONSTRAINT uq_payments_external_reference   UNIQUE (external_reference)
);

-- ── 2. INDEXES: sales.payments ────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_payments_order_id
    ON sales.payments(order_id);

CREATE INDEX IF NOT EXISTS idx_payments_status
    ON sales.payments(status);

CREATE INDEX IF NOT EXISTS idx_payments_provider
    ON sales.payments(provider);

CREATE INDEX IF NOT EXISTS idx_payments_provider_payment_id
    ON sales.payments(provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payments_provider_preference_id
    ON sales.payments(provider_preference_id)
    WHERE provider_preference_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payments_external_reference
    ON sales.payments(external_reference);

CREATE INDEX IF NOT EXISTS idx_payments_created_at
    ON sales.payments(created_at);

-- Partial unique index: sólo 1 pago activo por orden a la vez
-- Evita que se creen dos pagos simultáneos en estado activo para la misma orden.
-- Este índice NO debe ejecutarse manualmente; forma parte de la migración Flyway.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_active_per_order
    ON sales.payments(order_id)
    WHERE status IN ('CREATED','PENDING','PROCESSING','AUTHORIZED');

-- ── 3. TABLE: sales.payment_events ───────────────────────────────────
CREATE TABLE IF NOT EXISTS sales.payment_events (
    id                  BIGSERIAL PRIMARY KEY,
    payment_id          BIGINT NULL,
    order_id            BIGINT NULL,
    provider            VARCHAR(40) NOT NULL,
    event_type          VARCHAR(100) NOT NULL,
    provider_event_id   VARCHAR(150) NULL,
    raw_payload         TEXT NULL,
    payload_hash        VARCHAR(100) NULL,
    signature_valid     BOOLEAN NULL,
    processed           BOOLEAN NOT NULL DEFAULT false,
    processed_at        TIMESTAMP NULL,
    processed_by        VARCHAR(100) NULL,
    error_message       TEXT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Foreign keys
    CONSTRAINT fk_payment_events_payment
        FOREIGN KEY (payment_id) REFERENCES sales.payments(id) ON DELETE SET NULL,
    CONSTRAINT fk_payment_events_order
        FOREIGN KEY (order_id) REFERENCES sales.orders(id) ON DELETE SET NULL,

    -- Business constraint
    CONSTRAINT chk_payment_events_provider
        CHECK (provider IN ('BANK_TRANSFER','MERCADO_PAGO','PAYPAL','STRIPE','OPENPAY','MANUAL'))
);

-- ── 4. INDEXES: sales.payment_events ─────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_payment_events_payment_id
    ON sales.payment_events(payment_id);

CREATE INDEX IF NOT EXISTS idx_payment_events_order_id
    ON sales.payment_events(order_id);

CREATE INDEX IF NOT EXISTS idx_payment_events_provider
    ON sales.payment_events(provider);

CREATE INDEX IF NOT EXISTS idx_payment_events_provider_event_id
    ON sales.payment_events(provider_event_id)
    WHERE provider_event_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payment_events_processed
    ON sales.payment_events(processed);

CREATE INDEX IF NOT EXISTS idx_payment_events_created_at
    ON sales.payment_events(created_at);

-- Deduplicación de eventos externos (solo cuando provider_event_id no es NULL)
-- Permite múltiples eventos internos (provider_event_id = NULL) sin conflicto
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_events_provider_event_id
    ON sales.payment_events(provider, provider_event_id)
    WHERE provider_event_id IS NOT NULL;

-- ── 5. PERMISOS PAYMENT_READ y PAYMENT_MANAGE ────────────────────────
INSERT INTO auth.permissions (name, description, category, created_at)
VALUES
    ('PAYMENT_READ',   'Ver intentos de pago, historial y eventos',         'PAYMENT', NOW()),
    ('PAYMENT_MANAGE', 'Gestionar pagos: cancelar, reportes y conciliación', 'PAYMENT', NOW())
ON CONFLICT (name) DO NOTHING;

-- Asignar PAYMENT_READ y PAYMENT_MANAGE a ROLE_ADMIN (id=2) y ROLE_SUPER_ADMIN (id=3)
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM   auth.roles r
CROSS  JOIN auth.permissions p
WHERE  r.id IN (2, 3)
  AND  p.name IN ('PAYMENT_READ', 'PAYMENT_MANAGE')
ON CONFLICT DO NOTHING;
