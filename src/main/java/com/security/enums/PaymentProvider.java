package com.security.enums;

/**
 * Proveedor de pago de un intento de pago (sales.payments).
 *
 * Separado del enum PaymentMethod (que pertenece al resumen de órdenes)
 * para evitar colisiones y permitir evolución independiente por fase.
 *
 * Fase 7A  → BANK_TRANSFER habilitado para clientes
 * Fase 7B  → MERCADO_PAGO
 * Fase 7D  → PAYPAL
 * Futuro   → STRIPE, OPENPAY, MANUAL
 */
public enum PaymentProvider {
    BANK_TRANSFER("Transferencia Bancaria"),
    MERCADO_PAGO("Mercado Pago"),
    PAYPAL("PayPal"),
    STRIPE("Stripe"),
    OPENPAY("Openpay"),
    MANUAL("Manual / Ajuste interno");

    private final String displayName;

    PaymentProvider(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
