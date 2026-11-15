package com.security.entity;

import com.security.enums.PaymentProvider;
import com.security.enums.PaymentTransactionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidad de intento de pago — fuente de verdad transaccional.
 *
 * Cada registro representa UN intento de pago para una orden.
 * La tabla sales.orders mantiene payment_status como resumen/caché.
 *
 * Reglas de negocio:
 * - Solo puede existir UN pago activo (CREATED/PENDING/PROCESSING/AUTHORIZED)
 *   por orden a la vez (garantizado por uq_payments_active_per_order en BD).
 * - El amount siempre se obtiene de order.total en el backend; nunca del cliente.
 * - currency siempre es 'MXN' en Fase 7A.
 * - Los campos providerPaymentId, providerPreferenceId y checkoutUrl
 *   son null en Fase 7A (BANK_TRANSFER) y se usan a partir de Fase 7B.
 */
@Entity
@Table(
    name = "payments",
    schema = "sales",
    indexes = {
        @Index(name = "idx_payments_order_id",          columnList = "order_id"),
        @Index(name = "idx_payments_status",             columnList = "status"),
        @Index(name = "idx_payments_provider",           columnList = "provider"),
        @Index(name = "idx_payments_external_reference", columnList = "external_reference"),
        @Index(name = "idx_payments_created_at",         columnList = "created_at")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Relaciones ──────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdByUser;

    // ── Proveedor y estado ──────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentProvider provider;

    /** Método de pago en texto libre controlado por servicio y validado en BD */
    @Column(length = 40)
    private String method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentTransactionStatus status;

    // ── Montos ──────────────────────────────────────────────────────────

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String currency;

    // ── Referencias del proveedor (Fase 7B+) ───────────────────────────

    @Column(name = "provider_payment_id", length = 150)
    private String providerPaymentId;

    @Column(name = "provider_preference_id", length = 150)
    private String providerPreferenceId;

    @Column(name = "provider_order_id", length = 150)
    private String providerOrderId;

    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    // ── Referencias internas ────────────────────────────────────────────

    @Column(name = "external_reference", nullable = false, length = 150, unique = true)
    private String externalReference;

    @Column(name = "idempotency_key", nullable = false, length = 150, unique = true)
    private String idempotencyKey;

    // ── Datos del pagador ───────────────────────────────────────────────

    @Column(name = "payer_email", length = 150)
    private String payerEmail;

    @Column(name = "payer_id", length = 150)
    private String payerId;

    // ── Payload y metadata ──────────────────────────────────────────────

    /**
     * JSON libre con metadatos adicionales (stored as JSONB en PostgreSQL).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private String metadataJson;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "provider_payload_hash", length = 100)
    private String providerPayloadHash;

    // ── Timestamps de ciclo de vida ─────────────────────────────────────

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    // ── Campos de diagnóstico y administración ──────────────────────────

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "raw_provider_status", length = 100)
    private String rawProviderStatus;

    @Column(name = "admin_notes", columnDefinition = "TEXT")
    private String adminNotes;

    // ── Control de concurrencia optimista ──────────────────────────────

    @Version
    @Column(nullable = false)
    private Long version;

    // ── Auditoría ────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
