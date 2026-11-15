package com.security.controller.admin;

import com.security.dto.response.PaymentReconciliationResponse;
import com.security.security.UserPrincipal;
import com.security.service.PaymentReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/payments/reconciliation")
@RequiredArgsConstructor
public class PaymentReconciliationController {

    private final PaymentReconciliationService paymentReconciliationService;

    /**
     * Endpoint administrativo para reconciliar pagos activos en órdenes canceladas.
     * Solo SUPER_ADMIN con el permiso específico PAYMENT_MANAGE puede ejecutar esto.
     */
    @PostMapping("/cancelled-orders")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('PAYMENT_MANAGE')")
    public ResponseEntity<PaymentReconciliationResponse> reconcileCancelledOrders(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @AuthenticationPrincipal UserPrincipal actor) {
            
        Long actorId = actor != null ? actor.getId() : null;
        if (actorId == null) {
            org.slf4j.LoggerFactory.getLogger(PaymentReconciliationController.class)
                    .warn("Advertencia: No se pudo identificar el actorUserId del token en reconciliación");
        }
        
        PaymentReconciliationResponse response = paymentReconciliationService.reconcileActivePaymentsForCancelledOrders(dryRun, actorId);
        
        return ResponseEntity.ok(response);
    }
}
