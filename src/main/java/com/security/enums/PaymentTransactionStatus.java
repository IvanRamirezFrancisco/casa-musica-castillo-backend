package com.security.enums;

/**
 * Estado transaccional de un intento de pago (sales.payments).
 *
 * Separado del enum PaymentStatus (que pertenece al resumen de órdenes)
 * para evitar colisiones y tener un ciclo de vida propio de cada intento de pago.
 *
 * Transiciones válidas en Fase 7A (BANK_TRANSFER):
 *   PENDING → PAID         (aprobación de comprobante)
 *   PENDING → CANCELLED    (cancelación manual)
 *   PENDING → EXPIRED      (tiempo expirado — Fase 7E)
 *   PENDING → FAILED       (error técnico)
 *
 * Futuras transiciones (Fases 7B/7C — Mercado Pago):
 *   CREATED → PENDING → AUTHORIZED → PAID
 *   CREATED → PENDING → REJECTED / FAILED
 */
public enum PaymentTransactionStatus {
    CREATED("Creado"),
    PENDING("Pendiente"),
    PROCESSING("En proceso"),
    AUTHORIZED("Autorizado"),
    PAID("Pagado"),
    REJECTED("Rechazado"),
    CANCELLED("Cancelado"),
    EXPIRED("Expirado"),
    FAILED("Fallido"),
    REFUNDED("Reembolsado");

    private final String displayName;

    PaymentTransactionStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
