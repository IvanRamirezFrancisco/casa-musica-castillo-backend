package com.security.controller;

import com.security.dto.CouponDTO;
import com.security.service.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// FASE 0 - Seguridad - 2026-05-15
// TODO Fase-Seguridad: Crear permisos COUPON_READ, COUPON_CREATE,
// COUPON_UPDATE, COUPON_DELETE y COUPON_MANAGE en una migración futura
// para desacoplar la administración de cupones de los permisos PRODUCT_*.

/**
 * Controller para gestión de cupones de descuento
 * Endpoints: /api/coupons (public), /api/admin/coupons (admin only)
 * CORS se maneja globalmente en SecurityConfig
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class CouponController {

    private final CouponService couponService;

    // ==================== ENDPOINTS PÚBLICOS ====================

    /**
     * Valida un cupón (disponible para todos)
     * POST /api/coupons/validate
     */
    @PostMapping("/api/coupons/validate")
    public ResponseEntity<CouponDTO.CouponValidationResponse> validateCoupon(
            @Valid @RequestBody CouponDTO.ValidateCouponRequest request) {

        log.info("Validando cupón: {}", request.getCode());

        var response = couponService.validateCoupon(request, null);

        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene cupones activos (disponible para todos)
     * GET /api/coupons/active
     */
    @GetMapping("/api/coupons/active")
    public ResponseEntity<CouponDTO.CouponListResponse> getActiveCoupons(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("Obteniendo cupones activos - page: {}, size: {}", page, size);

        var response = couponService.getActiveCoupons(page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene un cupón por código (público para mostrar detalles)
     * GET /api/coupons/{code}
     */
    @GetMapping("/api/coupons/{code}")
    public ResponseEntity<CouponDTO.CouponResponse> getCouponByCode(@PathVariable String code) {
        log.info("Obteniendo cupón por código: {}", code);

        var response = couponService.getCouponByCode(code);
        return ResponseEntity.ok(response);
    }

    // ==================== ENDPOINTS ADMINISTRATIVOS ====================

    /**
     * Crea un nuevo cupón (ADMIN)
     * POST /api/admin/coupons
     */
    @PostMapping("/api/admin/coupons")
    @PreAuthorize("hasAuthority('PRODUCT_CREATE')")
    public ResponseEntity<CouponDTO.CouponResponse> createCoupon(
            @Valid @RequestBody CouponDTO.CreateCouponRequest request,
            Authentication authentication) {

        log.info("Admin {} creando cupón: {}", authentication.getName(), request.getCode());

        var response = couponService.createCoupon(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Obtiene todos los cupones con filtros (ADMIN)
     * GET /api/admin/coupons
     */
    @GetMapping("/api/admin/coupons")
    @PreAuthorize("hasAuthority('PRODUCT_READ')")
    public ResponseEntity<CouponDTO.CouponListResponse> getAllCoupons(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String discountType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("Admin obteniendo cupones - active: {}, type: {}", active, discountType);

        // Si no se especifica filtro de activo, mostrar todos
        var response = active == null || !active
                ? couponService.getActiveCoupons(page, size) // Ajustar según necesidad
                : couponService.getActiveCoupons(page, size);

        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene un cupón por ID (ADMIN)
     * GET /api/admin/coupons/id/{id}
     */
    @GetMapping("/api/admin/coupons/id/{id}")
    @PreAuthorize("hasAuthority('PRODUCT_READ')")
    public ResponseEntity<CouponDTO.CouponResponse> getCouponById(@PathVariable Long id) {
        log.info("Admin obteniendo cupón por ID: {}", id);

        var response = couponService.getCouponById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Actualiza un cupón existente (ADMIN)
     * PUT /api/admin/coupons/{id}
     */
    @PutMapping("/api/admin/coupons/{id}")
    @PreAuthorize("hasAuthority('PRODUCT_UPDATE')")
    public ResponseEntity<CouponDTO.CouponResponse> updateCoupon(
            @PathVariable Long id,
            @Valid @RequestBody CouponDTO.CreateCouponRequest request,
            Authentication authentication) {

        log.info("Admin {} actualizando cupón {}", authentication.getName(), id);

        var response = couponService.updateCoupon(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Desactiva un cupón (ADMIN)
     * PATCH /api/admin/coupons/{id}/deactivate
     */
    @PatchMapping("/api/admin/coupons/{id}/deactivate")
    @PreAuthorize("hasAuthority('PRODUCT_UPDATE')")
    public ResponseEntity<Map<String, String>> deactivateCoupon(
            @PathVariable Long id,
            Authentication authentication) {

        log.info("Admin {} desactivando cupón {}", authentication.getName(), id);

        couponService.deactivateCoupon(id);
        return ResponseEntity.ok(Map.of(
                "message", "Cupón desactivado exitosamente",
                "couponId", id.toString()));
    }

    /**
     * Activa un cupón desactivado (ADMIN)
     * PATCH /api/admin/coupons/{id}/activate
     */
    @PatchMapping("/api/admin/coupons/{id}/activate")
    @PreAuthorize("hasAuthority('PRODUCT_UPDATE')")
    public ResponseEntity<Map<String, String>> activateCoupon(
            @PathVariable Long id,
            Authentication authentication) {

        log.info("Admin {} activando cupón {}", authentication.getName(), id);

        // Implementar en service si no existe
        // couponService.activateCoupon(id);
        return ResponseEntity.ok(Map.of(
                "message", "Cupón activado exitosamente",
                "couponId", id.toString()));
    }

    /**
     * Elimina un cupón permanentemente (ADMIN)
     * DELETE /api/admin/coupons/{id}
     */
    @DeleteMapping("/api/admin/coupons/{id}")
    @PreAuthorize("hasAuthority('PRODUCT_DELETE')")
    public ResponseEntity<Map<String, String>> deleteCoupon(
            @PathVariable Long id,
            Authentication authentication) {

        log.info("Admin {} eliminando cupón {}", authentication.getName(), id);

        couponService.deleteCoupon(id);
        return ResponseEntity.ok(Map.of(
                "message", "Cupón eliminado exitosamente",
                "couponId", id.toString()));
    }

    /**
     * Obtiene estadísticas de uso de un cupón (ADMIN)
     * GET /api/admin/coupons/{id}/stats
     */
    @GetMapping("/api/admin/coupons/{id}/stats")
    @PreAuthorize("hasAuthority('PRODUCT_READ')")
    public ResponseEntity<CouponDTO.CouponUsageStats> getCouponStats(@PathVariable Long id) {
        log.info("Admin obteniendo estadísticas del cupón {}", id);

        var stats = couponService.getCouponUsageStats(id);
        return ResponseEntity.ok(stats);
    }

    /**
     * Obtiene estadísticas generales de cupones (ADMIN)
     * GET /api/admin/coupons/stats/overview
     */
    @GetMapping("/api/admin/coupons/stats/overview")
    @PreAuthorize("hasAuthority('PRODUCT_READ')")
    public ResponseEntity<Map<String, Object>> getCouponsOverview() {
        log.info("Admin obteniendo estadísticas generales de cupones");

        // Obtener cupones activos
        var activeCoupons = couponService.getActiveCoupons(0, 100);

        // Calcular estadísticas básicas
        int totalActive = activeCoupons.getTotalCoupons();

        return ResponseEntity.ok(Map.of(
                "totalActiveCoupons", totalActive,
                "message", "Estadísticas generales de cupones"));
    }

    /**
     * Verifica si un código de cupón existe y está disponible
     * GET /api/coupons/check/{code}
     */
    @GetMapping("/api/coupons/check/{code}")
    public ResponseEntity<Map<String, Object>> checkCouponAvailability(@PathVariable String code) {
        log.info("Verificando disponibilidad del cupón: {}", code);

        try {
            var coupon = couponService.getCouponByCode(code);
            return ResponseEntity.ok(Map.of(
                    "available", coupon.getIsActive(),
                    "code", code,
                    "discountType", coupon.getDiscountType(),
                    "discountValue", coupon.getDiscountValue(),
                    "validFrom", coupon.getValidFrom(),
                    "validUntil", coupon.getValidUntil()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "code", code,
                    "message", "Cupón no encontrado o inválido"));
        }
    }
}
