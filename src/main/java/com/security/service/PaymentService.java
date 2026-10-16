package com.security.service;

import com.security.dto.response.PaymentAdminResponse;
import com.security.dto.response.PaymentResponse;
import com.security.entity.Order;
import com.security.entity.Payment;
import com.security.entity.User;
import com.security.enums.*;
import com.security.exception.ResourceNotFoundException;
import com.security.repository.OrderRepository;
import com.security.repository.PaymentRepository;
import com.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Servicio de pagos — única fuente de verdad para sales.payments.
 *
 * Reglas generales:
 * - Solo este servicio escribe en sales.payments.
 * - Solo este servicio sincroniza orders.payment_status cuando hay un pago real.
 * - No acepta amount, status, providerPaymentId ni currency del cliente.
 * - Todos los campos críticos se generan o calculan internamente.
 * - Idempotente: si existe un pago activo para la orden, lo devuelve.
 * - En Fase 7A, el único provider permitido para clientes es BANK_TRANSFER.
 *
 * Sincronización con orders:
 * - orders.payment_status es un resumen/caché; PaymentService lo actualiza.
 * - orders.transaction_id se usa como campo legacy para el frontend existente.
 * - No se baja de PAID a PENDING (regla unidireccional).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventService paymentEventService;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final MercadoPagoService mercadoPagoService;
    private final OrderTimelineEventService timelineEventService;
    private final OrderPickupService orderPickupService;

    // ── Estados que se consideran "activos" (bloquean creación de nuevos) ───
    private static final List<PaymentTransactionStatus> ACTIVE_STATUSES = List.of(
            PaymentTransactionStatus.CREATED,
            PaymentTransactionStatus.PENDING,
            PaymentTransactionStatus.PROCESSING,
            PaymentTransactionStatus.AUTHORIZED
    );

    // =========================================================================
    // CREAR INTENTO DE PAGO (endpoint cliente)
    // =========================================================================

    /**
     * Crea un nuevo intento de pago para una orden de un usuario.
     *
     * Reglas de seguridad:
     * - Valida que la orden existe y pertenece al usuario.
     * - Valida que la orden no está cancelada ni pagada.
     * - En Fase 7A, solo acepta BANK_TRANSFER como provider.
     * - Si ya existe un pago activo del mismo provider, lo devuelve (idempotencia).
     * - El amount se toma de order.total; currency siempre es MXN.
     *
     * @throws ResourceNotFoundException si la orden no existe
     * @throws IllegalStateException     si la orden está cancelada, pagada, o provider no permitido
     * @throws SecurityException         si el usuario no es dueño de la orden
     */
    @Transactional
    public Payment createPaymentAttempt(Long userId, Long orderId, PaymentProvider provider) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Orden no encontrada: " + orderId));

        // Validar ownership
        if (!order.getUser().getId().equals(userId)) {
            throw new SecurityException("No tienes permiso para crear un pago en esta orden");
        }

        // Validar estado de la orden
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("No se puede crear un pago para una orden cancelada");
        }
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            throw new IllegalStateException("Esta orden ya está pagada");
        }

        // Proveedores permitidos en Fase 7B
        if (provider != PaymentProvider.BANK_TRANSFER && provider != PaymentProvider.MERCADO_PAGO) {
            throw new IllegalArgumentException(
                    "Proveedor no soportado en esta fase. " +
                    "Opciones: BANK_TRANSFER, MERCADO_PAGO.");
        }

        // Idempotencia de negocio: devolver pago activo existente del mismo provider
        return paymentRepository
                .findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(orderId, ACTIVE_STATUSES)
                .filter(p -> p.getProvider() == provider)
                .orElseGet(() -> buildAndSavePayment(order, userId, provider,
                        PaymentTransactionStatus.PENDING, "Payment iniciado por cliente | provider=" + provider.name()));
    }

    // =========================================================================
    // ENSURE BANK TRANSFER (usado por PaymentProofService)
    // =========================================================================

    /**
     * Garantiza que exista un Payment BANK_TRANSFER activo para la orden.
     * Crea uno nuevo si no existe. Devuelve el existente si ya hay uno activo.
     * Utilizado internamente cuando el usuario sube un comprobante.
     *
     * @param order  Order ya cargada en la sesión actual (evita query adicional)
     * @param userId ID del usuario que sube el comprobante
     */
    @Transactional
    public Payment ensureBankTransferPaymentForOrder(Order order, Long userId) {
        return paymentRepository
                .findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(order.getId(), ACTIVE_STATUSES)
                .filter(p -> p.getProvider() == PaymentProvider.BANK_TRANSFER)
                .orElseGet(() -> buildAndSavePayment(order, userId, PaymentProvider.BANK_TRANSFER,
                        PaymentTransactionStatus.PENDING, "Payment BANK_TRANSFER creado al subir comprobante"));
    }

    // =========================================================================
    // MARCAR COMO PAGADO
    // =========================================================================

    /**
     * Marca un Payment específico como PAID.
     * Sincroniza orders.payment_status y orders.transaction_id (campo legacy).
     * Idempotente: si ya está PAID, lo devuelve sin cambios.
     *
     * @param paymentId   ID del payment a marcar
     * @param source      Descripción de la fuente (ej: "BANK_TRANSFER_PROOF_APPROVED")
     * @param actorUserId ID del actor que ejecuta la acción (admin, sistema)
     * @throws ResourceNotFoundException si el payment no existe
     * @throws IllegalStateException     si el payment está en estado no transicionable (CANCELLED/EXPIRED)
     */
    @Transactional
    public Payment markAsPaid(Long paymentId, String source, Long actorUserId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment no encontrado: " + paymentId));

        // Idempotencia: si ya está PAID, no hacer nada
        if (payment.getStatus() == PaymentTransactionStatus.PAID) {
            log.info("[PaymentService] Payment {} ya está PAID (idempotencia). source={}", paymentId, source);
            return payment;
        }

        // Transiciones inválidas
        if (payment.getStatus() == PaymentTransactionStatus.CANCELLED ||
            payment.getStatus() == PaymentTransactionStatus.EXPIRED) {
            throw new IllegalStateException(
                    "No se puede marcar como PAID un pago en estado " + payment.getStatus().name());
        }

        // Marcar el payment
        payment.setStatus(PaymentTransactionStatus.PAID);
        payment.setPaidAt(LocalDateTime.now());
        Payment saved = paymentRepository.save(payment);

        Order order = orderRepository.findById(payment.getOrder().getId()).orElse(null);
        if (order != null) {
            OrderStatus prevOrderStatus = order.getStatus();
            PaymentStatus prevPaymentStatus = order.getPaymentStatus();
            
            order.setPaymentStatus(PaymentStatus.PAID);
            
            if (order.getStatus() == OrderStatus.PENDING) {
                order.setStatus(OrderStatus.CONFIRMED);
                log.info("[PaymentService] Orden {} confirmada automáticamente al marcar pago PAID", order.getId());
            }
            
            // Generar código de recolección si aplica
            if ("PICKUP_STORE".equals(order.getDeliveryType())) {
                orderPickupService.generatePickupCode(order);
            }
            
            orderRepository.save(order);
            
            User actorUser = null;
            if (actorUserId != null) {
                actorUser = userRepository.findById(actorUserId).orElse(null);
            }
            
            timelineEventService.recordEvent(
                order,
                "PAYMENT_APPROVED",
                "Pago Aprobado",
                "El pago fue aprobado y la orden actualizada. Fuente: " + source,
                prevOrderStatus,
                order.getStatus(),
                prevPaymentStatus,
                order.getPaymentStatus(),
                order.getShippingStatus(),
                order.getShippingStatus(),
                actorUser,
                actorUserId != null ? "ADMIN" : "SYSTEM",
                "PAYMENT_SERVICE",
                "BOTH",
                null
            );
            
            // Actualizar transaction_id (campo legacy para frontend existente)
            String txRef = payment.getProviderPaymentId() != null
                    ? payment.getProviderPaymentId()
                    : payment.getExternalReference();
            order.setTransactionId(txRef);
            orderRepository.save(order);
        }

        // Evento de auditoría
        paymentEventService.recordInternalEvent(saved, order, saved.getProvider(),
                PaymentEventService.EVT_PAYMENT_MARKED_PAID,
                "source=" + source + " | actor=" + actorUserId);

        log.info("[PaymentService] Payment {} marcado PAID. order={}, source={}", paymentId,
                order != null ? order.getId() : null, source);
        return saved;
    }

    /**
     * Convenience: encuentra el Payment BANK_TRANSFER activo de una orden y lo marca PAID.
     * Usado en approvePaymentProof para garantizar que el pago exista antes de marcar.
     */
    @Transactional
    public Payment markBankTransferAsPaidForOrder(Order order, Long uploadedByUserId, Long adminId) {
        // Asegurar que exista el payment (crea uno si no existe)
        Payment payment = ensureBankTransferPaymentForOrder(order, uploadedByUserId);
        return markAsPaid(payment.getId(), "BANK_TRANSFER_PROOF_APPROVED", adminId);
    }

    // =========================================================================
    // MARCAR COMO FALLIDO
    // =========================================================================

    /**
     * Marca un Payment como FAILED con motivo.
     */
    @Transactional
    public Payment markAsFailed(Long paymentId, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment no encontrado: " + paymentId));

        if (payment.getStatus() == PaymentTransactionStatus.PAID) {
            throw new IllegalStateException("No se puede marcar como FAILED un pago ya PAID");
        }

        payment.setStatus(PaymentTransactionStatus.FAILED);
        payment.setFailureReason(reason);
        Payment saved = paymentRepository.save(payment);

        Order order = orderRepository.findById(payment.getOrder().getId()).orElse(null);
        paymentEventService.recordInternalEvent(saved, order, saved.getProvider(),
                PaymentEventService.EVT_PAYMENT_FAILED, "reason=" + reason);

        log.info("[PaymentService] Payment {} marcado FAILED. reason={}", paymentId, reason);
        return saved;
    }

    // =========================================================================
    // CANCELAR PAGO
    // =========================================================================

    /**
     * Cancela automáticamente todos los pagos activos de una orden.
     * Idempotente: si no hay pagos activos, no falla.
     * Usado cuando una orden pasa a estado CANCELLED.
     */
    @Transactional
    public void cancelActivePaymentsForOrder(Long orderId, String reason, String source, Long actorUserId) {
        List<Payment> activePayments = paymentRepository.findByOrderIdAndStatusIn(orderId, ACTIVE_STATUSES);
        if (activePayments.isEmpty()) {
            log.debug("[PaymentService] No hay pagos activos para cancelar en la orden {}", orderId);
            return;
        }

        Order order = orderRepository.findById(orderId).orElse(null);

        for (Payment payment : activePayments) {
            PaymentTransactionStatus oldStatus = payment.getStatus();
            payment.setStatus(PaymentTransactionStatus.CANCELLED);
            payment.setCancelledAt(LocalDateTime.now());
            payment.setFailureReason(reason);
            
            Payment saved = paymentRepository.save(payment);

            String context = "source=" + source + " | actor=" + actorUserId + " | oldStatus=" + oldStatus;
            paymentEventService.recordInternalEvent(saved, order, saved.getProvider(),
                    PaymentEventService.EVT_PAYMENT_CANCELLED_DUE_TO_ORDER_CANCELLED, context);

            log.info("[PaymentService] Payment {} cancelado auto. OrderId={}, oldStatus={}, source={}", 
                     saved.getId(), orderId, oldStatus, source);
        }
    }

    /**
     * Cancela un Payment activo.
     * No cancela si ya está PAID.
     */
    @Transactional
    public Payment cancelPayment(Long paymentId, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment no encontrado: " + paymentId));

        if (payment.getStatus() == PaymentTransactionStatus.PAID) {
            throw new IllegalStateException("No se puede cancelar un pago ya PAID");
        }
        if (payment.getStatus() == PaymentTransactionStatus.CANCELLED) {
            log.info("[PaymentService] Payment {} ya está CANCELLED (idempotencia)", paymentId);
            return payment;
        }

        payment.setStatus(PaymentTransactionStatus.CANCELLED);
        payment.setCancelledAt(LocalDateTime.now());
        payment.setFailureReason(reason);
        Payment saved = paymentRepository.save(payment);

        Order order = orderRepository.findById(payment.getOrder().getId()).orElse(null);
        paymentEventService.recordInternalEvent(saved, order, saved.getProvider(),
                PaymentEventService.EVT_PAYMENT_CANCELLED, "reason=" + reason);

        log.info("[PaymentService] Payment {} cancelado. reason={}", paymentId, reason);
        return saved;
    }

    // =========================================================================
    // CONCILIACIÓN MERCADO PAGO — FASE 7C
    // =========================================================================

    /**
     * Aplica el estado real de un pago de Mercado Pago al Payment local.
     *
     * Precondiciones:
     * - El Payment local ya fue identificado y cargado por el llamador.
     * - El mpPayment ya fue consultado directamente en la API de Mercado Pago (fuente de verdad).
     *
     * Reglas de seguridad:
     * - SOLO 'approved' puede llevar a PAID.
     * - No bajar un Payment PAID a ningún estado inferior.
     * - No modificar pagos REFUNDED ni EXPIRED.
     * - Validar external_reference, amount y currency antes de aprobar.
     * - No guardar datos sensibles de tarjeta.
     *
     * @param localPayment    Payment local a actualizar
     * @param mpPayment       Pago real consultado en la API de Mercado Pago
     * @param providerEventId ID del evento de webhook (para trazabilidad)
     */
    @Transactional
    public void applyMercadoPagoStatus(
            Payment localPayment,
            com.mercadopago.resources.payment.Payment mpPayment,
            String providerEventId) {

        Long localPaymentId = localPayment.getId();
        String mpStatus = mpPayment.getStatus() != null ? mpPayment.getStatus().trim().toLowerCase() : "unknown";
        String mpStatusDetail = mpPayment.getStatusDetail();
        String mpExternalRef = mpPayment.getExternalReference();
        BigDecimal mpAmount = mpPayment.getTransactionAmount();
        String mpCurrency = mpPayment.getCurrencyId();
        String mpPaymentIdStr = mpPayment.getId() != null ? String.valueOf(mpPayment.getId()) : null;

        Order order = orderRepository.findById(localPayment.getOrder().getId()).orElse(null);

        // ── Regla: no bajar de estados terminales ────────────────────────────
        PaymentTransactionStatus currentStatus = localPayment.getStatus();
        if (currentStatus == PaymentTransactionStatus.REFUNDED
                || currentStatus == PaymentTransactionStatus.EXPIRED) {
            log.info("[PaymentService] Payment {} en estado terminal {}. No se modifica por webhook.",
                    localPaymentId, currentStatus);
            return;
        }
        if (currentStatus == PaymentTransactionStatus.PAID && !"approved".equals(mpStatus)) {
            // Enriquecer campos faltantes sin bajar de PAID
            log.info("[PaymentService] Payment {} ya PAID. Enriqueciendo metadata sin cambiar estado. mpStatus={}",
                    localPaymentId, mpStatus);
            enrichPaidPayment(localPayment, mpPayment, mpPaymentIdStr);
            paymentEventService.recordInternalEvent(localPayment, order, PaymentProvider.MERCADO_PAGO,
                    PaymentEventService.EVT_MP_PAYMENT_APPROVED,
                    "Enriquecimiento idempotente. mpStatus=" + mpStatus + " providerEventId=" + providerEventId);
            return;
        }

        // ── Validaciones de conciliación (solo antes de PAID) ────────────────
        if ("approved".equals(mpStatus)) {
            // 1. Validar external_reference
            if (mpExternalRef == null || !mpExternalRef.equals(localPayment.getExternalReference())) {
                log.error("[PaymentService] EXTERNAL_REFERENCE mismatch. local={} mp={}",
                        localPayment.getExternalReference(), mpExternalRef);
                paymentEventService.recordInternalEvent(localPayment, order, PaymentProvider.MERCADO_PAGO,
                        PaymentEventService.EVT_MP_EXTERNAL_REF_MISMATCH,
                        "local=" + localPayment.getExternalReference() + " mp=" + mpExternalRef);
                return; // No marcar PAID
            }

            // 2. Validar amount con BigDecimal (escala 2)
            if (mpAmount == null || localPayment.getAmount() == null
                    || mpAmount.setScale(2, java.math.RoundingMode.HALF_UP)
                               .compareTo(localPayment.getAmount().setScale(2, java.math.RoundingMode.HALF_UP)) != 0) {
                log.error("[PaymentService] AMOUNT mismatch. local={} mp={}",
                        localPayment.getAmount(), mpAmount);
                paymentEventService.recordInternalEvent(localPayment, order, PaymentProvider.MERCADO_PAGO,
                        PaymentEventService.EVT_MP_AMOUNT_MISMATCH,
                        "local=" + localPayment.getAmount() + " mp=" + mpAmount);
                return; // No marcar PAID
            }

            // 3. Validar currency
            if (!"MXN".equalsIgnoreCase(mpCurrency) || !"MXN".equalsIgnoreCase(localPayment.getCurrency())) {
                log.error("[PaymentService] CURRENCY mismatch. local={} mp={}",
                        localPayment.getCurrency(), mpCurrency);
                paymentEventService.recordInternalEvent(localPayment, order, PaymentProvider.MERCADO_PAGO,
                        PaymentEventService.EVT_MP_CURRENCY_MISMATCH,
                        "local=" + localPayment.getCurrency() + " mp=" + mpCurrency);
                return; // No marcar PAID
            }
        }

        // ── Aplicar transición de estado ─────────────────────────────────────
        switch (mpStatus) {
            case "approved" -> {
                localPayment.setStatus(PaymentTransactionStatus.PAID);
                localPayment.setPaidAt(mpPayment.getDateApproved() != null
                        ? mpPayment.getDateApproved().toLocalDateTime()
                        : LocalDateTime.now());
                localPayment.setProviderPaymentId(mpPaymentIdStr);
                localPayment.setRawProviderStatus("approved");
                enrichPaidPayment(localPayment, mpPayment, mpPaymentIdStr);
                Payment saved = paymentRepository.save(localPayment);

                // Sincronizar orden
                if (order != null) {
                    order.setPaymentStatus(com.security.enums.PaymentStatus.PAID);
                    order.setTransactionId(mpPaymentIdStr);
                    orderRepository.save(order);
                }

                paymentEventService.recordInternalEvent(saved, order, PaymentProvider.MERCADO_PAGO,
                        PaymentEventService.EVT_MP_PAYMENT_APPROVED,
                        "mpPaymentId=" + mpPaymentIdStr + " providerEventId=" + providerEventId);
                paymentEventService.recordInternalEvent(saved, order, PaymentProvider.MERCADO_PAGO,
                        PaymentEventService.EVT_PAYMENT_MARKED_PAID,
                        "source=MERCADO_PAGO_WEBHOOK | actor=SYSTEM | providerEventId=" + providerEventId);

                log.info("[PaymentService] Payment {} marcado PAID por webhook MP. mpPaymentId={} orderId={}",
                        localPaymentId, mpPaymentIdStr, order != null ? order.getId() : null);
            }

            case "pending" -> {
                if (currentStatus != PaymentTransactionStatus.PAID) {
                    localPayment.setStatus(PaymentTransactionStatus.PENDING);
                    localPayment.setRawProviderStatus("pending");
                    paymentRepository.save(localPayment);
                    paymentEventService.recordInternalEvent(localPayment, order, PaymentProvider.MERCADO_PAGO,
                            PaymentEventService.EVT_MP_PAYMENT_PENDING,
                            "detail=" + mpStatusDetail);
                }
            }

            case "in_process", "in_mediation" -> {
                if (currentStatus != PaymentTransactionStatus.PAID) {
                    localPayment.setStatus(PaymentTransactionStatus.PROCESSING);
                    localPayment.setRawProviderStatus(mpStatus);
                    paymentRepository.save(localPayment);
                    paymentEventService.recordInternalEvent(localPayment, order, PaymentProvider.MERCADO_PAGO,
                            PaymentEventService.EVT_MP_PAYMENT_IN_PROCESS,
                            "detail=" + mpStatusDetail);
                }
            }

            case "authorized" -> {
                if (currentStatus != PaymentTransactionStatus.PAID) {
                    localPayment.setStatus(PaymentTransactionStatus.AUTHORIZED);
                    localPayment.setRawProviderStatus("authorized");
                    paymentRepository.save(localPayment);
                    paymentEventService.recordInternalEvent(localPayment, order, PaymentProvider.MERCADO_PAGO,
                            "MERCADO_PAGO_PAYMENT_AUTHORIZED",
                            "detail=" + mpStatusDetail);
                }
            }

            case "rejected" -> {
                if (currentStatus != PaymentTransactionStatus.PAID) {
                    localPayment.setStatus(PaymentTransactionStatus.REJECTED);
                    localPayment.setRawProviderStatus("rejected");
                    localPayment.setFailureReason(mpStatusDetail);
                    paymentRepository.save(localPayment);
                    paymentEventService.recordInternalEvent(localPayment, order, PaymentProvider.MERCADO_PAGO,
                            PaymentEventService.EVT_MP_PAYMENT_REJECTED,
                            "detail=" + mpStatusDetail);
                    log.info("[PaymentService] Payment {} REJECTED por MP. detail={}", localPaymentId, mpStatusDetail);
                }
            }

            case "cancelled" -> {
                if (currentStatus != PaymentTransactionStatus.PAID && currentStatus != PaymentTransactionStatus.CANCELLED) {
                    localPayment.setStatus(PaymentTransactionStatus.CANCELLED);
                    localPayment.setCancelledAt(LocalDateTime.now());
                    localPayment.setRawProviderStatus("cancelled");
                    localPayment.setFailureReason(mpStatusDetail);
                    paymentRepository.save(localPayment);
                    paymentEventService.recordInternalEvent(localPayment, order, PaymentProvider.MERCADO_PAGO,
                            PaymentEventService.EVT_MP_PAYMENT_CANCELLED,
                            "detail=" + mpStatusDetail);
                }
            }

            case "refunded" -> {
                if (currentStatus != PaymentTransactionStatus.REFUNDED) {
                    localPayment.setStatus(PaymentTransactionStatus.REFUNDED);
                    localPayment.setRawProviderStatus("refunded");
                    paymentRepository.save(localPayment);
                    paymentEventService.recordInternalEvent(localPayment, order, PaymentProvider.MERCADO_PAGO,
                            PaymentEventService.EVT_MP_PAYMENT_REFUNDED,
                            "Reembolso registrado. Gestión logística pendiente. detail=" + mpStatusDetail);
                    log.info("[PaymentService] Payment {} REFUNDED por MP. Reembolso de orden pendiente de gestión manual.", localPaymentId);
                }
            }

            case "charged_back" -> {
                // Chargeback: riesgo alto. Registrar evento y no marcar PAID.
                localPayment.setRawProviderStatus("charged_back");
                localPayment.setFailureReason("Chargeback recibido: " + mpStatusDetail);
                paymentRepository.save(localPayment);
                paymentEventService.recordInternalEvent(localPayment, order, PaymentProvider.MERCADO_PAGO,
                        "MERCADO_PAGO_CHARGED_BACK",
                        "ALERTA: Chargeback registrado. detail=" + mpStatusDetail + " Requiere revisión manual.");
                log.warn("[PaymentService] CHARGEBACK en Payment {}. detail={} Requiere revisión.", localPaymentId, mpStatusDetail);
            }

            default -> {
                log.warn("[PaymentService] Estado MP desconocido '{}' para Payment {}. Ignorado.", mpStatus, localPaymentId);
                paymentEventService.recordInternalEvent(localPayment, order, PaymentProvider.MERCADO_PAGO,
                        "MERCADO_PAGO_UNKNOWN_STATUS",
                        "mpStatus=" + mpStatus + " detail=" + mpStatusDetail);
            }
        }
    }

    /**
     * Enriquece un Payment ya PAID con datos adicionales del proveedor (idempotente).
     * Solo actualiza campos vacíos; no sobrescribe datos ya existentes.
     */
    private void enrichPaidPayment(Payment payment,
                                   com.mercadopago.resources.payment.Payment mpPayment,
                                   String mpPaymentIdStr) {
        boolean changed = false;
        if (payment.getProviderPaymentId() == null && mpPaymentIdStr != null) {
            payment.setProviderPaymentId(mpPaymentIdStr);
            changed = true;
        }
        if (payment.getRawProviderStatus() == null && mpPayment.getStatus() != null) {
            payment.setRawProviderStatus(mpPayment.getStatus());
            changed = true;
        }
        // payer_email: solo si existe el campo y no es sensible
        if (payment.getPayerEmail() == null && mpPayment.getPayer() != null
                && mpPayment.getPayer().getEmail() != null) {
            payment.setPayerEmail(mpPayment.getPayer().getEmail());
            changed = true;
        }
        if (changed) {
            paymentRepository.save(payment);
        }
    }

    // =========================================================================
    // SINCRONIZACIÓN DE orders.payment_status
    // =========================================================================

    /**
     * Recalcula y sincroniza orders.payment_status basándose en el estado real de payments.
     * Regla: nunca bajar de PAID a PENDING.
     */
    @Transactional
    public void syncOrderPaymentStatus(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) return;

        // Regla: si ya es PAID, no bajar
        if (order.getPaymentStatus() == PaymentStatus.PAID) return;

        boolean hasPaid = paymentRepository.existsByOrderIdAndStatusIn(
                orderId, List.of(PaymentTransactionStatus.PAID));

        if (hasPaid) {
            order.setPaymentStatus(PaymentStatus.PAID);
            orderRepository.save(order);
            log.info("[PaymentService] Orden {} sincronizada a PAID.", orderId);
        }
    }

    // =========================================================================
    // CONSULTAS CLIENTE
    // =========================================================================

    /**
     * Lista de pagos de una orden, validando que pertenezca al usuario.
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsForUserOrder(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Orden no encontrada: " + orderId));

        if (!order.getUser().getId().equals(userId)) {
            throw new SecurityException("No tienes permiso para ver los pagos de esta orden");
        }

        return paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
                .stream()
                .map(this::toClientResponse)
                .collect(Collectors.toList());
    }

    /**
     * Pago activo actual de una orden, validando ownership.
     */
    @Transactional(readOnly = true)
    public PaymentResponse getCurrentPaymentForUserOrder(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Orden no encontrada: " + orderId));

        if (!order.getUser().getId().equals(userId)) {
            throw new SecurityException("No tienes permiso para ver los pagos de esta orden");
        }

        return paymentRepository
                .findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(orderId, ACTIVE_STATUSES)
                .map(this::toClientResponse)
                .orElseThrow(() -> new ResourceNotFoundException("No hay un pago activo para esta orden"));
    }

    // =========================================================================
    // CONSULTAS ADMIN
    // =========================================================================

    @Transactional(readOnly = true)
    public List<PaymentAdminResponse> getAdminPaymentsForOrder(Long orderId) {
        return paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
                .stream()
                .map(this::toAdminResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PaymentAdminResponse getAdminPaymentById(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment no encontrado: " + paymentId));
        return toAdminResponse(payment);
    }

    @Transactional(readOnly = true)
    public Page<PaymentAdminResponse> getAdminPayments(
            PaymentTransactionStatus status,
            PaymentProvider provider,
            Pageable pageable) {

        Page<Payment> page;
        if (status != null && provider != null) {
            page = paymentRepository.findByStatusAndProvider(status, provider, pageable);
        } else if (status != null) {
            page = paymentRepository.findByStatus(status, pageable);
        } else if (provider != null) {
            page = paymentRepository.findByProvider(provider, pageable);
        } else {
            page = paymentRepository.findAll(pageable);
        }
        return page.map(this::toAdminResponse);
    }

    // =========================================================================
    // CONSTRUCCIÓN INTERNA
    // =========================================================================

    private Payment buildAndSavePayment(Order order, Long userId, PaymentProvider provider,
                                         PaymentTransactionStatus initialStatus, String context) {
        validateAmount(order);

        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setCreatedByUser(user);
        payment.setProvider(provider);
        payment.setMethod(provider == PaymentProvider.BANK_TRANSFER ? "BANK_TRANSFER" : null);
        payment.setStatus(initialStatus);
        payment.setAmount(order.getTotal());
        payment.setCurrency("MXN");
        String extRef = generateExternalReference(order.getId());
        payment.setExternalReference(extRef);
        payment.setIdempotencyKey(generateIdempotencyKey(order.getId(), provider));
        payment.setVersion(0L);

        // Integración externa (ej: Mercado Pago Preference)
        if (provider == PaymentProvider.MERCADO_PAGO) {
            com.mercadopago.resources.preference.Preference preference = 
                    mercadoPagoService.createPreferenceForOrder(order, extRef);
            payment.setProviderPreferenceId(preference.getId());
            // Init point de Mercado Pago
            payment.setCheckoutUrl(preference.getSandboxInitPoint() != null ? 
                    preference.getSandboxInitPoint() : preference.getInitPoint());
            payment.setMetadataJson("{\"orderId\":" + order.getId() + ",\"provider\":\"MERCADO_PAGO\"}");
        }

        Payment saved;
        try {
            saved = paymentRepository.save(payment);
        } catch (DataIntegrityViolationException ex) {
            // Race condition: otro hilo creó un pago activo entre el check y el insert.
            // Recuperar el existente (uq_payments_active_per_order lo garantiza).
            log.warn("[PaymentService] Conflicto al crear pago para orden {}. Recuperando existente.", order.getId());
            saved = paymentRepository
                    .findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(order.getId(), ACTIVE_STATUSES)
                    .filter(p -> p.getProvider() == provider)
                    .orElseThrow(() -> new IllegalStateException(
                            "Error de concurrencia creando el pago para orden " + order.getId()));
        }

        paymentEventService.recordInternalEvent(saved, order, provider,
                PaymentEventService.EVT_PAYMENT_CREATED, context);

        log.info("[PaymentService] Payment {} creado. provider={}, order={}, amount={}",
                saved.getId(), provider, order.getId(), saved.getAmount());
        return saved;
    }

    private void validateAmount(Order order) {
        BigDecimal total = order.getTotal();
        if (total == null || total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException(
                    "El total de la orden " + order.getId() + " es inválido: " + total);
        }
    }

    // =========================================================================
    // GENERADORES DE REFERENCIAS
    // =========================================================================

    private String generateExternalReference(Long orderId) {
        return "ORD-" + orderId + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String generateIdempotencyKey(Long orderId, PaymentProvider provider) {
        return "PAY-" + orderId + "-" + provider.name() + "-" + UUID.randomUUID().toString();
    }

    // =========================================================================
    // MAPEO A DTOs
    // =========================================================================

    public PaymentResponse toClientResponse(Payment p) {
        return PaymentResponse.builder()
                .id(p.getId())
                .orderId(p.getOrder() != null ? p.getOrder().getId() : null)
                .orderNumber(p.getOrder() != null ? p.getOrder().getOrderNumber() : null)
                .provider(p.getProvider())
                .method(p.getMethod())
                .status(p.getStatus())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .checkoutUrl(p.getCheckoutUrl())
                .externalReference(p.getExternalReference())
                .paidAt(p.getPaidAt())
                .expiresAt(p.getExpiresAt())
                .cancelledAt(p.getCancelledAt())
                .failureReason(p.getFailureReason())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    public PaymentAdminResponse toAdminResponse(Payment p) {
        Long userId = null;
        String userEmail = null;
        if (p.getCreatedByUser() != null) {
            userId = p.getCreatedByUser().getId();
            try { userEmail = p.getCreatedByUser().getEmail(); } catch (Exception ignored) {}
        }

        return PaymentAdminResponse.builder()
                .id(p.getId())
                .orderId(p.getOrder() != null ? p.getOrder().getId() : null)
                .orderNumber(p.getOrder() != null ? p.getOrder().getOrderNumber() : null)
                .userId(userId)
                .userEmail(userEmail)
                .provider(p.getProvider())
                .method(p.getMethod())
                .status(p.getStatus())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .providerPaymentId(p.getProviderPaymentId())
                .providerPreferenceId(p.getProviderPreferenceId())
                .providerOrderId(p.getProviderOrderId())
                .externalReference(p.getExternalReference())
                .idempotencyKey(p.getIdempotencyKey())
                .payerEmail(p.getPayerEmail())
                .rawProviderStatus(p.getRawProviderStatus())
                .checkoutUrl(p.getCheckoutUrl())
                .adminNotes(p.getAdminNotes())
                .paidAt(p.getPaidAt())
                .expiresAt(p.getExpiresAt())
                .cancelledAt(p.getCancelledAt())
                .failureReason(p.getFailureReason())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .version(p.getVersion())
                .build();
    }
}
