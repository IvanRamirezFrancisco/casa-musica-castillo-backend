package com.security.controller;

import com.security.dto.CancelOrderRequest;
import com.security.dto.CheckoutRequest;
import com.security.dto.OrderDTO;
import com.security.dto.PaymentInstructionsResponse;
import com.security.dto.PricePreviewRequest;
import com.security.dto.PricePreviewResponse;
import com.security.dto.ShippingCalculationResult;
import com.security.security.UserPrincipal;
import com.security.service.OrderService;
import com.security.service.BankTransferSettingsService;
import com.security.service.PaymentProofService;
import com.security.service.DeliveryMethodService;
import com.security.service.ShoppingCartService;
import com.security.entity.ShoppingCart;
import com.security.service.OrderPickupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.format.annotation.DateTimeFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Controller para gestión de órdenes — Cliente.
 *
 * Endpoints:
 * - POST /api/checkout — Crear orden desde el carrito
 * - GET /api/orders/my — Listar órdenes del cliente
 * - GET /api/orders/my/{id} — Detalle de orden del cliente
 * - PATCH /api/orders/my/{id}/cancel — Cancelar orden por cliente
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final PaymentProofService paymentProofService;
    private final BankTransferSettingsService bankTransferSettingsService;
    private final com.security.service.OrderTimelineEventService timelineEventService;
    private final DeliveryMethodService deliveryMethodService;
    private final ShoppingCartService shoppingCartService;
    private final OrderPickupService orderPickupService;

    @PostMapping("/checkout/price-preview")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> pricePreview(
            @Valid @RequestBody PricePreviewRequest request,
            Authentication authentication) {
        try {
            Long userId = extractUserId(authentication);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            ShoppingCart cart = shoppingCartService.getOrCreateCartForUser(userId);
            ShippingCalculationResult deliveryResult = deliveryMethodService.calculateDelivery(
                    request.getDeliveryOption(), request.getState(), request.getCity(), request.getPostalCode()
            );

            PricePreviewResponse response = new PricePreviewResponse();
            response.setSubtotal(cart.getSubtotal());
            response.setTax(cart.getTax());
            
            BigDecimal shippingCost = deliveryResult.getShippingCost() != null ? deliveryResult.getShippingCost() : BigDecimal.ZERO;
            response.setShipping(shippingCost);
            response.setTotal(cart.getTotal().add(shippingCost));
            
            response.setDeliveryMethod(deliveryResult.getDeliveryMethod());
            response.setDeliveryLabel(deliveryResult.getDeliveryLabel());
            response.setRequiresAddress(deliveryResult.isRequiresAddress());
            response.setRequiresQuote(deliveryResult.isRequiresQuote());
            response.setCanProceedToPayment(deliveryResult.isCanProceedToPayment());
            response.setMessage(deliveryResult.getMessage());
            response.setContactWhatsapp(deliveryResult.getContactWhatsapp());
            response.setContactPhone(deliveryResult.getContactPhone());
            response.setQuoteMessage(deliveryResult.getQuoteMessage());
            response.setBusinessHours(deliveryResult.getBusinessHours());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error en price-preview: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/checkout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> checkout(
            @Valid @RequestBody CheckoutRequest request,
            Authentication authentication) {
        try {
            Long userId = extractUserId(authentication);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            log.info("POST /api/checkout — usuario {}", userId);
            OrderDTO order = orderService.createOrderFromCart(userId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(order);
        } catch (RuntimeException e) {
            log.warn("Error en checkout: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error interno en checkout: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno al procesar el pago y pedido"));
        }
    }

    @GetMapping("/orders/my")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<OrderDTO>> getMyOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("GET /api/orders/my — usuario {}", userId);
        int safeSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        Page<OrderDTO> orders = orderService.getOrdersByUserId(userId, pageRequest);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/orders/my/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getMyOrderById(
            @PathVariable Long id,
            Authentication authentication) {
        try {
            Long userId = extractUserId(authentication);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            log.info("GET /api/orders/my/{} — usuario {}", id, userId);
            OrderDTO order = orderService.getOrderById(id);
            
            if (!order.getUserId().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "No tienes permiso para ver esta orden"));
            }

            return ResponseEntity.ok(order);
        } catch (RuntimeException e) {
            log.warn("Orden no encontrada: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/orders/my/{id}/pickup-code")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getPickupCode(
            @PathVariable Long id,
            Authentication authentication) {
        try {
            Long userId = extractUserId(authentication);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            OrderDTO orderDto = orderService.getOrderById(id);
            if (!orderDto.getUserId().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "No tienes permiso para ver esta orden"));
            }

            com.security.entity.Order orderEntity = orderService.getOrderEntityById(id);

            boolean canShowCode = true;
            String message = "Código disponible para recolección.";

            if (!"PICKUP_STORE".equals(orderEntity.getDeliveryType())) {
                canShowCode = false;
                message = "Esta orden no es para recoger en tienda.";
            } else if (orderEntity.getPaymentStatus() != com.security.enums.PaymentStatus.PAID) {
                canShowCode = false;
                message = "El pedido debe estar pagado para ver el código.";
            } else if (orderEntity.getStatus() == com.security.enums.OrderStatus.CANCELLED || orderEntity.getStatus() == com.security.enums.OrderStatus.COMPLETED) {
                canShowCode = false;
                message = "El código de recolección ya no está disponible.";
            } else if (orderEntity.getPickupStatus() != com.security.enums.PickupStatus.READY_FOR_PICKUP) {
                canShowCode = false;
                if (orderEntity.getPickupStatus() == com.security.enums.PickupStatus.PICKED_UP || 
                    orderEntity.getPickupStatus() == com.security.enums.PickupStatus.CANCELLED) {
                    message = "El código de recolección ya no está disponible.";
                } else {
                    message = "El código estará disponible cuando tu pedido esté listo para recoger.";
                }
            } else if (orderEntity.getPickupCodeEncrypted() == null) {
                canShowCode = false;
                message = "El código de recolección aún no está disponible.";
            }

            if (!canShowCode) {
                return ResponseEntity.ok(Map.of(
                        "pickupCode", (Object) null,
                        "pickupCodeAvailable", false,
                        "message", message
                ));
            }

            String decryptedCode = orderPickupService.decryptPickupCode(orderEntity.getPickupCodeEncrypted());

            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store, max-age=0")
                    .body(Map.of(
                            "pickupCode", decryptedCode,
                            "pickupCodeAvailable", true,
                            "message", message,
                            "pickupStatus", orderEntity.getPickupStatus().name(),
                            "last4", orderEntity.getPickupCodeLast4() != null ? orderEntity.getPickupCodeLast4() : ""
                    ));

        } catch (Exception e) {
            log.error("Error al obtener código de recolección: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno del servidor."));
        }
    }

    @GetMapping("/orders/my/{id}/payment-instructions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getPaymentInstructions(
            @PathVariable Long id,
            Authentication authentication) {
        try {
            Long userId = extractUserId(authentication);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            PaymentInstructionsResponse response = bankTransferSettingsService.getPaymentInstructionsForOrder(userId, id);
            return ResponseEntity.ok(response);
        } catch (com.security.exception.ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Error al obtener instrucciones de pago: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/orders/my/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> cancelMyOrder(
            @PathVariable Long id,
            @RequestBody(required = false) CancelOrderRequest request,
            Authentication authentication) {
        try {
            Long userId = extractUserId(authentication);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            String reason = (request != null && request.getReason() != null) ? request.getReason() : "Cancelado por el cliente";
            
            log.info("PATCH /api/orders/my/{}/cancel — usuario {}", id, userId);
            OrderDTO order = orderService.cancelOrder(id, reason, "CUSTOMER", userId);
            
            return ResponseEntity.ok(order);
        } catch (RuntimeException e) {
            log.warn("Error al cancelar orden {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/orders/my/{orderId}/payment-proof")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> uploadPaymentProof(
            @PathVariable Long orderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "referenceNumber", required = false) String referenceNumber,
            @RequestParam(value = "bankName", required = false) String bankName,
            @RequestParam(value = "amountDeclared", required = false) BigDecimal amountDeclared,
            @RequestParam(value = "transferDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transferDate,
            @RequestParam(value = "notes", required = false) String notes,
            Authentication authentication) {
        try {
            Long userId = extractUserId(authentication);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            log.info("POST /api/orders/my/{}/payment-proof — usuario {}", orderId, userId);
            com.security.dto.response.PaymentProofResponse response = paymentProofService.uploadPaymentProof(
                    userId, orderId, file, referenceNumber, bankName, amountDeclared, transferDate, notes);
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Error de validación al subir comprobante para orden {}: {}", orderId, e.getMessage());
            String msg = e.getMessage();
            if (msg != null && msg.contains(":")) {
                String[] parts = msg.split(":", 2);
                return ResponseEntity.badRequest().body(Map.of("error", parts[0].trim(), "message", parts[1].trim()));
            }
            return ResponseEntity.badRequest().body(Map.of("error", "VALIDATION_ERROR", "message", msg));
        } catch (RuntimeException e) {
            log.warn("Error de ejecución al subir comprobante para orden {}: {}", orderId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "UPLOAD_FAILED", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Error interno subiendo comprobante: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error interno al procesar el archivo"));
        }
    }

    @GetMapping("/orders/my/{orderId}/payment-proof")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getMyPaymentProof(
            @PathVariable Long orderId,
            Authentication authentication) {
        try {
            Long userId = extractUserId(authentication);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            log.info("GET /api/orders/my/{}/payment-proof — usuario {}", orderId, userId);
            com.security.dto.response.PaymentProofResponse response = paymentProofService.getMyPaymentProof(userId, orderId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            log.warn("Comprobante no encontrado para orden {}: {}", orderId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/orders/my/{orderId}/payment-proof/file")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getMyPaymentProofFile(
            @PathVariable Long orderId,
            Authentication authentication) {
        try {
            Long userId = extractUserId(authentication);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            log.info("GET /api/orders/my/{}/payment-proof/file — usuario {}", orderId, userId);
            com.security.dto.response.PaymentProofFileResponse response = paymentProofService.getMyPaymentProofFile(orderId, userId);

            String contentType = response.contentType();
            String ext = ".pdf";
            if (contentType != null) {
                if (contentType.contains("jpeg") || contentType.contains("jpg")) ext = ".jpg";
                else if (contentType.contains("png")) ext = ".png";
                else if (contentType.contains("webp")) ext = ".webp";
            }
            
            String orderNum = response.orderNumber() != null ? response.orderNumber() : "UNKNOWN";
            if (orderNum.startsWith("ORD-")) {
                orderNum = orderNum.substring(4);
            }
            String filename = "comprobante-ORD-" + orderNum + ext;
            org.springframework.http.ContentDisposition contentDisposition = org.springframework.http.ContentDisposition.builder("inline")
                    .filename(filename)
                    .build();

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(response.contentType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Content-Disposition, Content-Type")
                    .body(response.resource());
        } catch (RuntimeException e) {
            log.warn("No se pudo descargar comprobante de orden {}: {}", orderId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("error", e.getMessage()));
        }
    }

    private Long extractUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal.getId();
        }
        return null;
    }
    @GetMapping("/orders/my/{id}/timeline")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<java.util.List<com.security.dto.response.OrderTimelineEventDTO>> getMyOrderTimeline(
            @PathVariable Long id,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Validación explícita de propiedad (Prevención IDOR)
        OrderDTO order = orderService.getOrderById(id);
        if (!order.getUserId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build(); // O FORBIDDEN
        }

        return ResponseEntity.ok(timelineEventService.getCustomerTimeline(id));
    }

}
