package com.security.service;

import com.security.dto.response.PaymentReconciliationItemResponse;
import com.security.dto.response.PaymentReconciliationResponse;
import com.security.entity.Payment;
import com.security.enums.PaymentTransactionStatus;
import com.security.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationService {

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    // Estados activos a reconciliar
    private static final List<PaymentTransactionStatus> ACTIVE_STATUSES = List.of(
            PaymentTransactionStatus.CREATED,
            PaymentTransactionStatus.PENDING,
            PaymentTransactionStatus.PROCESSING,
            PaymentTransactionStatus.AUTHORIZED
    );

    /**
     * Reconcilia los pagos activos que quedaron "huérfanos" en órdenes CANCELLED.
     * Si dryRun = true, solo simula y devuelve lo que haría.
     * Si dryRun = false, ejecuta la cancelación llamando a PaymentService.
     */
    @Transactional
    public PaymentReconciliationResponse reconcileActivePaymentsForCancelledOrders(boolean dryRun, Long actorUserId) {
        log.info("[PaymentReconciliation] Iniciando reconciliación de órdenes CANCELLED. dryRun={}, actorUserId={}", dryRun, actorUserId);

        // Buscar pagos activos en órdenes canceladas
        List<Payment> activePayments = paymentRepository.findActivePaymentsForCancelledOrders(ACTIVE_STATUSES);

        long evaluatedOrdersCount = activePayments.stream()
                .map(p -> p.getOrder().getId())
                .distinct()
                .count();

        long activePaymentsFound = activePayments.size();
        long wouldCancelPaymentsCount = 0;
        long cancelledPaymentsCount = 0;

        List<PaymentReconciliationItemResponse> items = new ArrayList<>();

        for (Payment payment : activePayments) {
            PaymentTransactionStatus previousStatus = payment.getStatus();

            PaymentReconciliationItemResponse item = PaymentReconciliationItemResponse.builder()
                    .orderId(payment.getOrder().getId())
                    .orderNumber(payment.getOrder().getOrderNumber())
                    .paymentId(payment.getId())
                    .provider(payment.getProvider())
                    .previousStatus(previousStatus)
                    .amount(payment.getAmount())
                    .build();

            if (dryRun) {
                item.setNewStatus(PaymentTransactionStatus.CANCELLED);
                item.setAction("WOULD_CANCEL");
                item.setReason("Simulación de cancelación por reconciliación.");
                wouldCancelPaymentsCount++;
            } else {
                // La orden se asume ya cancelada
                paymentService.cancelActivePaymentsForOrder(
                        payment.getOrder().getId(),
                        "Pago cancelado automáticamente por servicio de reconciliación de órdenes.",
                        "RECONCILIATION_SERVICE",
                        actorUserId
                );
                
                // Confirmación
                item.setNewStatus(PaymentTransactionStatus.CANCELLED);
                item.setAction("CANCELLED");
                item.setReason("Cancelado con éxito.");
                cancelledPaymentsCount++;
            }
            items.add(item);
        }

        String message = dryRun 
            ? "Dry run completado. Ningún cambio aplicado." 
            : "Reconciliación completada. Cambios aplicados con éxito.";

        log.info("[PaymentReconciliation] Finalizado. dryRun={}, evaluadas={}, activos={}, wouldCancel={}, cancelados={}", 
                 dryRun, evaluatedOrdersCount, activePaymentsFound, wouldCancelPaymentsCount, cancelledPaymentsCount);

        return PaymentReconciliationResponse.builder()
                .dryRun(dryRun)
                .evaluatedOrders(evaluatedOrdersCount)
                .activePaymentsFound(activePaymentsFound)
                .wouldCancelPayments(wouldCancelPaymentsCount)
                .cancelledPayments(cancelledPaymentsCount)
                .message(message)
                .items(items)
                .build();
    }
}
