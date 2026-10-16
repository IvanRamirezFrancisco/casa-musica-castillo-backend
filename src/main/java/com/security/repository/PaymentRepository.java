package com.security.repository;

import com.security.entity.Payment;
import com.security.enums.PaymentProvider;
import com.security.enums.PaymentTransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio de intentos de pago (sales.payments).
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Todos los pagos de una orden, más recientes primero */
    List<Payment> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    /**
     * Primer pago activo de una orden entre los estados indicados.
     * Usado para idempotencia: si existe uno activo, devolver en lugar de crear.
     */
    Optional<Payment> findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
            Long orderId, Collection<PaymentTransactionStatus> statuses);

    /**
     * Verificar si existe algún pago en los estados indicados para una orden.
     * Usado en syncOrderPaymentStatus y validaciones de negocio.
     */
    boolean existsByOrderIdAndStatusIn(
            Long orderId, Collection<PaymentTransactionStatus> statuses);

    /** Buscar por external_reference (campo único generado por el backend) */
    Optional<Payment> findByExternalReference(String externalReference);

    /** Buscar por idempotency_key para deduplicación */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /** Buscar por proveedor + ID de pago del proveedor (Fase 7B+) */
    Optional<Payment> findByProviderAndProviderPaymentId(
            PaymentProvider provider, String providerPaymentId);

    /** Buscar por proveedor + ID de preferencia del proveedor (Fase 7B+) */
    Optional<Payment> findByProviderAndProviderPreferenceId(
            PaymentProvider provider, String providerPreferenceId);

    /** Pagos filtrados por estado (admin) */
    Page<Payment> findByStatus(PaymentTransactionStatus status, Pageable pageable);

    /** Pagos filtrados por proveedor (admin) */
    Page<Payment> findByProvider(PaymentProvider provider, Pageable pageable);

    /** Pagos filtrados por estado y proveedor (admin) */
    Page<Payment> findByStatusAndProvider(
            PaymentTransactionStatus status, PaymentProvider provider, Pageable pageable);

    /** Pagos de una orden para admin */
    List<Payment> findByOrderId(Long orderId);

    /** Buscar todos los pagos de una orden en los estados indicados */
    List<Payment> findByOrderIdAndStatusIn(Long orderId, Collection<PaymentTransactionStatus> statuses);

    /** Reconciliación: Buscar todos los pagos en ciertos estados donde la orden está CANCELLED */
    @org.springframework.data.jpa.repository.Query("SELECT p FROM Payment p JOIN p.order o WHERE o.status = com.security.enums.OrderStatus.CANCELLED AND p.status IN :statuses")
    List<Payment> findActivePaymentsForCancelledOrders(@org.springframework.data.repository.query.Param("statuses") Collection<PaymentTransactionStatus> statuses);

    /** Reconciliación: Buscar pagos pendientes por proveedor y fecha de creación */
    List<Payment> findByProviderAndStatusInAndCreatedAtBefore(
            PaymentProvider provider,
            Collection<PaymentTransactionStatus> statuses,
            java.time.LocalDateTime createdAt);
}
