package com.security.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidad para registro de uso de cupones
 */
@Entity
@Table(name = "coupon_usage", schema = "sales", indexes = {
        @Index(name = "idx_usage_coupon", columnList = "coupon_id"),
        @Index(name = "idx_usage_user", columnList = "user_id"),
        @Index(name = "idx_usage_order", columnList = "order_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Cupón utilizado
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id", nullable = false)
    @NotNull
    private Coupon coupon;

    /**
     * Usuario que usó el cupón
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @NotNull
    private User user;

    /**
     * Orden donde se aplicó
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    /**
     * Carrito donde se aplicó (antes de convertir a orden)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id")
    private ShoppingCart cart;

    /**
     * Descuento aplicado
     */
    @NotNull
    @Column(name = "discount_applied", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountApplied;

    /**
     * Total de la orden/carrito
     */
    @Column(name = "order_total", precision = 10, scale = 2)
    private BigDecimal orderTotal;

    @CreationTimestamp
    @Column(name = "used_at", nullable = false, updatable = false)
    private LocalDateTime usedAt;
}
