package com.security.repository;

import com.security.entity.PaymentEvent;
import com.security.enums.PaymentProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repositorio de eventos de pago (sales.payment_events).
 * Los eventos nunca se borran: solo lectura y creación.
 */
@Repository
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    /**
     * Verificar si ya existe un evento externo con el mismo provider + providerEventId.
     * Usado para deduplicación de webhooks (Fase 7C+).
     */
    boolean existsByProviderAndProviderEventId(PaymentProvider provider, String providerEventId);

    /** Todos los eventos de un Payment, más recientes primero */
    List<PaymentEvent> findByPaymentIdOrderByCreatedAtDesc(Long paymentId);

    /** Todos los eventos de una Order, más recientes primero */
    List<PaymentEvent> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    /** Eventos no procesados de un proveedor (útil para reintentos en Fase 7C+) */
    List<PaymentEvent> findByProviderAndProcessedFalseOrderByCreatedAtAsc(PaymentProvider provider);

    /** Busca un evento externo específico por provider + providerEventId + eventType */
    java.util.Optional<PaymentEvent> findByProviderAndProviderEventIdAndEventType(
            PaymentProvider provider, String providerEventId, String eventType);
}
