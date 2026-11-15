package com.security.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Entidad para wishlist (lista de deseos)
 */
@Entity
@Table(name = "wishlists", schema = "sales", uniqueConstraints = {
        @UniqueConstraint(name = "uk_wishlist_user_product", columnNames = { "user_id", "product_id" })
}, indexes = {
        @Index(name = "idx_wishlist_user", columnList = "user_id"),
        @Index(name = "idx_wishlist_product", columnList = "product_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Wishlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Usuario dueño de la wishlist
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @NotNull
    private User user;

    /**
     * Producto deseado
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id", nullable = false)
    @NotNull
    private Product product;

    /**
     * Precio al momento de agregar
     */
    @Column(name = "price_when_added", precision = 10, scale = 2)
    private BigDecimal priceWhenAdded;

    /**
     * Prioridad: LOW (1), MEDIUM (2), HIGH (3)
     */
    @Column(nullable = false)
    private Integer priority = 2; // MEDIUM por defecto

    /**
     * Notas personales sobre el producto
     */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Indica si se notificó al usuario cuando volvió a stock
     */
    @Column(name = "notified_back_in_stock", nullable = false)
    private Boolean notifiedBackInStock = false;

    /**
     * Indica si se notificó al usuario sobre descuento
     */
    @Column(name = "notified_discount", nullable = false)
    private Boolean notifiedDiscount = false;

    @CreationTimestamp
    @Column(name = "added_at", nullable = false, updatable = false)
    private LocalDateTime addedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Verifica si el precio actual es menor al guardado
     */
    public boolean isPriceDropped() {
        if (priceWhenAdded == null || product == null)
            return false;
        BigDecimal currentPrice = product.getDiscountPrice() != null
                ? product.getDiscountPrice()
                : product.getPrice();
        return currentPrice.compareTo(priceWhenAdded) < 0;
    }

    /**
     * Calcula el porcentaje de descuento
     */
    public BigDecimal getDiscountPercentage() {
        if (!isPriceDropped())
            return BigDecimal.ZERO;

        BigDecimal currentPrice = product.getDiscountPrice() != null
                ? product.getDiscountPrice()
                : product.getPrice();

        BigDecimal diff = priceWhenAdded.subtract(currentPrice);
        return diff.divide(priceWhenAdded, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    /**
     * Verifica si el producto está de vuelta en stock
     */
    public boolean isBackInStock() {
        return product != null && product.getStock() > 0;
    }
}
