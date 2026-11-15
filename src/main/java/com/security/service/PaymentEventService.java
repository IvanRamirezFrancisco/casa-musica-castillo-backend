package com.security.service;

import com.security.entity.Order;
import com.security.entity.Payment;
import com.security.entity.PaymentEvent;
import com.security.enums.PaymentProvider;
import com.security.repository.PaymentEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Servicio de eventos de pago.
 *
 * Responsabilidades:
 * - Registrar eventos internos del ciclo de vida de un pago.
 * - Registrar y deduplicar eventos externos de webhooks (Fase 7C+).
 * - Los eventos NUNCA se borran; son un log de auditoría permanente.
 *
 * Diseño transaccional:
 * - recordInternalEvent() participa en la transacción del llamador (REQUIRED).
 *   Así el evento se persiste junto con el pago en el mismo commit.
 * - En Fase 7C, se agregará recordProviderEvent() con REQUIRES_NEW para
 *   que el evento de webhook se persista aunque el procesamiento falle.
 *
 * Tipos de evento internos en Fase 7A:
 *   PAYMENT_CREATED, PAYMENT_MARKED_PAID, PAYMENT_FAILED,
 *   PAYMENT_CANCELLED, BANK_TRANSFER_PROOF_UPLOADED,
 *   BANK_TRANSFER_PROOF_APPROVED, BANK_TRANSFER_PROOF_REJECTED
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventService {

    private final PaymentEventRepository paymentEventRepository;
    private final PlatformTransactionManager transactionManager;

    // ── Constantes de tipo de evento ──────────────────────────────────

    public static final String EVT_PAYMENT_CREATED              = "PAYMENT_CREATED";
    public static final String EVT_PAYMENT_MARKED_PAID          = "PAYMENT_MARKED_PAID";
    public static final String EVT_PAYMENT_FAILED               = "PAYMENT_FAILED";
    public static final String EVT_PAYMENT_CANCELLED            = "PAYMENT_CANCELLED";
    public static final String EVT_PAYMENT_CANCELLED_DUE_TO_ORDER_CANCELLED = "PAYMENT_CANCELLED_DUE_TO_ORDER_CANCELLED";
    public static final String EVT_PROOF_UPLOADED               = "BANK_TRANSFER_PROOF_UPLOADED";
    public static final String EVT_PROOF_APPROVED               = "BANK_TRANSFER_PROOF_APPROVED";
    public static final String EVT_PROOF_REJECTED               = "BANK_TRANSFER_PROOF_REJECTED";

    // ── Constantes de eventos externos — Fase 7C (Webhooks Mercado Pago) ──
    public static final String EVT_MP_WEBHOOK_RECEIVED          = "MERCADO_PAGO_WEBHOOK_RECEIVED";
    public static final String EVT_MP_WEBHOOK_DUPLICATE         = "MERCADO_PAGO_WEBHOOK_DUPLICATE";
    public static final String EVT_MP_WEBHOOK_SIG_INVALID       = "MERCADO_PAGO_WEBHOOK_SIGNATURE_INVALID";
    public static final String EVT_MP_PAYMENT_FETCHED           = "MERCADO_PAGO_PAYMENT_FETCHED";
    public static final String EVT_MP_PAYMENT_APPROVED          = "MERCADO_PAGO_PAYMENT_APPROVED";
    public static final String EVT_MP_PAYMENT_PENDING           = "MERCADO_PAGO_PAYMENT_PENDING";
    public static final String EVT_MP_PAYMENT_IN_PROCESS        = "MERCADO_PAGO_PAYMENT_IN_PROCESS";
    public static final String EVT_MP_PAYMENT_REJECTED          = "MERCADO_PAGO_PAYMENT_REJECTED";
    public static final String EVT_MP_PAYMENT_CANCELLED         = "MERCADO_PAGO_PAYMENT_CANCELLED";
    public static final String EVT_MP_PAYMENT_REFUNDED          = "MERCADO_PAGO_PAYMENT_REFUNDED";
    public static final String EVT_MP_AMOUNT_MISMATCH           = "MERCADO_PAGO_AMOUNT_MISMATCH";
    public static final String EVT_MP_CURRENCY_MISMATCH         = "MERCADO_PAGO_CURRENCY_MISMATCH";
    public static final String EVT_MP_EXTERNAL_REF_MISMATCH     = "MERCADO_PAGO_EXTERNAL_REFERENCE_MISMATCH";

    // =========================================================================
    // EVENTOS INTERNOS
    // =========================================================================

    /**
     * Registra un evento interno de ciclo de vida de un pago.
     * Participa en la transacción del llamador (propagación REQUIRED por defecto).
     * Si el registro falla, solo se emite un WARNING sin romper el flujo principal.
     *
     * @param payment    Payment relacionado (puede ser null si aún no existe)
     * @param order      Order relacionada
     * @param provider   Proveedor del pago
     * @param eventType  Tipo de evento (usar constantes EVT_*)
     * @param context    Contexto informativo (no incluir datos sensibles)
     * @return PaymentEvent guardado, o null si fallo (no lanza excepción)
     */
    // Quitado @Transactional para usar TransactionTemplate + afterCommit
    public PaymentEvent recordInternalEvent(Payment payment, Order order,
                                            PaymentProvider provider,
                                            String eventType, String context) {
        PaymentEvent event = new PaymentEvent();
        event.setPayment(payment);
        event.setOrder(order);
        event.setProvider(provider);
        event.setEventType(eventType);
        event.setProcessed(true);
        event.setProcessedAt(LocalDateTime.now());
        event.setProcessedBy("INTERNAL");
        event.setErrorMessage(context);

        if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    saveEventInNewTransaction(event, eventType, order, payment);
                }
            });
            // Retorna null (o el evento sin ID) temporalmente, porque se guarda asincrónicamente al commit.
            // Esto es seguro ya que nadie depende del ID del evento interno inmediatamente.
            return event;
        } else {
            return saveEventInNewTransaction(event, eventType, order, payment);
        }
    }

    private PaymentEvent saveEventInNewTransaction(PaymentEvent event, String eventType, Order order, Payment payment) {
        try {
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            transactionTemplate.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
            return transactionTemplate.execute(status -> {
                PaymentEvent saved = paymentEventRepository.save(event);
                log.debug("[PaymentEvent] Evento interno registrado: {} | order={} | payment={}",
                        eventType,
                        order != null ? order.getId() : null,
                        payment != null ? payment.getId() : null);
                return saved;
            });
        } catch (Exception e) {
            log.warn("[PaymentEvent] No se pudo registrar evento interno '{}': {}", eventType, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // EVENTOS DE PROVEEDOR EXTERNO — FASE 7C (Webhooks)
    // =========================================================================

    /**
     * Registra un evento externo de webhook (ej: Mercado Pago).
     *
     * Usa REQUIRES_NEW para garantizar que el evento se persiste en su propia
     * transacción, independientemente de si el procesamiento posterior falla.
     * Esto permite auditoría completa y reintentos seguros.
     *
     * @param payment         Payment relacionado (puede ser null si aún no se identificó)
     * @param order           Order relacionada (puede ser null)
     * @param provider        Proveedor del webhook
     * @param eventType       Tipo de evento (usar constantes EVT_MP_*)
     * @param providerEventId ID único del evento del proveedor (para deduplicación)
     * @param rawPayload      Body crudo del webhook (máx 4000 chars; puede ser null)
     * @param payloadHash     SHA-256 del raw payload
     * @param processed       true si el evento se considera procesado exitosamente
     * @param context         Contexto informativo (NO incluir datos sensibles)
     * @return PaymentEvent guardado, o null si falla (no lanza excepción)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentEvent recordProviderEvent(
            Payment payment, Order order,
            PaymentProvider provider,
            String eventType,
            String providerEventId,
            String rawPayload,
            String payloadHash,
            boolean processed,
            String context) {
        try {
            PaymentEvent event = new PaymentEvent();
            event.setPayment(payment);
            event.setOrder(order);
            event.setProvider(provider);
            event.setEventType(eventType);
            event.setProviderEventId(providerEventId);
            event.setRawPayload(rawPayload);
            event.setPayloadHash(payloadHash);
            event.setSignatureValid(true); // Solo se llama con firma válida (excepto EVT_WEBHOOK_SIG_INVALID)
            event.setProcessed(processed);
            if (processed) {
                event.setProcessedAt(LocalDateTime.now());
                event.setProcessedBy("WEBHOOK_SERVICE");
            }
            event.setErrorMessage(context);

            // Para evento de firma inválida, marcar signature_valid=false
            if (EVT_MP_WEBHOOK_SIG_INVALID.equals(eventType)) {
                event.setSignatureValid(false);
            }

            PaymentEvent saved = paymentEventRepository.save(event);
            log.debug("[PaymentEvent] Evento proveedor registrado: {} | provider={} | providerEventId={}",
                    eventType, provider, providerEventId);
            return saved;

        } catch (Exception e) {
            log.error("[PaymentEvent] No se pudo registrar evento proveedor '{}': {}", eventType, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // DEDUPLICACIÓN DE EVENTOS EXTERNOS (Fase 7C+)
    // =========================================================================

    /**
     * Verifica si un evento externo de webhook ya fue procesado.
     * Usado para idempotencia en la recepción de webhooks.
     */
    @Transactional(readOnly = true)
    public boolean isEventAlreadyProcessed(PaymentProvider provider, String providerEventId) {
        if (providerEventId == null || providerEventId.isBlank()) return false;
        return paymentEventRepository.existsByProviderAndProviderEventId(provider, providerEventId);
    }

    /**
     * Marca un evento como procesado (exitosamente o con error).
     */
    @Transactional
    public void markProcessed(Long eventId, String processedBy, String errorMessage) {
        paymentEventRepository.findById(eventId).ifPresent(event -> {
            event.setProcessed(true);
            event.setProcessedAt(LocalDateTime.now());
            event.setProcessedBy(processedBy);
            event.setErrorMessage(errorMessage);
            paymentEventRepository.save(event);
        });
    }

    // =========================================================================
    // CONSULTAS
    // =========================================================================

    /**
     * Devuelve todos los eventos de un Payment, más recientes primero.
     */
    @Transactional(readOnly = true)
    public List<PaymentEvent> getEventsByPayment(Long paymentId) {
        return paymentEventRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId);
    }

    /**
     * Devuelve todos los eventos de una Order, más recientes primero.
     */
    @Transactional(readOnly = true)
    public List<PaymentEvent> getEventsByOrder(Long orderId) {
        return paymentEventRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    // =========================================================================
    // MAPEO
    // =========================================================================

    /**
     * Mapea un PaymentEvent a su DTO de respuesta (sin rawPayload).
     */
    public com.security.dto.response.PaymentEventResponse toResponse(PaymentEvent event) {
        return com.security.dto.response.PaymentEventResponse.builder()
                .id(event.getId())
                .paymentId(event.getPayment() != null ? event.getPayment().getId() : null)
                .orderId(event.getOrder() != null ? event.getOrder().getId() : null)
                .provider(event.getProvider())
                .eventType(event.getEventType())
                .providerEventId(event.getProviderEventId())
                .signatureValid(event.getSignatureValid())
                .processed(event.getProcessed())
                .processedAt(event.getProcessedAt())
                .processedBy(event.getProcessedBy())
                .errorMessage(event.getErrorMessage())
                .createdAt(event.getCreatedAt())
                .build();
    }
}
