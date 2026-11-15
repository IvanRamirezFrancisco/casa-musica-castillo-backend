package com.security.controller;

import com.security.dto.WishlistDTO;
import com.security.security.UserPrincipal;
import com.security.service.WishlistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller para gestión de lista de deseos (wishlist).
 * Endpoints: /api/wishlist
 * CORS se maneja globalmente en SecurityConfig.
 *
 * <p>Todos los métodos requieren autenticación ({@code @PreAuthorize("isAuthenticated()")}
 * a nivel de clase). El usuario solo puede operar sobre sus propios datos —
 * el userId se extrae del token JWT, nunca se confía en el cliente.
 */
@RestController
@RequestMapping("/api/wishlist")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class WishlistController {

    private final WishlistService wishlistService;

    // ── Consultas ──────────────────────────────────────────────────────────────

    /**
     * Obtiene la wishlist completa del usuario autenticado.
     * GET /api/wishlist
     */
    @GetMapping
    public ResponseEntity<WishlistDTO.WishlistResponse> getWishlist(Authentication authentication) {
        log.info("Usuario {} obteniendo su wishlist", authentication.getName());
        Long userId = getUserIdFromAuth(authentication);
        return ResponseEntity.ok(wishlistService.getWishlist(userId));
    }

    /**
     * Verifica si un producto específico está en la wishlist (para el botón corazón).
     * Respuesta ligera — no carga toda la lista.
     * GET /api/wishlist/check/{productId}
     */
    @GetMapping("/check/{productId}")
    public ResponseEntity<WishlistDTO.CheckResponse> checkProduct(
            @PathVariable Long productId,
            Authentication authentication) {

        log.debug("Usuario {} verificando producto {} en wishlist",
                authentication.getName(), productId);
        Long userId = getUserIdFromAuth(authentication);
        return ResponseEntity.ok(wishlistService.checkProductInWishlist(userId, productId));
    }

    /**
     * Obtiene items de la wishlist con bajada de precio.
     * GET /api/wishlist/price-drops
     */
    @GetMapping("/price-drops")
    public ResponseEntity<List<WishlistDTO.WishlistItemResponse>> getPriceDroppedItems(
            Authentication authentication) {

        log.info("Usuario {} obteniendo items con descuento", authentication.getName());
        Long userId = getUserIdFromAuth(authentication);
        return ResponseEntity.ok(wishlistService.getPriceDroppedItems(userId));
    }

    /**
     * Obtiene items que están en stock.
     * GET /api/wishlist/in-stock
     */
    @GetMapping("/in-stock")
    public ResponseEntity<List<WishlistDTO.WishlistItemResponse>> getInStockItems(
            Authentication authentication) {

        log.info("Usuario {} obteniendo items en stock", authentication.getName());
        Long userId = getUserIdFromAuth(authentication);
        return ResponseEntity.ok(wishlistService.getInStockItems(userId));
    }

    /**
     * Obtiene items sin stock.
     * GET /api/wishlist/out-of-stock
     */
    @GetMapping("/out-of-stock")
    public ResponseEntity<List<WishlistDTO.WishlistItemResponse>> getOutOfStockItems(
            Authentication authentication) {

        log.info("Usuario {} obteniendo items sin stock", authentication.getName());
        Long userId = getUserIdFromAuth(authentication);
        return ResponseEntity.ok(wishlistService.getOutOfStockItems(userId));
    }

    /**
     * Obtiene resumen estadístico de la wishlist.
     * GET /api/wishlist/summary
     */
    @GetMapping("/summary")
    public ResponseEntity<WishlistDTO.WishlistSummaryResponse> getWishlistSummary(
            Authentication authentication) {

        log.info("Usuario {} obteniendo resumen de wishlist", authentication.getName());
        Long userId = getUserIdFromAuth(authentication);
        return ResponseEntity.ok(wishlistService.getWishlistSummary(userId));
    }

    /**
     * Obtiene notificaciones de precio/stock para la wishlist.
     * GET /api/wishlist/notifications
     */
    @GetMapping("/notifications")
    public ResponseEntity<Map<String, Object>> getNotifications(Authentication authentication) {
        log.info("Usuario {} obteniendo notificaciones de wishlist", authentication.getName());
        Long userId = getUserIdFromAuth(authentication);

        var priceNotifications = wishlistService.checkPriceDrops(userId);
        var stockNotifications = wishlistService.checkBackInStock(userId);

        return ResponseEntity.ok(Map.of(
                "priceDrops", priceNotifications,
                "backInStock", stockNotifications,
                "total", priceNotifications.size() + stockNotifications.size()));
    }

    // ── Mutaciones ─────────────────────────────────────────────────────────────

    /**
     * Agrega un producto a la wishlist del usuario autenticado.
     * POST /api/wishlist
     */
    @PostMapping
    public ResponseEntity<WishlistDTO.WishlistItemResponse> addToWishlist(
            @Valid @RequestBody WishlistDTO.AddToWishlistRequest request,
            Authentication authentication) {

        log.info("Usuario {} agregando producto {} a wishlist",
                authentication.getName(), request.getProductId());
        Long userId = getUserIdFromAuth(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(wishlistService.addToWishlist(userId, request));
    }

    /**
     * Actualiza prioridad/notas de un item de la wishlist.
     * PUT /api/wishlist/{itemId}
     */
    @PutMapping("/{itemId}")
    public ResponseEntity<WishlistDTO.WishlistItemResponse> updateWishlistItem(
            @PathVariable Long itemId,
            @Valid @RequestBody WishlistDTO.UpdateWishlistRequest request,
            Authentication authentication) {

        log.info("Usuario {} actualizando wishlist item {}", authentication.getName(), itemId);
        Long userId = getUserIdFromAuth(authentication);
        return ResponseEntity.ok(wishlistService.updateWishlistItem(itemId, userId, request));
    }

    /**
     * Elimina un item de la wishlist por su ID de registro (wishlistId).
     * DELETE /api/wishlist/{itemId}
     */
    @DeleteMapping("/{itemId}")
    public ResponseEntity<Map<String, String>> removeFromWishlist(
            @PathVariable Long itemId,
            Authentication authentication) {

        log.info("Usuario {} eliminando wishlist item {}", authentication.getName(), itemId);
        Long userId = getUserIdFromAuth(authentication);
        wishlistService.removeFromWishlist(itemId, userId);

        return ResponseEntity.ok(Map.of(
                "message", "Producto eliminado de la wishlist",
                "itemId", itemId.toString()));
    }

    /**
     * Elimina un item de la wishlist por su productId — sin necesitar el wishlistId.
     * Permite el toggle directo desde el botón corazón del product-card.
     * DELETE /api/wishlist/by-product/{productId}
     */
    @DeleteMapping("/by-product/{productId}")
    public ResponseEntity<Map<String, String>> removeByProductId(
            @PathVariable Long productId,
            Authentication authentication) {

        log.info("Usuario {} eliminando producto {} de wishlist por productId",
                authentication.getName(), productId);
        Long userId = getUserIdFromAuth(authentication);
        wishlistService.removeByProductId(userId, productId);

        return ResponseEntity.ok(Map.of(
                "message", "Producto eliminado de la wishlist",
                "productId", productId.toString()));
    }

    /**
     * Mueve un item de la wishlist al carrito usando el SP sp_move_wishlist_to_cart.
     * POST /api/wishlist/{itemId}/move-to-cart
     */
    @PostMapping("/{itemId}/move-to-cart")
    public ResponseEntity<Map<String, String>> moveToCart(
            @PathVariable Long itemId,
            Authentication authentication) {

        log.info("Usuario {} moviendo wishlist item {} al carrito",
                authentication.getName(), itemId);
        Long userId = getUserIdFromAuth(authentication);
        wishlistService.moveToCart(itemId, userId);

        return ResponseEntity.ok(Map.of(
                "message", "Producto agregado al carrito",
                "itemId", itemId.toString()));
    }

    /**
     * Mueve todos los items disponibles (en stock) al carrito.
     * POST /api/wishlist/move-all-to-cart
     */
    @PostMapping("/move-all-to-cart")
    public ResponseEntity<Map<String, Object>> moveAllToCart(Authentication authentication) {
        log.info("Usuario {} moviendo toda la wishlist al carrito", authentication.getName());
        Long userId = getUserIdFromAuth(authentication);

        var inStockItems = wishlistService.getInStockItems(userId);
        int movedCount = 0;

        for (var item : inStockItems) {
            try {
                wishlistService.moveToCart(item.getWishlistId(), userId);
                movedCount++;
            } catch (Exception e) {
                log.warn("Error moviendo item {} al carrito: {}",
                        item.getWishlistId(), e.getMessage());
            }
        }

        return ResponseEntity.ok(Map.of(
                "message", "Items movidos al carrito",
                "totalItems", inStockItems.size(),
                "movedItems", movedCount));
    }

    /**
     * Verifica cambios de precio en todos los items.
     * POST /api/wishlist/check-prices
     */
    @PostMapping("/check-prices")
    public ResponseEntity<Map<String, Object>> checkPrices(Authentication authentication) {
        log.info("Usuario {} verificando precios de wishlist", authentication.getName());
        Long userId = getUserIdFromAuth(authentication);
        var changes = wishlistService.checkPriceDrops(userId);

        return ResponseEntity.ok(Map.of(
                "message", "Verificación de precios completada",
                "changesFound", changes.size(),
                "changes", changes));
    }

    /**
     * Verifica disponibilidad de stock en todos los items.
     * POST /api/wishlist/check-stock
     */
    @PostMapping("/check-stock")
    public ResponseEntity<Map<String, Object>> checkStock(Authentication authentication) {
        log.info("Usuario {} verificando stock de wishlist", authentication.getName());
        Long userId = getUserIdFromAuth(authentication);
        var notifications = wishlistService.checkBackInStock(userId);

        return ResponseEntity.ok(Map.of(
                "message", "Verificación de stock completada",
                "notificationsCreated", notifications.size(),
                "notifications", notifications));
    }

    /**
     * Limpia la wishlist completa del usuario.
     * DELETE /api/wishlist
     */
    @DeleteMapping
    public ResponseEntity<Map<String, String>> clearWishlist(Authentication authentication) {
        log.info("Usuario {} limpiando toda su wishlist", authentication.getName());
        Long userId = getUserIdFromAuth(authentication);

        var wishlistResponse = wishlistService.getWishlist(userId);
        wishlistResponse.getItems()
                .forEach(item -> wishlistService.removeFromWishlist(item.getWishlistId(), userId));

        return ResponseEntity.ok(Map.of(
                "message", "Wishlist limpiada exitosamente",
                "removedItems", String.valueOf(wishlistResponse.getItems().size())));
    }

    // ── Helper privado ─────────────────────────────────────────────────────────

    /**
     * Extrae el userId del principal JWT.
     *
     * <p>Lanza {@link AccessDeniedException} si el principal no es del tipo esperado
     * ({@link UserPrincipal}), en lugar de devolver {@code null} silenciosamente y
     * causar un NullPointerException más adelante.
     *
     * <p>La anotación {@code @PreAuthorize("isAuthenticated()")} de clase garantiza
     * que {@code authentication} nunca es {@code null} ni está sin autenticar cuando
     * se llega a este método.
     */
    private Long getUserIdFromAuth(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal.getId();
        }
        log.error("Principal de tipo inesperado en WishlistController: {}",
                principal != null ? principal.getClass().getName() : "null");
        throw new AccessDeniedException(
                "No se pudo identificar al usuario autenticado. Por favor, vuelve a iniciar sesión.");
    }
}
