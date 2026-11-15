package com.security.controller;

import com.security.dto.CartDTO;
import com.security.security.UserPrincipal;
import com.security.service.ShoppingCartService;
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
 * Controller para gestión del carrito de compras
 * Endpoints: /api/cart
 * CORS se maneja globalmente en SecurityConfig
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
@Slf4j
public class ShoppingCartController {

    private final ShoppingCartService cartService;

    /**
     * Obtiene o crea el carrito del usuario autenticado
     * GET /api/cart
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartDTO.CartResponse> getCart(Authentication authentication) {
        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Obteniendo carrito del usuario: {}", userId);

        var cart = cartService.getOrCreateCartForUser(userId);
        var response = cartService.buildCartResponse(cart.getId());

        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene carrito por sesión anónima
     * GET /api/cart/session/{sessionId}
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<CartDTO.CartResponse> getCartBySession(@PathVariable String sessionId) {
        log.info("Obteniendo carrito para sesión: {}", sessionId);

        var cart = cartService.getOrCreateCartForSession(sessionId);
        var response = cartService.buildCartResponse(cart.getId());

        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene resumen del carrito del usuario autenticado
     * GET /api/cart/summary
     */
    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartDTO.CartSummaryResponse> getCartSummary(Authentication authentication) {
        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Obteniendo resumen del carrito del usuario: {}", userId);

        var summary = cartService.getCartSummaryForUser(userId);
        return ResponseEntity.ok(summary);
    }

    /**
     * Agrega un producto al carrito del usuario autenticado
     * POST /api/cart/items
     */
    @PostMapping("/items")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartDTO.CartResponse> addItem(
            @Valid @RequestBody CartDTO.AddItemRequest request,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Agregando producto {} al carrito del usuario {}", request.getProductId(), userId);

        var response = cartService.addItemToUserCart(userId, request.getProductId(), request.getQuantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Actualiza la cantidad de un item (validando ownership)
     * PUT /api/cart/items/{itemId}
     */
    @PutMapping("/items/{itemId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartDTO.CartResponse> updateItemQuantity(
            @PathVariable Long itemId,
            @Valid @RequestBody CartDTO.UpdateItemRequest request,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Actualizando item {} a cantidad {} (usuario {})", itemId, request.getQuantity(), userId);

        var response = cartService.updateItemQuantityForUser(userId, itemId, request.getQuantity());
        return ResponseEntity.ok(response);
    }

    /**
     * Mueve un item del carrito a la wishlist (validando ownership)
     * POST /api/cart/items/{itemId}/move-to-wishlist
     */
    @PostMapping("/items/{itemId}/move-to-wishlist")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartDTO.CartResponse> moveItemToWishlist(
            @PathVariable Long itemId,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Moviendo item {} a wishlist (usuario {})", itemId, userId);

        var response = cartService.moveItemToWishlistForUser(userId, itemId);
        return ResponseEntity.ok(response);
    }

    /**
     * Elimina un item del carrito (validando ownership)
     * DELETE /api/cart/items/{itemId}
     */
    @DeleteMapping("/items/{itemId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartDTO.CartResponse> removeItem(
            @PathVariable Long itemId,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Eliminando item {} (usuario {})", itemId, userId);

        var response = cartService.removeItemForUser(userId, itemId);
        return ResponseEntity.ok(response);
    }

    /**
     * Aplica un cupón al carrito del usuario autenticado
     * POST /api/cart/coupon
     */
    @PostMapping("/coupon")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartDTO.CouponAppliedResponse> applyCoupon(
            @Valid @RequestBody CartDTO.ApplyCouponRequest request,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Aplicando cupón {} al carrito del usuario {}", request.getCouponCode(), userId);

        var response = cartService.applyCouponForUser(userId, request.getCouponCode());
        return ResponseEntity.ok(response);
    }

    /**
     * Remueve el cupón del carrito del usuario autenticado
     * DELETE /api/cart/coupon
     */
    @DeleteMapping("/coupon")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartDTO.CartResponse> removeCoupon(Authentication authentication) {
        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Removiendo cupón del carrito del usuario {}", userId);

        var response = cartService.removeCouponForUser(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Vacía el carrito del usuario autenticado
     * DELETE /api/cart
     */
    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> clearCart(Authentication authentication) {

        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Vaciando carrito del usuario {}", userId);

        cartService.clearCartForUser(userId);
        return ResponseEntity.ok(Map.of("message", "Carrito vaciado exitosamente"));
    }

    /**
     * Valida el carrito del usuario autenticado antes de checkout
     * GET /api/cart/validate
     */
    @GetMapping("/validate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartDTO.CartValidationResponse> validateCart(Authentication authentication) {
        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Validando carrito del usuario {}", userId);

        var response = cartService.validateCartForUser(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Transfiere carrito de sesión anónima a usuario autenticado
     * POST /api/cart/transfer
     */
    @PostMapping("/transfer")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartDTO.CartResponse> transferCart(
            @RequestParam String sessionId,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Transfiriendo carrito de sesión {} a usuario {}", sessionId, userId);

        var cart = cartService.transferCartToUser(sessionId, userId);
        var response = cartService.buildCartResponse(cart.getId());

        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene el carrito activo del usuario autenticado o crea uno nuevo
     * POST /api/cart
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartDTO.CartResponse> createOrGetCart(Authentication authentication) {
        Long userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Creando/obteniendo carrito para usuario: {}", userId);

        var cart = cartService.getOrCreateCartForUser(userId);
        var response = cartService.buildCartResponse(cart.getId());

        return ResponseEntity.ok(response);
    }

    /**
     * Crea carrito para sesión anónima
     * POST /api/cart/anonymous
     */
    @PostMapping("/anonymous")
    public ResponseEntity<CartDTO.CartResponse> createAnonymousCart(@RequestParam(required = false) String sessionId) {
        log.info("Creando carrito anónimo para sesión: {}", sessionId);

        var cart = cartService.getOrCreateCartForSession(sessionId);
        var response = cartService.buildCartResponse(cart.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Extrae el ID numérico del usuario desde el objeto Authentication.
     * El principal es siempre un UserPrincipal (implementación interna de
     * UserDetails)
     * que almacena el id de la entidad User, NO su email.
     */
    private Long extractUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal.getId();
        }
        log.warn("Principal de tipo inesperado: {}. Se esperaba UserPrincipal.",
                principal != null ? principal.getClass().getName() : "null");
        return null;
    }
}
