package com.security.job;

import com.security.entity.Order;
import com.security.entity.PaymentProof;
import com.security.enums.OrderStatus;
import com.security.enums.PaymentProofStatus;
import com.security.repository.OrderRepository;
import com.security.repository.PaymentProofRepository;
import com.security.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Job programado para expirar y cancelar órdenes no pagadas.
 * Libera el stock reservado para permitir que otros clientes compren los productos.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderExpirationJob {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final PaymentProofRepository paymentProofRepository;

    @Value("${bank.transfer.payment-validity-hours:72}")
    private int paymentValidityHours;

    /**
     * Ejecuta automáticamente para limpiar órdenes no pagadas en el límite de tiempo.
     */
    @Scheduled(fixedRateString = "${bank.transfer.expiration-job.fixed-rate-ms:1800000}")
    public void cancelExpiredOrders() {
        log.info("[OrderExpirationJob] Iniciando expiración de órdenes pendientes...");

        // Umbrales
        LocalDateTime thresholdMP = LocalDateTime.now().minusMinutes(60);
        LocalDateTime thresholdBank = LocalDateTime.now().minusHours(48);

        // Órdenes en estado PENDING
        List<Order> pendingOrders = orderRepository.findByStatus(OrderStatus.PENDING);

        if (pendingOrders.isEmpty()) {
            log.info("[OrderExpirationJob] No hay órdenes PENDING para analizar.");
            return;
        }

        int cancelledCount = 0;

        for (Order order : pendingOrders) {
            try {
                com.security.enums.PaymentMethod method = order.getPaymentMethod();
                
                if (method == com.security.enums.PaymentMethod.MERCADO_PAGO) {
                    // Si es Mercado Pago y pasó de 60 mins (independiente de bank.transfer)
                    if (order.getCreatedAt().isBefore(thresholdMP)) {
                        log.info("[OrderExpirationJob] Cancelando orden MP expirada: {}", order.getId());
                        orderService.cancelOrder(order.getId(), "Orden expirada por falta de pago (Mercado Pago > 60m)", "SYSTEM", null);
                        cancelledCount++;
                    }
                } else if (method == com.security.enums.PaymentMethod.BANK_TRANSFER) {
                    // Si es Transferencia y pasó el tiempo válido
                    LocalDateTime thresholdBankTransfer = LocalDateTime.now().minusHours(paymentValidityHours);
                    
                    if (order.getCreatedAt().isBefore(thresholdBankTransfer) && 
                        order.getPaymentStatus() == com.security.enums.PaymentStatus.PENDING &&
                        order.getShippingStatus() == com.security.enums.ShippingStatus.PENDING) {
                        
                        // Verificar si tiene un comprobante
                        Optional<PaymentProof> proofOpt = paymentProofRepository.findTopByOrderIdOrderByCreatedAtDesc(order.getId());
                        boolean canCancel = true;
                        
                        if (proofOpt.isPresent()) {
                            PaymentProof proof = proofOpt.get();
                            // Si el comprobante está bajo revisión o aprobado, no cancelar
                            if (proof.getStatus() == PaymentProofStatus.PENDING_REVIEW || proof.getStatus() == PaymentProofStatus.APPROVED) {
                                canCancel = false;
                                log.debug("[OrderExpirationJob] Orden {} tiene comprobante en revisión/aprobado. No expira.", order.getId());
                            }
                        }

                        if (canCancel) {
                            log.info("[OrderExpirationJob] Cancelando orden BANK_TRANSFER expirada: {}", order.getId());
                            orderService.cancelOrder(order.getId(), "Orden expirada por falta de pago dentro del plazo establecido.", "SYSTEM_EXPIRATION", null);
                            cancelledCount++;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[OrderExpirationJob] Error cancelando orden {}: {}", order.getId(), e.getMessage());
            }
        }

        log.info("[OrderExpirationJob] Proceso finalizado. {} órdenes canceladas por expiración.", cancelledCount);
    }
}
