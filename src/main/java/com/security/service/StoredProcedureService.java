package com.security.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.StoredProcedureQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Service para llamadas directas a Stored Procedures de MySQL
 * Centraliza todas las llamadas a SPs de la base de datos
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StoredProcedureService {

    private final EntityManager entityManager;

    /**
     * Calcula el descuento de un cupón
     * SP: sp_calculate_coupon_discount(p_coupon_id, p_amount, OUT p_discount)
     */
    @Transactional
    public BigDecimal calculateCouponDiscount(Long couponId, BigDecimal amount) {
        log.info("Calculando descuento del cupón {} para monto {}", couponId, amount);

        try {
            StoredProcedureQuery query = entityManager.createStoredProcedureQuery("sales.sp_calculate_coupon_discount");

            query.registerStoredProcedureParameter("p_coupon_id", Long.class, ParameterMode.IN);
            query.registerStoredProcedureParameter("p_amount", BigDecimal.class, ParameterMode.IN);
            query.registerStoredProcedureParameter("p_discount", BigDecimal.class, ParameterMode.OUT);

            query.setParameter("p_coupon_id", couponId);
            query.setParameter("p_amount", amount);

            query.execute();

            BigDecimal discount = (BigDecimal) query.getOutputParameterValue("p_discount");
            log.info("Descuento calculado: {}", discount);

            return discount != null ? discount : BigDecimal.ZERO;

        } catch (Exception e) {
            log.error("Error al calcular descuento del cupón: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Aplica un cupón a un carrito
     * SP: sp_apply_coupon_to_cart(p_cart_id, p_coupon_id)
     */
    @Transactional
    public void applyCouponToCart(Long cartId, Long couponId) {
        log.info("Aplicando cupón {} al carrito {}", couponId, cartId);

        try {
            StoredProcedureQuery query = entityManager.createStoredProcedureQuery("sales.sp_apply_coupon_to_cart");

            query.registerStoredProcedureParameter("p_cart_id", Long.class, ParameterMode.IN);
            query.registerStoredProcedureParameter("p_coupon_id", Long.class, ParameterMode.IN);

            query.setParameter("p_cart_id", cartId);
            query.setParameter("p_coupon_id", couponId);

            query.execute();

            log.info("Cupón aplicado exitosamente al carrito");

        } catch (Exception e) {
            log.error("Error al aplicar cupón al carrito: {}", e.getMessage());
            throw new RuntimeException("Error al aplicar cupón: " + e.getMessage());
        }
    }

    /**
     * Aplica un cupón a una orden
     * SP: sp_apply_coupon_to_order(p_order_id, p_coupon_id)
     */
    @Transactional
    public void applyCouponToOrder(Long orderId, Long couponId) {
        log.info("Aplicando cupón {} a la orden {}", couponId, orderId);

        try {
            StoredProcedureQuery query = entityManager.createStoredProcedureQuery("sales.sp_apply_coupon_to_order");

            query.registerStoredProcedureParameter("p_order_id", Long.class, ParameterMode.IN);
            query.registerStoredProcedureParameter("p_coupon_id", Long.class, ParameterMode.IN);

            query.setParameter("p_order_id", orderId);
            query.setParameter("p_coupon_id", couponId);

            query.execute();

            log.info("Cupón aplicado exitosamente a la orden");

        } catch (Exception e) {
            log.error("Error al aplicar cupón a la orden: {}", e.getMessage());
            throw new RuntimeException("Error al aplicar cupón: " + e.getMessage());
        }
    }

    /**
     * Recalcula el rating de un producto
     * SP: sp_recalculate_product_rating(p_product_id)
     */
    @Transactional
    public void recalculateProductRating(Long productId) {
        log.info("Recalculando rating del producto {}", productId);

        try {
            StoredProcedureQuery query = entityManager
                    .createStoredProcedureQuery("catalog.sp_recalculate_product_rating");

            query.registerStoredProcedureParameter("p_product_id", Long.class, ParameterMode.IN);
            query.setParameter("p_product_id", productId);

            query.execute();

            log.info("Rating recalculado exitosamente");

        } catch (Exception e) {
            log.error("Error al recalcular rating: {}", e.getMessage());
            // No lanzar excepción, solo log
        }
    }

    /**
     * Calcula totales de una orden
     * SP: sp_calculate_order_totals(p_order_id)
     */
    @Transactional
    public void calculateOrderTotals(Long orderId) {
        log.info("Calculando totales de la orden {}", orderId);

        try {
            StoredProcedureQuery query = entityManager.createStoredProcedureQuery("sales.sp_calculate_order_totals");

            query.registerStoredProcedureParameter("p_order_id", Long.class, ParameterMode.IN);
            query.setParameter("p_order_id", orderId);

            query.execute();

            log.info("Totales calculados exitosamente");

        } catch (Exception e) {
            log.error("Error al calcular totales de la orden: {}", e.getMessage());
            throw new RuntimeException("Error al calcular totales: " + e.getMessage());
        }
    }

    /**
     * Transfiere carrito a orden
     * SP: sp_transfer_cart_to_order(p_cart_id, OUT p_order_id)
     */
    @Transactional
    public Long transferCartToOrder(Long cartId) {
        log.info("Transfiriendo carrito {} a orden", cartId);

        try {
            StoredProcedureQuery query = entityManager.createStoredProcedureQuery("sales.sp_transfer_cart_to_order");

            query.registerStoredProcedureParameter("p_cart_id", Long.class, ParameterMode.IN);
            query.registerStoredProcedureParameter("p_order_id", Long.class, ParameterMode.OUT);

            query.setParameter("p_cart_id", cartId);

            query.execute();

            Long orderId = (Long) query.getOutputParameterValue("p_order_id");
            log.info("Carrito transferido a orden {}", orderId);

            return orderId;

        } catch (Exception e) {
            log.error("Error al transferir carrito a orden: {}", e.getMessage());
            throw new RuntimeException("Error al crear orden: " + e.getMessage());
        }
    }

    /**
     * Cancela una orden
     * SP: sp_cancel_order(p_order_id, p_reason)
     */
    @Transactional
    public void cancelOrder(Long orderId, String reason) {
        log.info("Cancelando orden {}", orderId);

        try {
            StoredProcedureQuery query = entityManager.createStoredProcedureQuery("sales.sp_cancel_order");

            query.registerStoredProcedureParameter("p_order_id", Long.class, ParameterMode.IN);
            query.registerStoredProcedureParameter("p_reason", String.class, ParameterMode.IN);

            query.setParameter("p_order_id", orderId);
            query.setParameter("p_reason", reason);

            query.execute();

            log.info("Orden cancelada exitosamente");

        } catch (Exception e) {
            log.error("Error al cancelar orden: {}", e.getMessage());
            throw new RuntimeException("Error al cancelar orden: " + e.getMessage());
        }
    }

    /**
     * Genera número único de orden
     * SP: sp_generate_order_number(OUT p_order_number)
     */
    @Transactional
    public String generateOrderNumber() {
        log.info("Generando número de orden");

        try {
            StoredProcedureQuery query = entityManager.createStoredProcedureQuery("sales.sp_generate_order_number");

            query.registerStoredProcedureParameter("p_order_number", String.class, ParameterMode.OUT);

            query.execute();

            String orderNumber = (String) query.getOutputParameterValue("p_order_number");
            log.info("Número de orden generado: {}", orderNumber);

            return orderNumber;

        } catch (Exception e) {
            log.error("Error al generar número de orden: {}", e.getMessage());
            // Generar fallback
            return "ORD-" + System.currentTimeMillis();
        }
    }

    /**
     * Actualiza estadísticas de usuario
     * SP: sp_update_user_stats(p_user_id)
     */
    @Transactional
    public void updateUserStats(Long userId) {
        log.info("Actualizando estadísticas del usuario {}", userId);

        try {
            StoredProcedureQuery query = entityManager.createStoredProcedureQuery("auth.sp_update_user_stats");

            query.registerStoredProcedureParameter("p_user_id", Long.class, ParameterMode.IN);
            query.setParameter("p_user_id", userId);

            query.execute();

            log.info("Estadísticas actualizadas exitosamente");

        } catch (Exception e) {
            log.error("Error al actualizar estadísticas: {}", e.getMessage());
            // No lanzar excepción, solo log
        }
    }

    /**
     * Mueve item de wishlist a carrito
     * SP: sp_move_wishlist_to_cart(p_wishlist_id)
     */
    @Transactional
    public void moveWishlistToCart(Long wishlistId) {
        log.info("Moviendo item {} de wishlist a carrito", wishlistId);

        try {
            StoredProcedureQuery query = entityManager.createStoredProcedureQuery("sales.sp_move_wishlist_to_cart");

            query.registerStoredProcedureParameter("p_wishlist_id", Long.class, ParameterMode.IN);
            query.setParameter("p_wishlist_id", wishlistId);

            query.execute();

            log.info("Item movido exitosamente a carrito");

        } catch (Exception e) {
            log.error("Error al mover item a carrito: {}", e.getMessage());
            throw new RuntimeException("Error al mover item a carrito: " + e.getMessage());
        }
    }

    /**
     * Verifica descuentos en wishlist
     * SP: sp_check_wishlist_discounts(p_user_id)
     */
    @Transactional
    public void checkWishlistDiscounts(Long userId) {
        log.info("Verificando descuentos en wishlist del usuario {}", userId);

        try {
            StoredProcedureQuery query = entityManager.createStoredProcedureQuery("sales.sp_check_wishlist_discounts");

            query.registerStoredProcedureParameter("p_user_id", Long.class, ParameterMode.IN);
            query.setParameter("p_user_id", userId);

            query.execute();

            log.info("Verificación de descuentos completada");

        } catch (Exception e) {
            log.error("Error al verificar descuentos: {}", e.getMessage());
            // No lanzar excepción, solo log
        }
    }

    /**
     * Verifica productos de vuelta en stock en wishlist
     * SP: sp_check_wishlist_back_in_stock(p_user_id)
     */
    @Transactional
    public void checkWishlistBackInStock(Long userId) {
        log.info("Verificando productos en stock en wishlist del usuario {}", userId);

        try {
            StoredProcedureQuery query = entityManager
                    .createStoredProcedureQuery("sales.sp_check_wishlist_back_in_stock");

            query.registerStoredProcedureParameter("p_user_id", Long.class, ParameterMode.IN);
            query.setParameter("p_user_id", userId);

            query.execute();

            log.info("Verificación de stock completada");

        } catch (Exception e) {
            log.error("Error al verificar stock: {}", e.getMessage());
            // No lanzar excepción, solo log
        }
    }

    /**
     * Obtiene wishlist con comparación de precios
     * SP: sp_get_wishlist_with_price_comparison(p_user_id)
     */
    @Transactional(readOnly = true)
    public void getWishlistWithPriceComparison(Long userId) {
        log.info("Obteniendo wishlist con comparación de precios del usuario {}", userId);

        try {
            StoredProcedureQuery query = entityManager
                    .createStoredProcedureQuery("sales.sp_get_wishlist_with_price_comparison");

            query.registerStoredProcedureParameter("p_user_id", Long.class, ParameterMode.IN);
            query.setParameter("p_user_id", userId);

            query.execute();

            log.info("Wishlist con comparación de precios obtenida");

        } catch (Exception e) {
            log.error("Error al obtener wishlist: {}", e.getMessage());
        }
    }
}
