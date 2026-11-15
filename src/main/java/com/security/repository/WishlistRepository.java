package com.security.repository;

import com.security.entity.Wishlist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Repository para wishlist (lista de deseos)
 */
@Repository
public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    /**
     * Encuentra wishlist de un usuario
     */
    @Query("SELECT w FROM Wishlist w JOIN FETCH w.product WHERE w.user.id = :userId ORDER BY w.priority DESC, w.addedAt DESC")
    List<Wishlist> findByUserIdWithProduct(@Param("userId") Long userId);

    /**
     * Encuentra wishlist de un usuario paginada
     */
    Page<Wishlist> findByUserIdOrderByPriorityDescAddedAtDesc(Long userId, Pageable pageable);

    /**
     * Encuentra un item específico en wishlist
     */
    @Query("SELECT w FROM Wishlist w WHERE w.user.id = :userId AND w.product.id = :productId")
    Optional<Wishlist> findByUserIdAndProductId(@Param("userId") Long userId, @Param("productId") Long productId);

    /**
     * Verifica si un producto está en la wishlist de un usuario
     */
    @Query("SELECT COUNT(w) > 0 FROM Wishlist w WHERE w.user.id = :userId AND w.product.id = :productId")
    boolean existsByUserIdAndProductId(@Param("userId") Long userId, @Param("productId") Long productId);

    /**
     * Cuenta items en wishlist de un usuario
     */
    Long countByUserId(Long userId);

    /**
     * Encuentra items de alta prioridad
     */
    @Query("SELECT w FROM Wishlist w WHERE w.user.id = :userId AND w.priority = 3 ORDER BY w.addedAt DESC")
    List<Wishlist> findHighPriorityByUserId(@Param("userId") Long userId);

    /**
     * Encuentra items fuera de stock
     */
    @Query("SELECT w FROM Wishlist w JOIN w.product p WHERE w.user.id = :userId AND p.stock = 0 ORDER BY w.priority DESC")
    List<Wishlist> findOutOfStockByUserId(@Param("userId") Long userId);

    /**
     * Encuentra items en stock
     */
    @Query("SELECT w FROM Wishlist w JOIN w.product p WHERE w.user.id = :userId AND p.stock > 0 ORDER BY w.priority DESC")
    List<Wishlist> findInStockByUserId(@Param("userId") Long userId);

    /**
     * Encuentra items con bajada de precio
     */
    @Query("SELECT w FROM Wishlist w JOIN w.product p WHERE w.user.id = :userId AND " +
            "((p.discountPrice IS NOT NULL AND p.discountPrice < w.priceWhenAdded) OR " +
            "(p.discountPrice IS NULL AND p.price < w.priceWhenAdded)) " +
            "ORDER BY w.priority DESC")
    List<Wishlist> findPriceDroppedByUserId(@Param("userId") Long userId);

    /**
     * Encuentra items no notificados sobre stock
     */
    @Query("SELECT w FROM Wishlist w JOIN w.product p WHERE w.user.id = :userId AND " +
            "w.notifiedBackInStock = false AND p.stock > 0")
    List<Wishlist> findUnnotifiedBackInStock(@Param("userId") Long userId);

    /**
     * Encuentra items no notificados sobre descuento
     */
    @Query("SELECT w FROM Wishlist w JOIN w.product p WHERE w.user.id = :userId AND " +
            "w.notifiedDiscount = false AND " +
            "((p.discountPrice IS NOT NULL AND p.discountPrice < w.priceWhenAdded) OR " +
            "(p.discountPrice IS NULL AND p.price < w.priceWhenAdded))")
    List<Wishlist> findUnnotifiedDiscount(@Param("userId") Long userId);

    /**
     * Calcula valor total de wishlist
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN p.discountPrice IS NOT NULL THEN p.discountPrice ELSE p.price END), 0) " +
            "FROM Wishlist w JOIN w.product p WHERE w.user.id = :userId")
    BigDecimal calculateTotalValue(@Param("userId") Long userId);

    /**
     * Calcula ahorro potencial (precio guardado vs actual)
     */
    @Query("SELECT COALESCE(SUM(w.priceWhenAdded - CASE WHEN p.discountPrice IS NOT NULL THEN p.discountPrice ELSE p.price END), 0) "
            +
            "FROM Wishlist w JOIN w.product p WHERE w.user.id = :userId AND " +
            "((p.discountPrice IS NOT NULL AND p.discountPrice < w.priceWhenAdded) OR " +
            "(p.discountPrice IS NULL AND p.price < w.priceWhenAdded))")
    BigDecimal calculatePotentialSavings(@Param("userId") Long userId);

    /**
     * Encuentra items por prioridad
     */
    @Query("SELECT w FROM Wishlist w WHERE w.user.id = :userId AND w.priority = :priority ORDER BY w.addedAt DESC")
    List<Wishlist> findByUserIdAndPriority(@Param("userId") Long userId, @Param("priority") Integer priority);

    /**
     * Productos más agregados a wishlists
     */
    @Query("SELECT w.product.id, COUNT(w) FROM Wishlist w GROUP BY w.product.id ORDER BY COUNT(w) DESC")
    List<Object[]> findMostWishedProducts();

    /**
     * Elimina items de wishlist de un producto
     */
    void deleteByProductId(Long productId);

    // FASE 2 — Toggle optimizado desde product-card
    /**
     * Elimina el item de wishlist de un usuario por productId.
     * Permite el toggle sin necesitar conocer el wishlistId de antemano.
     */
    @Modifying
    @Query("DELETE FROM Wishlist w WHERE w.user.id = :userId AND w.product.id = :productId")
    void deleteByUserIdAndProductId(@Param("userId") Long userId, @Param("productId") Long productId);
}
