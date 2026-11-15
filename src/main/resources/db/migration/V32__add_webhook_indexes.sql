-- V32: Añadir índices útiles para deduplicación y consulta eficiente de payment_events (Fase 7C)
-- No destructivo: no modifica datos, no borra columnas, no cambia tipos.
-- Solo agrega índices para mejorar rendimiento en búsquedas de webhooks y auditoría.

-- Índice para deduplicación de eventos externos (proveedor + ID de evento único)
-- Condicional: solo indexa filas donde provider_event_id IS NOT NULL
CREATE INDEX IF NOT EXISTS idx_pe_provider_event_id
    ON sales.payment_events (provider, provider_event_id)
    WHERE provider_event_id IS NOT NULL;

-- Índice para buscar eventos no procesados por proveedor (reintentos y monitoreo)
CREATE INDEX IF NOT EXISTS idx_pe_provider_processed
    ON sales.payment_events (provider, processed)
    WHERE processed = false;

-- Nota: Los índices idx_payment_events_payment_id, idx_payment_events_order_id,
-- idx_payment_events_processed y idx_payment_events_created_at ya existen
-- en la entidad PaymentEvent.java vía DDL de Hibernate.
-- Esta migración los explicita en Flyway para tener control total en producción.
