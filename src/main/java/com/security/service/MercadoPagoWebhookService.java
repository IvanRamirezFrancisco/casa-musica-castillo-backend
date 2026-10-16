package com.security.service;

import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.security.config.MercadoPagoProperties;
import com.security.dto.webhook.MercadoPagoWebhookRequest;
import com.security.entity.Order;
import com.security.entity.PaymentEvent;
import com.security.enums.PaymentProvider;
import com.security.enums.PaymentTransactionStatus;
import com.security.repository.OrderRepository;
import com.security.repository.PaymentEventRepository;
import com.security.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Servicio de conciliación de pagos para webhooks de Mercado Pago.
 *
 * Responsabilidades:
 * - Validar firma HMAC del webhook antes de cualquier procesamiento.
 * - Deduplicar eventos para evitar doble procesamiento.
 * - Consultar el estado real del pago en la API de Mercado Pago (fuente de verdad).
 * - Conciliar amount, currency y external_reference antes de aprobar.
 * - Delegar a PaymentService para aplicar transiciones de estado.
 * - Registrar todos los eventos de auditoría.
 *
 * Reglas de seguridad:
 * - NUNCA confiar en el status enviado en el body del webhook.
 * - NUNCA marcar PAID sin validar contra la API real de Mercado Pago.
 * - NUNCA aceptar datos de amount/currency del frontend.
 * - Solo 'approved' puede llevar un pago a PAID.
 *
 * Manejo de timeouts:
 * - Si la API de Mercado Pago falla temporalmente, se responde 500 (via excepción)
 *   para que Mercado Pago reintente.
 * - Los eventos con processed=false permiten reprocesamiento en reintentos.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoWebhookService {

    // ── Constantes de eventos de auditoría ─────────────────────────────────
    public static final String EVT_WEBHOOK_RECEIVED          = "MERCADO_PAGO_WEBHOOK_RECEIVED";
    public static final String EVT_WEBHOOK_DUPLICATE         = "MERCADO_PAGO_WEBHOOK_DUPLICATE";
    public static final String EVT_WEBHOOK_SIG_INVALID       = "MERCADO_PAGO_WEBHOOK_SIGNATURE_INVALID";
    public static final String EVT_PAYMENT_FETCHED           = "MERCADO_PAGO_PAYMENT_FETCHED";
    public static final String EVT_PAYMENT_APPROVED          = "MERCADO_PAGO_PAYMENT_APPROVED";
    public static final String EVT_PAYMENT_PENDING           = "MERCADO_PAGO_PAYMENT_PENDING";
    public static final String EVT_PAYMENT_IN_PROCESS        = "MERCADO_PAGO_PAYMENT_IN_PROCESS";
    public static final String EVT_PAYMENT_REJECTED          = "MERCADO_PAGO_PAYMENT_REJECTED";
    public static final String EVT_PAYMENT_CANCELLED         = "MERCADO_PAGO_PAYMENT_CANCELLED";
    public static final String EVT_PAYMENT_REFUNDED          = "MERCADO_PAGO_PAYMENT_REFUNDED";
    public static final String EVT_AMOUNT_MISMATCH           = "MERCADO_PAGO_AMOUNT_MISMATCH";
    public static final String EVT_CURRENCY_MISMATCH         = "MERCADO_PAGO_CURRENCY_MISMATCH";
    public static final String EVT_EXTERNAL_REF_MISMATCH     = "MERCADO_PAGO_EXTERNAL_REFERENCE_MISMATCH";
    public static final String EVT_PAYMENT_NOT_FOUND_LOCAL   = "MERCADO_PAGO_PAYMENT_NOT_FOUND_LOCAL";
    public static final String EVT_CHARGED_BACK              = "MERCADO_PAGO_CHARGED_BACK";
    public static final String EVT_IN_MEDIATION              = "MERCADO_PAGO_IN_MEDIATION";

    private final MercadoPagoWebhookSignatureService signatureService;
    private final PaymentEventService paymentEventService;
    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OrderRepository orderRepository;
    private final MercadoPagoProperties properties;

    /**
     * Punto de entrada principal para procesar un webhook de Mercado Pago.
     *
     * Flujo:
     * 1. Validar firma HMAC.
     * 2. Extraer data.id (paymentId del proveedor).
     * 3. Determinar si el evento es de tipo "payment".
     * 4. Verificar idempotencia (processed=true → 200 sin reprocesar).
     * 5. Consultar API real de Mercado Pago.
     * 6. Conciliar y aplicar estado.
     *
     * @param request       DTO del body del webhook
     * @param xSignature    Header x-signature de Mercado Pago
     * @param xRequestId    Header x-request-id de Mercado Pago
     * @param dataIdQuery   Query param "data.id" (parte del manifest de firma)
     * @param typeQuery     Query param "type"
     * @param topicQuery    Query param "topic" (formato alternativo)
     * @param rawBody       Body crudo como String (para payload_hash)
     */
    public void processWebhook(
            MercadoPagoWebhookRequest request,
            String xSignature,
            String xRequestId,
            String dataIdQuery,
            String typeQuery,
            String topicQuery,
            String rawBody) {

        // ── Paso 1: Validar firma HMAC ───────────────────────────────────────
        boolean signatureValid = signatureService.isValidSignature(xSignature, xRequestId, dataIdQuery);
        String payloadHash = signatureService.sha256Hex(rawBody);

        if (!signatureValid) {
            log.warn("[WebhookService] Firma inválida recibida. requestId={}", xRequestId);
            // Registrar intento con firma inválida (REQUIRES_NEW para persistir aunque la tx falle)
            paymentEventService.recordProviderEvent(
                    null, null, PaymentProvider.MERCADO_PAGO,
                    EVT_WEBHOOK_SIG_INVALID,
                    deriveProviderEventId(request, dataIdQuery, xRequestId),
                    null, payloadHash, false,
                    "Firma HMAC inválida. requestId=" + safeLog(xRequestId)
            );
            throw new SecurityException("WEBHOOK_SIGNATURE_INVALID");
        }

        // ── Paso 2: Extraer data.id (paymentId en Mercado Pago) ────────────
        String dataId = resolveDataId(dataIdQuery, request);
        if (dataId == null || dataId.isBlank()) {
            log.warn("[WebhookService] data.id no encontrado en query param ni body. requestId={}", xRequestId);
            throw new IllegalArgumentException("WEBHOOK_NO_DATA_ID");
        }

        // ── Paso 3: Verificar si es evento de tipo 'payment' ────────────────
        boolean isPaymentEvent = isPaymentType(request, typeQuery, topicQuery);
        String providerEventId = deriveProviderEventId(request, dataId, xRequestId);

        if (!isPaymentEvent) {
            log.info("[WebhookService] Evento no es tipo 'payment'. type={} topic={}. Ignorado. providerEventId={}",
                    typeQuery, topicQuery, providerEventId);
            // Registrar pero no procesar
            paymentEventService.recordProviderEvent(
                    null, null, PaymentProvider.MERCADO_PAGO,
                    EVT_WEBHOOK_RECEIVED + "_NON_PAYMENT",
                    providerEventId,
                    rawBody != null && rawBody.length() <= 2000 ? rawBody : null,
                    payloadHash, true,
                    "Evento no-payment ignorado. type=" + typeQuery + " topic=" + topicQuery
            );
            return; // 200 OK
        }

        // ── Paso 4: Verificar idempotencia ───────────────────────────────────
        Optional<PaymentEvent> existingEvent = paymentEventRepository
                .findByProviderAndProviderEventIdAndEventType(
                        PaymentProvider.MERCADO_PAGO, providerEventId, EVT_WEBHOOK_RECEIVED);

        if (existingEvent.isPresent()) {
            PaymentEvent evt = existingEvent.get();
            if (Boolean.TRUE.equals(evt.getProcessed())) {
                // Duplicado ya procesado exitosamente → responder 200 sin reprocesar
                log.info("[WebhookService] Webhook duplicado ya procesado. providerEventId={}", providerEventId);
                paymentEventService.recordProviderEvent(
                        evt.getPayment(), evt.getOrder(), PaymentProvider.MERCADO_PAGO,
                        EVT_WEBHOOK_DUPLICATE,
                        providerEventId + "_dup_" + System.currentTimeMillis(),
                        null, payloadHash, true,
                        "Webhook duplicado ignorado. Evento original id=" + evt.getId()
                );
                return; // 200 OK
            } else {
                // processed=false → reintento después de error/timeout → permitir reprocesamiento
                log.info("[WebhookService] Webhook previamente fallido (processed=false). Reprocesando. providerEventId={}", providerEventId);
                // Reutilizar el evento existente para el procesamiento (se actualizará al final)
                processPaymentById(dataId, evt, rawBody, payloadHash, providerEventId, xRequestId);
                return;
            }
        }

        // ── Paso 5: Evento nuevo → registrar y procesar ─────────────────────
        PaymentEvent newEvent = paymentEventService.recordProviderEvent(
                null, null, PaymentProvider.MERCADO_PAGO,
                EVT_WEBHOOK_RECEIVED,
                providerEventId,
                rawBody != null && rawBody.length() <= 4000 ? rawBody : "[truncated]",
                payloadHash, false,
                "Webhook recibido. dataId=" + dataId + " requestId=" + safeLog(xRequestId)
        );

        processPaymentById(dataId, newEvent, rawBody, payloadHash, providerEventId, xRequestId);
    }

    /**
     * Consulta el pago real en Mercado Pago y aplica la conciliación.
     * Lanza excepción (para HTTP 500) si la API de MP falla temporalmente.
     */
    private void processPaymentById(
            String dataId, PaymentEvent webhookEvent,
            String rawBody, String payloadHash,
            String providerEventId, String xRequestId) {

        Long mpPaymentId;
        try {
            mpPaymentId = Long.parseLong(dataId);
        } catch (NumberFormatException e) {
            log.error("[WebhookService] data.id no es numérico: {}", dataId);
            markEventFailed(webhookEvent, "data.id no numérico: " + dataId);
            return; // No es error de MP; responder 200 con evento documentado
        }

        // ── Consultar Mercado Pago API (fuente de verdad) ────────────────────
        Payment mpPayment;
        try {
            PaymentClient client = new PaymentClient();
            mpPayment = client.get(mpPaymentId);
            log.info("[WebhookService] Pago obtenido de MP. mpPaymentId={}, status={}, externalRef={}",
                    mpPaymentId, mpPayment.getStatus(), mpPayment.getExternalReference());

            paymentEventService.recordProviderEvent(
                    null, null, PaymentProvider.MERCADO_PAGO,
                    EVT_PAYMENT_FETCHED,
                    providerEventId + "_fetched",
                    null, null, true,
                    "Pago consultado en API MP. status=" + mpPayment.getStatus()
                            + " externalRef=" + mpPayment.getExternalReference()
            );
        } catch (MPApiException e) {
            log.error("[WebhookService] Error API Mercado Pago al consultar pago {}. status={}", mpPaymentId, e.getApiResponse().getStatusCode());
            markEventFailed(webhookEvent, "Error API MP. status=" + e.getApiResponse().getStatusCode());
            throw new RuntimeException("MP_API_ERROR_" + e.getApiResponse().getStatusCode());
        } catch (MPException e) {
            log.error("[WebhookService] Error SDK Mercado Pago al consultar pago {}: {}", mpPaymentId, e.getMessage());
            markEventFailed(webhookEvent, "Error SDK MP: " + e.getMessage());
            throw new RuntimeException("MP_SDK_ERROR");
        }

        // ── Buscar Payment local ─────────────────────────────────────────────
        String externalRef = mpPayment.getExternalReference();
        com.security.entity.Payment localPayment = findLocalPayment(mpPayment, externalRef, mpPaymentId);

        if (localPayment == null) {
            log.warn("[WebhookService] No se encontró Payment local para externalRef={} mpPaymentId={}",
                    externalRef, mpPaymentId);
            paymentEventService.recordProviderEvent(
                    null, null, PaymentProvider.MERCADO_PAGO,
                    EVT_PAYMENT_NOT_FOUND_LOCAL,
                    providerEventId + "_notfound",
                    null, null, true,
                    "Payment local no encontrado. externalRef=" + externalRef + " mpPaymentId=" + mpPaymentId
            );
            markEventProcessed(webhookEvent, "Payment local no encontrado. externalRef=" + externalRef);
            return; // 200 OK — no reintentar infinitamente
        }

        // ── Aplicar conciliación y estado ─────────────────────────────────────
        try {
            paymentService.applyMercadoPagoStatus(localPayment, mpPayment, providerEventId);
            markEventProcessed(webhookEvent, "Conciliado. mpStatus=" + mpPayment.getStatus());
        } catch (Exception e) {
            log.error("[WebhookService] Error al aplicar estado MP al Payment {}. mpStatus={}: {}",
                    localPayment.getId(), mpPayment.getStatus(), e.getMessage());
            markEventFailed(webhookEvent, "Error aplicando estado: " + e.getMessage());
            throw e; // Re-lanzar para generar 500 y permitir reintento de MP
        }
    }

    /**
     * Reconsulta manual de un pago en Mercado Pago desde el panel de administración o un job.
     *
     * @param localPaymentId ID del Payment local (tabla sales.payments)
     * @param adminId        ID del admin que ejecuta (o null si es sistema)
     */
    @Transactional
    public void requeryPayment(Long localPaymentId, Long adminId) {
        com.security.entity.Payment localPayment = paymentRepository.findById(localPaymentId)
                .orElseThrow(() -> new RuntimeException("Payment local no encontrado: " + localPaymentId));

        if (localPayment.getProvider() != PaymentProvider.MERCADO_PAGO) {
            throw new IllegalArgumentException("El pago no es de Mercado Pago");
        }

        String providerPaymentId = localPayment.getProviderPaymentId();
        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            throw new IllegalArgumentException("El pago local no tiene providerPaymentId asociado. No se puede consultar a MP sin el ID externo.");
        }

        Long mpPaymentId;
        try {
            mpPaymentId = Long.parseLong(providerPaymentId);
        } catch (NumberFormatException e) {
            throw new RuntimeException("El providerPaymentId no es un número válido: " + providerPaymentId);
        }

        // Consultar API de Mercado Pago
        Payment mpPayment;
        try {
            PaymentClient client = new PaymentClient();
            mpPayment = client.get(mpPaymentId);
            log.info("[WebhookService] Pago reconsultado de MP. mpPaymentId={}, status={}",
                    mpPaymentId, mpPayment.getStatus());
        } catch (Exception e) {
            log.error("[WebhookService] Error consultando MP en requery para pago {}: {}", mpPaymentId, e.getMessage());
            throw new RuntimeException("Error comunicando con Mercado Pago para reconsulta: " + e.getMessage());
        }

        String eventContext = "Reconsulta " + (adminId != null ? "manual admin " + adminId : "automática sistema") + " | mpStatus=" + mpPayment.getStatus();
        
        // Aplicar estado (la validación de amounts/currency está dentro de este método)
        paymentService.applyMercadoPagoStatus(localPayment, mpPayment, "requery_" + System.currentTimeMillis());
        
        paymentEventService.recordInternalEvent(
                localPayment, 
                localPayment.getOrder(), 
                PaymentProvider.MERCADO_PAGO, 
                "REQUERY_COMPLETED", 
                eventContext
        );
    }

    /**
     * Busca el Payment local por external_reference (primario) o provider_payment_id (fallback).
     */
    private com.security.entity.Payment findLocalPayment(Payment mpPayment, String externalRef, Long mpPaymentId) {
        // Búsqueda primaria por external_reference
        if (externalRef != null && !externalRef.isBlank()) {
            Optional<com.security.entity.Payment> byRef = paymentRepository.findByExternalReference(externalRef);
            if (byRef.isPresent()) return byRef.get();
        }
        // Fallback por provider + provider_payment_id
        return paymentRepository
                .findByProviderAndProviderPaymentId(PaymentProvider.MERCADO_PAGO, String.valueOf(mpPaymentId))
                .orElse(null);
    }

    // ── Helpers de estado de evento ─────────────────────────────────────────

    @Transactional
    private void markEventProcessed(PaymentEvent event, String note) {
        if (event == null || event.getId() == null) return;
        paymentEventService.markProcessed(event.getId(), "WEBHOOK_SERVICE", note);
    }

    @Transactional
    private void markEventFailed(PaymentEvent event, String note) {
        if (event == null || event.getId() == null) return;
        paymentEventRepository.findById(event.getId()).ifPresent(e -> {
            e.setProcessed(false);
            e.setProcessedAt(LocalDateTime.now());
            e.setProcessedBy("WEBHOOK_SERVICE_ERROR");
            e.setErrorMessage(note != null && note.length() > 500 ? note.substring(0, 500) : note);
            paymentEventRepository.save(e);
        });
    }

    // ── Resolución de data.id ────────────────────────────────────────────────

    /**
     * Resuelve el data.id con prioridad: query param → body.data.id
     */
    private String resolveDataId(String dataIdQuery, MercadoPagoWebhookRequest request) {
        if (dataIdQuery != null && !dataIdQuery.isBlank()) return dataIdQuery;
        if (request != null && request.getDataId() != null) return request.getDataId();
        return null;
    }

    /**
     * Determina si el webhook corresponde a un evento de tipo "payment".
     */
    private boolean isPaymentType(MercadoPagoWebhookRequest request, String typeQuery, String topicQuery) {
        if ("payment".equalsIgnoreCase(typeQuery)) return true;
        if ("payment".equalsIgnoreCase(topicQuery)) return true;
        if (request != null && "payment".equalsIgnoreCase(request.getType())) return true;
        return false;
    }

    /**
     * Genera un provider_event_id único para deduplicación.
     * Prioridad: body.id → "payment:{dataId}:req:{requestId}"
     */
    private String deriveProviderEventId(MercadoPagoWebhookRequest request, String dataId, String requestId) {
        if (request != null && request.getId() != null) {
            return "mpwh:" + request.getId();
        }
        if (dataId != null && !dataId.isBlank()) {
            return "payment:" + dataId + (requestId != null ? ":req:" + requestId : "");
        }
        if (requestId != null) {
            return "req:" + requestId;
        }
        return "unknown:" + System.currentTimeMillis();
    }

    /** Oculta partes de un valor sensible para logging seguro */
    private String safeLog(String value) {
        if (value == null) return "null";
        if (value.length() <= 8) return "[redacted]";
        return value.substring(0, 4) + "...[redacted]";
    }
}
