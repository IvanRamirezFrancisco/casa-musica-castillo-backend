package com.security.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entidad para carritos de compra
 * Soporta usuarios autenticados y sesiones anónimas
 */
@Entity
@Table(name = "shopping_carts", schema = "sales", indexes = {
        @Index(name = "idx_cart_user", columnList = "user_id"),
        @Index(name = "idx_cart_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShoppingCart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Usuario dueño del carrito (null para sesiones anónimas)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /**
     * ID de sesión para carritos anónimos
     */
    @Column(name = "session_id", length = 100)
    private String sessionId;

    /**
     * Subtotal antes de IVA y descuentos
     */
    @NotNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    /**
     * IVA aplicado (16% por defecto)
     */
    @NotNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal tax = BigDecimal.ZERO;

    /**
     * Descuento aplicado por cupón
     */
    @Column(precision = 10, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    /**
     * Total final (subtotal + tax - discount)
     */
    @NotNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    /**
     * Código del cupón aplicado (si existe)
     */
    @Column(name = "coupon_code", length = 50)
    private String couponCode;

    /**
     * Estado del carrito: ACTIVE, CONVERTED, EXPIRED, ABANDONED
     */
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    /**
     * Fecha de expiración (72 horas por defecto)
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * Items del carrito
     */
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CartItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Agrega un item al carrito
     */
    public void addItem(CartItem item) {
        items.add(item);
        item.setCart(this);
    }

    /**
     * Remueve un item del carrito
     */
    public void removeItem(CartItem item) {
        items.remove(item);
        item.setCart(null);
    }

    /**
     * Verifica si el carrito está expirado
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Verifica si el carrito está activo
     */
    public boolean isActive() {
        return "ACTIVE".equals(status) && !isExpired();
    }
}
