package com.security.controller.admin;

import com.security.dto.OrderDTO;
import com.security.enums.OrderStatus;
import com.security.enums.PaymentStatus;
import com.security.enums.ShippingStatus;
import com.security.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import com.security.security.UserPrincipal;
import org.springframework.web.bind.annotation.*;

import com.security.dto.request.RejectPaymentProofRequest;
import com.security.dto.PickupVerificationRequest;
import com.security.service.OrderPickupService;
import com.security.repository.UserRepository;
import org.springframework.core.io.Resource;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Controller para gestión de órdenes — Panel de Administración.
 *
 * Seguridad:
 * - Requiere rol ADMIN o SUPER_ADMIN en cada endpoint
 * - Validación y sanitización de todos los parámetros de entrada
 * - Whitelist de campos de ordenamiento para prevenir inyección
 * - Hardcap en tamaño de página (máximo 100)
 * - Manejo centralizado de excepciones con respuestas controladas
 * - Logs de auditoría sin datos sensibles
 *
 * Endpoints:
 * - GET /api/admin/orders — Listar órdenes con filtros y paginación
 * - GET /api/admin/orders/{id} — Detalle completo de una orden
 * - GET /api/admin/orders/stats — Estadísticas globales
 * - GET /api/admin/orders/customer/{userId} — Órdenes de un cliente
 * - PATCH /api/admin/orders/{id}/status — Cambiar estado de orden
 * - PATCH /api/admin/orders/{id}/payment-status — Cambiar estado de pago
 * - PATCH /api/admin/orders/{id}/shipping-status — Cambiar estado de envío
 * - PATCH /api/admin/orders/{id}/cancel — Cancelar orden con motivo
 */
@RestController
@RequestMapping("/api/admin/orders")
@PreAuthorize("hasAuthority('ORDER_READ')")
@Slf4j
public class AdminOrderController {

    /** Campos permitidos para ordenamiento — previene inyección en JPQL/SQL */
    private static final java.util.Set<String> ALLOWED_SORT_FIELDS = java.util.Set.of(
            "id", "orderNumber", "createdAt", "total", "status", "paymentStatus", "shippingStatus");

    /** Número máximo de registros por página */
    private static final int MAX_PAGE_SIZE = 100;

    @Autowired
    private OrderService orderService;

    @Autowired
    private com.security.service.OrderTimelineEventService timelineEventService;
    
    @Autowired
    private com.security.service.OrderStateTransitionService stateTransitionService;
    
    @Autowired
    private com.security.service.PaymentProofService paymentProofService;

    @Autowired
    private OrderPickupService orderPickupService;

    @Autowired
    private UserRepository userRepository;

    // ==================== LISTAR Y BUSCAR ====================

    /**
     * GET /api/admin/orders
     *
     * Listar órdenes con búsqueda avanzada, filtros y paginación.
     * Todos los parámetros son opcionales y se validan antes de usarse.
     */
    @GetMapping
    public ResponseEntity<Page<OrderDTO>> getAllOrders(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) OrderStatus orderStatus,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(required = false) ShippingStatus shippingStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {

        try {
            log.info("GET /api/admin/orders — page={}, size={}, sortBy={}, sortDir={}", page, size, sortBy, sortDir);

            // Validar tamaño de página
            int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

            // Whitelist de campos de ordenamiento
            String safeSortBy = ALLOWED_SORT_FIELDS.contains(sortBy) ? sortBy : "createdAt";

            // Dirección segura
            Sort.Direction direction = "ASC".equalsIgnoreCase(sortDir)
                    ? Sort.Direction.ASC
                    : Sort.Direction.DESC;
            PageRequest pageRequest = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(direction, safeSortBy));

            Page<OrderDTO> orders = orderService.searchOrders(
                    search, orderStatus, paymentStatus, shippingStatus,
                    startDate, endDate, pageRequest);

            log.info("GET /api/admin/orders — retornando {} de {} resultados", orders.getNumberOfElements(),
                    orders.getTotalElements());

            return ResponseEntity.ok(orders);

        } catch (Exception e) {
            log.error("Error al listar órdenes: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * GET /api/admin/orders/{id}
     *
     * Obtener detalle completo de una orden por su ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<OrderDTO> getOrderById(@PathVariable Long id) {
        try {
            log.info("GET /api/admin/orders/{} — solicitando detalle", id);

            OrderDTO order = orderService.getOrderById(id);

            log.info("GET /api/admin/orders/{} — orden encontrada: {}", id, order.getOrderNumber());

            return ResponseEntity.ok(order);

        } catch (RuntimeException e) {
            log.warn("GET /api/admin/orders/{} — no encontrada: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("GET /api/admin/orders/{} — error interno: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * GET /api/admin/orders/customer/{userId}
     *
     * Obtener el historial de órdenes de un cliente específico.
     */
    @GetMapping("/customer/{userId}")
    public ResponseEntity<Page<OrderDTO>> getOrdersByCustomer(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        try {
            log.info("GET /api/admin/orders/customer/{} — page={}, size={}", userId, page, size);

            int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
            PageRequest pageRequest = PageRequest.of(Math.max(page, 0), safeSize,
                    Sort.by(Sort.Direction.DESC, "createdAt"));

            Page<OrderDTO> orders = orderService.getOrdersByUserId(userId, pageRequest);

            log.info("GET /api/admin/orders/customer/{} — {} órdenes encontradas", userId, orders.getTotalElements());

            return ResponseEntity.ok(orders);

        } catch (Exception e) {
            log.error("Error al obtener órdenes del cliente {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * GET /api/admin/orders/stats
     *
     * Obtener estadísticas globales de órdenes.
     */
    @GetMapping("/stats")
    public ResponseEntity<OrderService.OrderStatsDTO> getOrderStats() {
        try {
            log.info("GET /api/admin/orders/stats — calculando estadísticas");

            OrderService.OrderStatsDTO stats = orderService.getOrderStats();

            log.info("GET /api/admin/orders/stats — {} órdenes totales", stats.getTotalOrders());

            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("Error al obtener estadísticas de órdenes: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ==================== ACTUALIZAR ESTADOS ====================

    // FASE 0 - Seguridad - 2026-05-15
    /**
     * PATCH /api/admin/orders/{id}/status
     *
     * Cambiar el estado de la orden.
     * Body: { "status": "CONFIRMED" }
     * Requiere ORDER_UPDATE (no hereda ORDER_READ de clase).
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public ResponseEntity<?> updateOrderStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        try {
            String statusStr = body.get("status");

            if (statusStr == null || statusStr.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El campo 'status' es requerido"));
            }

            OrderStatus newStatus = OrderStatus.valueOf(statusStr.trim().toUpperCase());

            log.info("PATCH /api/admin/orders/{}/status — nuevo estado: {}", id, newStatus);

            OrderDTO updatedOrder = orderService.updateOrderStatus(id, newStatus);

            log.info("PATCH /api/admin/orders/{}/status — actualizado correctamente", id);

            return ResponseEntity.ok(updatedOrder);

        } catch (IllegalArgumentException e) {
            log.warn("Estado de orden inválido para orden {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_ORDER_STATUS", "message", "Valor de estado inválido. Use: PENDING, CONFIRMED, PROCESSING, COMPLETED, CANCELLED"));
        } catch (IllegalStateException e) {
            log.warn("Transición de estado inválida para orden {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_ORDER_STATUS_TRANSITION", "message", e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Error de negocio al actualizar estado de orden {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Error interno al actualizar estado de orden {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "INTERNAL_SERVER_ERROR", "message", "Error interno del servidor"));
        }
    }

    // FASE 0 - Seguridad - 2026-05-15
    /**
     * PATCH /api/admin/orders/{id}/payment-status
     *
     * Cambiar el estado de pago de la orden.
     * Body: { "paymentStatus": "PAID" }
     * Requiere ORDER_UPDATE (no hereda ORDER_READ de clase).
     */
    @PatchMapping("/{id}/payment-status")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')") // Solo un súper admin podría forzar esto en emergencias extremas
    public ResponseEntity<?> updatePaymentStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        return ResponseEntity.badRequest()
                .body(Map.of(
                        "error", "INVALID_PAYMENT_STATUS_TRANSITION",
                        "message", "El estado de pago no se modifica manualmente. Usa aprobar comprobante, rechazar comprobante o reconsultar Mercado Pago."
                ));
    }

    // FASE 0 - Seguridad - 2026-05-15
    /**
     * PATCH /api/admin/orders/{id}/shipping-status
     *
     * Cambiar el estado de envío de la orden.
     * Body: { "shippingStatus": "SHIPPED", "trackingNumber": "TRK123456" }
     * Requiere ORDER_UPDATE (no hereda ORDER_READ de clase).
     */
    @PatchMapping("/{id}/shipping-status")
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public ResponseEntity<?> updateShippingStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        try {
            String statusStr = body.get("shippingStatus");
            String trackingNumber = body.get("trackingNumber");

            if (statusStr == null || statusStr.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El campo 'shippingStatus' es requerido"));
            }

            ShippingStatus newStatus = ShippingStatus.valueOf(statusStr.trim().toUpperCase());

            log.info("PATCH /api/admin/orders/{}/shipping-status — nuevo estado: {}, tracking: {}",
                    id, newStatus, trackingNumber != null ? "[provided]" : "[none]");

            OrderDTO updatedOrder = orderService.updateShippingStatus(id, newStatus, trackingNumber);

            log.info("PATCH /api/admin/orders/{}/shipping-status — actualizado correctamente", id);

            return ResponseEntity.ok(updatedOrder);

        } catch (IllegalArgumentException e) {
            log.warn("Estado de envío inválido para orden {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_SHIPPING_STATUS", "message", "Valor de estado de envío inválido. Use: PENDING, PREPARING, SHIPPED, IN_TRANSIT, DELIVERED, RETURNED"));
        } catch (IllegalStateException e) {
            log.warn("Transición de envío inválida para orden {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_SHIPPING_STATUS_TRANSITION", "message", e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Error de negocio al actualizar estado de envío de orden {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Error interno al actualizar estado de envío de orden {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "INTERNAL_SERVER_ERROR", "message", "Error interno del servidor"));
        }
    }

    // FASE 0 - Seguridad - 2026-05-15
    /**
     * PATCH /api/admin/orders/{id}/cancel
     *
     * Cancelar una orden con un motivo obligatorio.
     * Body: { "reason": "Solicitud del cliente" }
     * Requiere ORDER_UPDATE (no hereda ORDER_READ de clase).
     */
    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public ResponseEntity<?> cancelOrder(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication authentication) {

        try {
            String reason = body.get("reason");

            if (reason == null || reason.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El motivo de cancelación es obligatorio"));
            }

            if (reason.trim().length() > 500) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El motivo no puede exceder 500 caracteres"));
            }
            
            Long adminId = null;
            if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
                adminId = principal.getId();
            }

            log.info("PATCH /api/admin/orders/{}/cancel — motivo proporcionado, adminId={}", id, adminId);

            OrderDTO updatedOrder = orderService.cancelOrder(id, reason, "ADMIN", adminId);

            log.info("PATCH /api/admin/orders/{}/cancel — orden cancelada correctamente", id);

            return ResponseEntity.ok(updatedOrder);

        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Error al cancelar orden {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Bad Request", "message", e.getMessage()));
        }
    }

    /**
     * GET /api/admin/orders/export/csv
     *
     * Exportar órdenes a CSV para descarga directa.
     */
    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportToCsv() {
        try {
            log.info("GET /api/admin/orders/export/csv — generando exportación");

            PageRequest allPages = PageRequest.of(0, MAX_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<OrderDTO> page = orderService.searchOrders(null, null, null, null, null, null, allPages);
            java.util.List<OrderDTO> orders = page.getContent();

            StringBuilder csv = new StringBuilder();
            csv.append(
                    "ID,N\u00famero Orden,Cliente,Email,Fecha,Total,Estado Orden,Estado Pago,Estado Env\u00edo,Tracking\n");

            for (OrderDTO o : orders) {
                csv.append(o.getId()).append(",");
                csv.append(escapeCsv(o.getOrderNumber())).append(",");
                csv.append(escapeCsv(o.getCustomerName())).append(",");
                csv.append(escapeCsv(o.getCustomerEmail())).append(",");
                csv.append(o.getOrderDate() != null ? o.getOrderDate().toString() : "").append(",");
                csv.append(o.getTotal()).append(",");
                csv.append(o.getStatus()).append(",");
                csv.append(o.getPaymentStatus()).append(",");
                csv.append(o.getShippingStatus()).append(",");
                csv.append(escapeCsv(o.getTrackingNumber() != null ? o.getTrackingNumber() : "")).append("\n");
            }

            byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String filename = "ordenes_" + java.time.LocalDate.now() + ".csv";

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=UTF-8"));
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(bytes.length);

            log.info("GET /api/admin/orders/export/csv — {} órdenes exportadas", orders.size());

            return ResponseEntity.ok().headers(headers).body(bytes);

        } catch (Exception e) {
            log.error("Error al exportar órdenes a CSV: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Escapa un campo para formato CSV. */
    private String escapeCsv(String value) {
        if (value == null)
            return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ==================== COMPROBANTES DE PAGO ====================

    @GetMapping("/{id}/payment-proof")
    public ResponseEntity<?> getPaymentProof(@PathVariable Long id) {
        try {
            com.security.dto.response.PaymentProofResponse response = paymentProofService.getAdminPaymentProof(id);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            log.warn("Comprobante no encontrado para orden {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/payment-proof/file")
    public ResponseEntity<?> getPaymentProofFile(@PathVariable Long id) {
        try {
            com.security.dto.response.PaymentProofFileResponse response = paymentProofService.getAdminPaymentProofFile(id);

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
            log.warn("No se pudo descargar comprobante de orden {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/payment-proof/approve")
    @PreAuthorize("hasAuthority('ORDER_UPDATE') or hasAuthority('ORDER_MANAGE') or hasAuthority('PAYMENT_MANAGE') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> approvePaymentProof(
            @PathVariable Long id,
            Authentication authentication) {
        try {
            Long adminId = ((UserPrincipal) authentication.getPrincipal()).getId();
            log.info("Aprobando comprobante para orden {} por admin {}", id, adminId);
            com.security.dto.response.PaymentProofResponse response = paymentProofService.approvePaymentProof(adminId, id);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Error aprobando comprobante orden {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/payment-proof/reject")
    @PreAuthorize("hasAuthority('ORDER_UPDATE') or hasAuthority('ORDER_MANAGE') or hasAuthority('PAYMENT_MANAGE') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> rejectPaymentProof(
            @PathVariable Long id,
            @RequestBody RejectPaymentProofRequest request,
            Authentication authentication) {
        try {
            Long adminId = ((UserPrincipal) authentication.getPrincipal()).getId();
            log.info("Rechazando comprobante para orden {} por admin {}", id, adminId);
            com.security.dto.response.PaymentProofResponse response = paymentProofService.rejectPaymentProof(adminId, id, request.getReason());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Error rechazando comprobante orden {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/admin/orders/{id}/timeline
     * Obtener historial de eventos de una orden.
     */
    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAuthority('ORDER_READ')")
    public ResponseEntity<List<com.security.dto.response.OrderTimelineEventDTO>> getOrderTimeline(@PathVariable Long id) {
        log.info("GET /api/admin/orders/{}/timeline", id);
        return ResponseEntity.ok(timelineEventService.getAdminTimeline(id));
    }

    /**
     * GET /api/admin/orders/{id}/allowed-transitions
     * Obtener transiciones permitidas y ayuda visual para el estado de una orden.
     */
    @GetMapping("/{id}/allowed-transitions")
    @PreAuthorize("hasAuthority('ORDER_READ')")
    public ResponseEntity<com.security.dto.response.OrderTransitionsDTO> getAllowedTransitions(@PathVariable Long id) {
        log.info("GET /api/admin/orders/{}/allowed-transitions", id);
        
        com.security.entity.Order order = orderService.getOrderEntityById(id);
        
        com.security.dto.response.OrderTransitionsDTO dto = com.security.dto.response.OrderTransitionsDTO.builder()
                .currentOrderStatus(order.getStatus())
                .currentPaymentStatus(order.getPaymentStatus())
                .currentShippingStatus(order.getShippingStatus())
                .allowedOrderStatuses(stateTransitionService.getAllowedOrderStatuses(order))
                .allowedShippingStatuses(stateTransitionService.getAllowedShippingStatuses(order))
                .canUpdateOrderStatus(stateTransitionService.canUpdateOrderStatus(order))
                .canUpdateShippingStatus(stateTransitionService.canUpdateShippingStatus(order))
                .canUpdateTrackingNumber(stateTransitionService.canUpdateTrackingNumber(order))
                .canCancelOrder(stateTransitionService.canCancelOrder(order))
                .requiresCancelReason(stateTransitionService.requiresCancelReason())
                .orderStatusHelpMessage(stateTransitionService.getOrderStatusHelpMessage(order))
                .shippingStatusHelpMessage(stateTransitionService.getShippingStatusHelpMessage(order))
                .cancelOrderHelpMessage(stateTransitionService.getCancelOrderHelpMessage(order))
                .build();
                
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/{id}/pickup-ready")
    @PreAuthorize("hasAuthority('ORDER_UPDATE') or hasAuthority('ORDER_MANAGE') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> markReadyForPickup(
            @PathVariable Long id,
            Authentication authentication) {
        try {
            Long adminId = ((UserPrincipal) authentication.getPrincipal()).getId();
            com.security.entity.User adminUser = userRepository.findById(adminId).orElse(null);
            
            orderPickupService.markReadyForPickup(id, adminId, adminUser);
            
            return ResponseEntity.ok(Map.of("message", "Orden marcada como lista para recolección"));
        } catch (Exception e) {
            log.error("Error al marcar orden {} como lista para recolección: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/pickup-verify")
    @PreAuthorize("hasAuthority('ORDER_UPDATE') or hasAuthority('ORDER_MANAGE') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> verifyPickup(
            @PathVariable Long id,
            @Valid @RequestBody PickupVerificationRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest,
            Authentication authentication) {
        try {
            Long adminId = ((UserPrincipal) authentication.getPrincipal()).getId();
            com.security.entity.User adminUser = userRepository.findById(adminId).orElse(null);
            
            String ipAddress = httpRequest.getRemoteAddr();
            String userAgent = httpRequest.getHeader("User-Agent");
            
            orderPickupService.verifyAndReleasePickup(id, request.getCode(), request.getPickupAuthorizationId(), request.getNotes(), adminId, adminUser, ipAddress, userAgent);
            
            return ResponseEntity.ok(Map.of("message", "Código verificado correctamente. Pedido entregado."));
        } catch (Exception e) {
            log.error("Error al verificar código de recolección para orden {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
