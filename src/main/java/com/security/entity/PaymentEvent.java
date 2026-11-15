package com.security.entity;

import com.security.enums.PaymentProvider;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entidad de evento de pago — log inmutable de eventos internos y externos.
 *
 * Cada registro es un evento auditable relacionado con un intento de pago:
 * - Eventos internos: PAYMENT_CREATED, PAYMENT_MARKED_PAID, etc.
 * - Eventos externos (Fase 7C+): webhooks de Mercado Pago / PayPal.
 *
 * Reglas:
 * - NO se borran eventos. El historial es permanente.
 * - Para eventos externos, provider_event_id garantiza deduplicación.
 * - rawPayload solo se almacena para eventos externos (webhooks).
 *   Para eventos internos se usa el campo error_message como contexto.
 */
@Entity
@Table(
    name = "payment_events",
    schema = "sales",
    indexes = {
        @Index(name = "idx_payment_events_payment_id",   columnList = "payment_id"),
        @Index(name = "idx_payment_events_order_id",     columnList = "order_id"),
        @Index(name = "idx_payment_events_provider",     columnList = "provider"),
        @Index(name = "idx_payment_events_processed",    columnList = "processed"),
        @Index(name = "idx_payment_events_created_at",   columnList = "created_at")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Relaciones opcionales ───────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    // ── Identificación del evento ───────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentProvider provider;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** ID del evento externo del proveedor (para deduplicación de webhooks) */
    @Column(name = "provider_event_id", length = 150)
    private String providerEventId;

    // ── Payload (solo para eventos externos / webhooks) ─────────────────

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "payload_hash", length = 100)
    private String payloadHash;

    @Column(name = "signature_valid")
    private Boolean signatureValid;

    // ── Estado de procesamiento ─────────────────────────────────────────

    @Column(nullable = false)
    private Boolean processed = false;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "processed_by", length = 100)
    private String processedBy;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // ── Auditoría ────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
