package com.security.controller.admin;

import com.security.dto.response.PaymentAdminResponse;
import com.security.enums.PaymentProvider;
import com.security.enums.PaymentTransactionStatus;
import com.security.exception.ResourceNotFoundException;
import com.security.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.security.security.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Controller de administración para pagos.
 *
 * Seguridad:
 * - Requiere PAYMENT_READ, ORDER_READ, ADMIN o SUPER_ADMIN en endpoints de lectura.
 * - En Fase 7A no se permite modificar estados desde este controller.
 * - No se expone rawPayload ni metadataJson por defecto.
 * - No se implementa DELETE de payments ni payment_events.
 *
 * Endpoints:
 * - GET /api/admin/orders/{orderId}/payments         → pagos de una orden
 * - GET /api/admin/payments/{paymentId}              → detalle de un pago
 * - GET /api/admin/payments?status=&provider=&page=  → lista paginada con filtros
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class AdminPaymentController {

    private final PaymentService paymentService;
    private final com.security.service.MercadoPagoWebhookService mercadoPagoWebhookService;

    /** Tamaño máximo de página permitido */
    private static final int MAX_PAGE_SIZE = 100;

    /** Campos permitidos para ordenamiento */
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("id", "createdAt", "amount", "status", "provider");

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/admin/orders/{orderId}/payments
    // Listar todos los pagos de una orden específica
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/api/admin/orders/{orderId}/payments")
    @PreAuthorize("hasAuthority('PAYMENT_READ') or hasAuthority('ORDER_READ') or hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> getPaymentsByOrder(@PathVariable Long orderId) {
        try {
            log.info("GET /api/admin/orders/{}/payments", orderId);
            List<PaymentAdminResponse> payments = paymentService.getAdminPaymentsForOrder(orderId);
            return ResponseEntity.ok(payments);

        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error listando pagos de orden {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/admin/payments/{paymentId}
    // Detalle de un pago por ID
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/api/admin/payments/{paymentId}")
    @PreAuthorize("hasAuthority('PAYMENT_READ') or hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> getPaymentById(@PathVariable Long paymentId) {
        try {
            log.info("GET /api/admin/payments/{}", paymentId);
            PaymentAdminResponse response = paymentService.getAdminPaymentById(paymentId);
            return ResponseEntity.ok(response);

        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error obteniendo payment {}: {}", paymentId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/admin/payments?status=&provider=&orderId=&page=&size=&sortBy=
    // Lista paginada con filtros opcionales
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/api/admin/payments")
    @PreAuthorize("hasAuthority('PAYMENT_READ') or hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> getAllPayments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String provider,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {
        try {
            log.info("GET /api/admin/payments — status={}, provider={}, page={}, size={}",
                    status, provider, page, size);

            // Sanitizar parámetros
            int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
            String safeSortBy = ALLOWED_SORT_FIELDS.contains(sortBy) ? sortBy : "createdAt";
            Sort.Direction direction = "ASC".equalsIgnoreCase(sortDir)
                    ? Sort.Direction.ASC : Sort.Direction.DESC;
            PageRequest pageRequest = PageRequest.of(Math.max(page, 0), safeSize,
                    Sort.by(direction, safeSortBy));

            // Convertir filtros de enum con validación
            PaymentTransactionStatus statusEnum = null;
            if (status != null && !status.isBlank()) {
                try {
                    statusEnum = PaymentTransactionStatus.valueOf(status.trim().toUpperCase());
                } catch (IllegalArgumentException ex) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Estado de pago inválido: " + status));
                }
            }

            PaymentProvider providerEnum = null;
            if (provider != null && !provider.isBlank()) {
                try {
                    providerEnum = PaymentProvider.valueOf(provider.trim().toUpperCase());
                } catch (IllegalArgumentException ex) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Proveedor inválido: " + provider));
                }
            }

            Page<PaymentAdminResponse> result =
                    paymentService.getAdminPayments(statusEnum, providerEnum, pageRequest);

            log.info("GET /api/admin/payments — retornando {} de {} resultados",
                    result.getNumberOfElements(), result.getTotalElements());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error listando pagos (admin): {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno"));
        }
    }

    // ── POST /api/admin/payments/{paymentId}/requery ───────────────
    @PostMapping("/api/admin/payments/{paymentId}/requery")
    @PreAuthorize("hasAuthority('PAYMENT_MANAGE') or hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> requeryPayment(
            @PathVariable Long paymentId,
            Authentication authentication) {
        try {
            Long adminId = ((UserPrincipal) authentication.getPrincipal()).getId();
            log.info("POST /api/admin/payments/{}/requery — admin={}", paymentId, adminId);
            
            mercadoPagoWebhookService.requeryPayment(paymentId, adminId);
            
            return ResponseEntity.ok(Map.of(
                    "status", "success", 
                    "message", "Pago reconsultado exitosamente"
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("Payment local no encontrado")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", e.getMessage()));
            }
            log.error("Error reconsultando pago {}: {}", paymentId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno reconsultando pago: " + e.getMessage()));
        }
    }
}
