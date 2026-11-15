package com.security.controller;

import com.security.dto.request.CreatePaymentRequest;
import com.security.dto.response.PaymentResponse;
import com.security.enums.PaymentProvider;
import com.security.security.UserPrincipal;
import com.security.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller para gestión de pagos desde el cliente.
 *
 * Seguridad:
 * - Requiere autenticación en todos los endpoints.
 * - El userId se extrae del JWT; nunca del request body.
 * - Solo puede ver y crear pagos de sus propias órdenes.
 * - No puede cambiar estados, amounts ni campos internos.
 *
 * Endpoints:
 * - GET  /api/orders/my/{orderId}/payments         → lista de pagos de la orden
 * - GET  /api/orders/my/{orderId}/payments/current → pago activo actual
 * - POST /api/orders/my/{orderId}/payments         → crear intento de pago
 */
@RestController
@RequestMapping("/api/orders/my/{orderId}/payments")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class OrderPaymentController {

    private final PaymentService paymentService;

    // ── GET: todos los pagos de la orden ─────────────────────────────────

    @GetMapping
    public ResponseEntity<?> getPaymentsForOrder(
            @PathVariable Long orderId,
            Authentication authentication) {
        try {
            Long userId = extractUserId(authentication);
            if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

            log.info("GET /api/orders/my/{}/payments — userId={}", orderId, userId);
            List<PaymentResponse> payments = paymentService.getPaymentsForUserOrder(userId, orderId);
            return ResponseEntity.ok(payments);

        } catch (com.security.exception.ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error obteniendo pagos de orden {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno"));
        }
    }

    // ── GET: pago activo actual de la orden ───────────────────────────────

    @GetMapping("/current")
    public ResponseEntity<?> getCurrentPayment(
            @PathVariable Long orderId,
            Authentication authentication) {
        try {
            Long userId = extractUserId(authentication);
            if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

            log.info("GET /api/orders/my/{}/payments/current — userId={}", orderId, userId);
            PaymentResponse payment = paymentService.getCurrentPaymentForUserOrder(userId, orderId);
            return ResponseEntity.ok(payment);

        } catch (com.security.exception.ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error obteniendo pago actual de orden {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno"));
        }
    }

    // ── POST: crear intento de pago ────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> createPayment(
            @PathVariable Long orderId,
            @Valid @RequestBody CreatePaymentRequest request,
            Authentication authentication) {
        try {
            Long userId = extractUserId(authentication);
            if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

            log.info("POST /api/orders/my/{}/payments — userId={}, provider={}",
                    orderId, userId, request.getProvider());

            // Convertir y validar el provider
            PaymentProvider provider;
            try {
                provider = PaymentProvider.valueOf(request.getProvider().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error",
                                "Proveedor inválido: '" + request.getProvider() +
                                "'. Use: BANK_TRANSFER"));
            }

            com.security.entity.Payment payment =
                    paymentService.createPaymentAttempt(userId, orderId, provider);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(paymentService.toClientResponse(payment));

        } catch (com.security.exception.ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // Orden cancelada, pagada, o mala configuración de MP → 409 Conflict / 400
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            // Provider no permitido → 400
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (com.security.exception.MercadoPagoPreferenceException e) {
            log.error("Error MercadoPagoPreferenceException en orden {}: status={}, error={}", orderId, e.getProviderStatusCode(), e.getProviderError());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "No pudimos iniciar Mercado Pago en este momento. Intenta nuevamente desde el detalle del pedido."));
        } catch (Exception e) {
            log.error("Error creando pago para orden {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno al procesar el pago"));
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────

    private Long extractUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) return userPrincipal.getId();
        return null;
    }
}
