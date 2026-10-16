package com.security.repository;

import com.security.dto.admin.RecentOrderDTO;
import com.security.entity.Order;
import com.security.enums.OrderStatus;
import com.security.enums.PaymentStatus;
import com.security.enums.ShippingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 📦 Repositorio de Órdenes con búsqueda avanzada
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

        Optional<Order> findByOrderNumber(String orderNumber);

        List<Order> findByUserId(Long userId);

        Page<Order> findByUserId(Long userId, Pageable pageable);

        Page<Order> findByStatus(OrderStatus status, Pageable pageable);

        List<Order> findByStatus(OrderStatus status);

        Page<Order> findByPaymentStatus(PaymentStatus paymentStatus, Pageable pageable);

        /**
         * 🚚 Buscar por estado de envío
         */
        Page<Order> findByShippingStatus(ShippingStatus shippingStatus, Pageable pageable);

        @Query("SELECT o FROM Order o WHERE " +
                        "(o.orderNumber LIKE %:keyword% OR " +
                        "LOWER(o.user.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                        "LOWER(o.user.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')))")
        Page<Order> searchOrders(@Param("keyword") String keyword, Pageable pageable);

        @Query("SELECT o FROM Order o WHERE o.createdAt BETWEEN :startDate AND :endDate")
        List<Order> findOrdersByDateRange(@Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate);

        @Query("SELECT SUM(o.total) FROM Order o WHERE o.status != 'CANCELLED' AND o.createdAt BETWEEN :startDate AND :endDate")
        BigDecimal calculateTotalSales(@Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate);

        @Query("SELECT COUNT(o) FROM Order o WHERE o.createdAt BETWEEN :startDate AND :endDate")
        Long countOrdersByDateRange(@Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate);

        @Query("SELECT o FROM Order o ORDER BY o.createdAt DESC")
        List<Order> findRecentOrders(Pageable pageable);

        /**
         * Proyección segura para el dashboard: evita LazyInitializationException
         * al acceder a {@code Order.user} fuera de la sesión Hibernate.
         * Concatena firstName + lastName del usuario directamente en JPQL.
         */
        @Query("""
                        SELECT new com.security.dto.admin.RecentOrderDTO(
                            o.id,
                            o.orderNumber,
                            o.user.id,
                            CONCAT(o.user.firstName, ' ', o.user.lastName),
                            o.total,
                            o.status,
                            o.createdAt
                        )
                        FROM Order o
                        ORDER BY o.createdAt DESC
                        """)
        List<RecentOrderDTO> findRecentOrderDtos(Pageable pageable);

        Long countByStatus(OrderStatus status);

        boolean existsByOrderNumber(String orderNumber);

        /**
         * Verifica si un usuario ha comprado un producto en una orden válida
         */
        @Query("SELECT COUNT(o) > 0 FROM Order o JOIN o.items i WHERE o.user.id = :userId AND i.product.id = :productId AND o.status IN ('CONFIRMED', 'PROCESSING', 'COMPLETED')")
        boolean hasUserPurchasedProduct(@Param("userId") Long userId, @Param("productId") Long productId);

        /**
         * Encuentra las órdenes válidas de un producto por usuario
         */
        @Query("SELECT o FROM Order o JOIN o.items i WHERE o.user.id = :userId AND i.product.id = :productId AND o.status IN ('CONFIRMED', 'PROCESSING', 'COMPLETED') ORDER BY o.createdAt DESC")
        List<Order> findValidOrdersForProduct(@Param("userId") Long userId, @Param("productId") Long productId);
}
