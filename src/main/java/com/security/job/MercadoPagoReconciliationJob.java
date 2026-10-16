package com.security.job;

import com.security.entity.Payment;
import com.security.enums.PaymentProvider;
import com.security.enums.PaymentTransactionStatus;
import com.security.repository.PaymentRepository;
import com.security.service.MercadoPagoWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Job programado para conciliar pagos de Mercado Pago pendientes.
 * Actúa como mecanismo de respaldo (fallback) en caso de que los webhooks fallen, 
 * se pierdan o tarden demasiado.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoReconciliationJob {

    private final PaymentRepository paymentRepository;
    private final MercadoPagoWebhookService mercadoPagoWebhookService;

    /**
     * Ejecuta la conciliación cada 15 minutos.
     * Busca pagos que lleven más de 10 minutos pendientes/procesando y consulta la API de MP.
     */
    @Scheduled(fixedRateString = "${app.mercadopago.reconciliation.interval:900000}")
    public void reconcilePendingPayments() {
        log.info("[ReconciliationJob] Iniciando conciliación de pagos de Mercado Pago...");
        
        // Pagos creados hace más de 10 minutos
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        
        List<PaymentTransactionStatus> pendingStatuses = List.of(
                PaymentTransactionStatus.PENDING,
                PaymentTransactionStatus.PROCESSING,
                PaymentTransactionStatus.AUTHORIZED
        );

        List<Payment> pendingPayments = paymentRepository.findByProviderAndStatusInAndCreatedAtBefore(
                PaymentProvider.MERCADO_PAGO, pendingStatuses, threshold
        );

        if (pendingPayments.isEmpty()) {
            log.info("[ReconciliationJob] No hay pagos pendientes para conciliar.");
            return;
        }

        log.info("[ReconciliationJob] Encontrados {} pagos pendientes para conciliar.", pendingPayments.size());

        for (Payment payment : pendingPayments) {
            try {
                // Previene saturar la API, saltamos si no tiene ID externo
                if (payment.getProviderPaymentId() == null || payment.getProviderPaymentId().isBlank()) {
                    continue; // Esperamos al webhook inicial
                }
                
                log.info("[ReconciliationJob] Reconsultando pago local ID: {}", payment.getId());
                mercadoPagoWebhookService.requeryPayment(payment.getId(), null);
                
            } catch (Exception e) {
                log.error("[ReconciliationJob] Error al reconsultar el pago {}: {}", payment.getId(), e.getMessage());
                // Continuar con los siguientes pagos
            }
        }
        
        log.info("[ReconciliationJob] Conciliación finalizada.");
    }
}
