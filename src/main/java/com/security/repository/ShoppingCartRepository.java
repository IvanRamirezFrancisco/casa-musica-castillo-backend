package com.security.repository;

import com.security.entity.ShoppingCart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository para carritos de compra
 */
@Repository
public interface ShoppingCartRepository extends JpaRepository<ShoppingCart, Long> {

    /**
     * Encuentra el carrito activo de un usuario.
     * ⚠️ ATENCIÓN: Si existen carritos ACTIVE duplicados para el mismo usuario,
     * esta query lanza NonUniqueResultException. Usar findAllActiveCartsByUserId
     * en la lógica de negocio para ser tolerante a duplicados.
     */
    @Query("SELECT c FROM ShoppingCart c WHERE c.user.id = :userId AND c.status = 'ACTIVE' AND c.expiresAt > :now")
    Optional<ShoppingCart> findActiveCartByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    // FASE 1.1 - Carrito duplicado - 2026-05-15
    /**
     * Devuelve TODOS los carritos ACTIVE de un usuario ordenados del más reciente al más antiguo.
     * Orden: updatedAt DESC NULLS LAST, createdAt DESC NULLS LAST, id DESC.
     *
     * Usar este método en getOrCreateCartForUser para ser tolerante a duplicados:
     * si la lista tiene >1 elemento, el servicio conserva el primero (más reciente)
     * y cierra los demás como ABANDONED.
     */
    @Query("""
            SELECT c FROM ShoppingCart c
            WHERE c.user.id = :userId
              AND c.status = 'ACTIVE'
              AND c.expiresAt > :now
            ORDER BY
                CASE WHEN c.updatedAt IS NULL THEN 1 ELSE 0 END,
                c.updatedAt DESC,
                CASE WHEN c.createdAt IS NULL THEN 1 ELSE 0 END,
                c.createdAt DESC,
                c.id DESC
            """)
    List<ShoppingCart> findAllActiveCartsByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    // FASE 1.2 - Fix bug: carrito ACTIVE con expiresAt vencido causa DataIntegrityViolationException
    /**
     * Devuelve TODOS los carritos con status = 'ACTIVE' para un usuario,
     * SIN filtrar por expiresAt. Permite detectar carritos cuya fecha ya
     * venció antes de intentar un INSERT que violaría el constraint único
     * ux_shopping_carts_one_active_per_user.
     *
     * Orden: updatedAt DESC NULLS LAST, createdAt DESC NULLS LAST, id DESC.
     */
    @Query("""
            SELECT c FROM ShoppingCart c
            WHERE c.user.id = :userId
              AND c.status = 'ACTIVE'
            ORDER BY
                CASE WHEN c.updatedAt IS NULL THEN 1 ELSE 0 END,
                c.updatedAt DESC,
                CASE WHEN c.createdAt IS NULL THEN 1 ELSE 0 END,
                c.createdAt DESC,
                c.id DESC
            """)
    List<ShoppingCart> findAllActiveCartsByUserIdIgnoringExpiry(@Param("userId") Long userId);

    /**
     * Encuentra el carrito activo por ID de sesión
     */
    @Query("SELECT c FROM ShoppingCart c WHERE c.sessionId = :sessionId AND c.status = 'ACTIVE' AND c.expiresAt > :now")
    Optional<ShoppingCart> findActiveCartBySessionId(@Param("sessionId") String sessionId,
            @Param("now") LocalDateTime now);

    /**
     * Encuentra todos los carritos de un usuario
     */
    List<ShoppingCart> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Encuentra carritos expirados
     */
    @Query("SELECT c FROM ShoppingCart c WHERE c.expiresAt < :now AND c.status = 'ACTIVE'")
    List<ShoppingCart> findExpiredCarts(@Param("now") LocalDateTime now);

    /**
     * Encuentra carritos abandonados (más de X horas sin actualizar)
     */
    @Query("SELECT c FROM ShoppingCart c WHERE c.status = 'ACTIVE' AND c.updatedAt < :threshold")
    List<ShoppingCart> findAbandonedCarts(@Param("threshold") LocalDateTime threshold);

    /**
     * Marca carritos como expirados
     */
    @Modifying
    @Query("UPDATE ShoppingCart c SET c.status = 'EXPIRED' WHERE c.expiresAt < :now AND c.status = 'ACTIVE'")
    int markExpiredCarts(@Param("now") LocalDateTime now);

    /**
     * Encuentra carritos con cupón aplicado
     */
    @Query("SELECT c FROM ShoppingCart c WHERE c.couponCode = :couponCode AND c.status = 'ACTIVE'")
    List<ShoppingCart> findByActiveCoupon(@Param("couponCode") String couponCode);

    /**
     * Cuenta carritos activos por usuario
     */
    @Query("SELECT COUNT(c) FROM ShoppingCart c WHERE c.user.id = :userId AND c.status = 'ACTIVE'")
    Long countActiveCartsByUser(@Param("userId") Long userId);

    /**
     * Obtiene el carrito con items cargados
     */
    @Query("SELECT DISTINCT c FROM ShoppingCart c LEFT JOIN FETCH c.items WHERE c.id = :cartId")
    Optional<ShoppingCart> findByIdWithItems(@Param("cartId") Long cartId);

    /**
     * Elimina carritos expirados antiguos (más de 30 días)
     */
    @Modifying
    @Query("DELETE FROM ShoppingCart c WHERE c.status = 'EXPIRED' AND c.updatedAt < :threshold")
    int deleteOldExpiredCarts(@Param("threshold") LocalDateTime threshold);
}
