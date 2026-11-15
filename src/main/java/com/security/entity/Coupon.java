package com.security.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Entidad para cupones de descuento
 */
@Entity
@Table(name = "coupons", schema = "sales", indexes = {
        @Index(name = "idx_coupon_type", columnList = "discount_type"),
        @Index(name = "idx_coupon_active", columnList = "is_active"),
        @Index(name = "idx_coupon_dates", columnList = "valid_from, valid_until")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Código único del cupón (ej: VERANO2024)
     */
    @NotBlank(message = "El código del cupón es obligatorio")
    @Size(min = 3, max = 50, message = "El código debe tener entre 3 y 50 caracteres")
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    /**
     * Descripción del cupón
     */
    @Size(max = 200, message = "La descripción no puede exceder 200 caracteres")
    @Column(length = 200)
    private String description;

    /**
     * Tipo de descuento: PERCENTAGE, FIXED, FREE_SHIPPING
     */
    @NotNull(message = "El tipo de descuento es obligatorio")
    @Column(name = "discount_type", nullable = false, length = 20)
    private String discountType;

    /**
     * Valor del descuento (porcentaje o monto fijo)
     */
    @NotNull(message = "El valor del descuento es obligatorio")
    @DecimalMin(value = "0.01", message = "El valor debe ser mayor a 0")
    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    /**
     * Compra mínima requerida
     */
    @Column(name = "minimum_purchase", precision = 10, scale = 2)
    private BigDecimal minimumPurchase;

    /**
     * Descuento máximo aplicable (para PERCENTAGE)
     */
    @Column(name = "maximum_discount", precision = 10, scale = 2)
    private BigDecimal maximumDiscount;

    /**
     * Fecha de inicio de validez
     */
    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    /**
     * Fecha de fin de validez
     */
    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    /**
     * Límite de uso total (null = ilimitado)
     */
    @Column(name = "usage_limit")
    private Integer usageLimit;

    /**
     * Límite de uso por usuario (null = ilimitado)
     */
    @Column(name = "usage_limit_per_user")
    private Integer usageLimitPerUser;

    /**
     * Veces que se ha usado
     */
    @Column(name = "times_used", nullable = false)
    private Integer timesUsed = 0;

    /**
     * Indica si está activo
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * Solo para primera compra
     */
    @Column(name = "first_purchase_only", nullable = false)
    private Boolean firstPurchaseOnly = false;

    /**
     * Categorías a las que aplica el cupón.
     * Relación normalizada mediante tabla coupon_applicable_categories.
     * Si está vacío, aplica a todas las categorías.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "coupon_applicable_categories", schema = "sales", joinColumns = @JoinColumn(name = "coupon_id"), inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<Category> applicableCategories = new HashSet<>();

    /**
     * Productos a los que aplica el cupón.
     * Relación normalizada mediante tabla coupon_applicable_products.
     * Si está vacío, aplica a todos los productos.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "coupon_applicable_products", schema = "sales", joinColumns = @JoinColumn(name = "coupon_id"), inverseJoinColumns = @JoinColumn(name = "product_id"))
    private Set<Product> applicableProducts = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Verifica si el cupón está vigente
     */
    public boolean isValid() {
        if (!isActive)
            return false;

        LocalDateTime now = LocalDateTime.now();
        if (validFrom != null && now.isBefore(validFrom))
            return false;
        if (validUntil != null && now.isAfter(validUntil))
            return false;

        if (usageLimit != null && timesUsed >= usageLimit)
            return false;

        return true;
    }

    /**
     * Incrementa el contador de usos
     */
    public void incrementUsage() {
        this.timesUsed++;
        if (usageLimit != null && timesUsed >= usageLimit) {
            this.isActive = false;
        }
    }

    /**
     * Calcula el descuento para un monto dado
     */
    public BigDecimal calculateDiscount(BigDecimal amount) {
        if ("FREE_SHIPPING".equals(discountType)) {
            return BigDecimal.ZERO; // Se maneja en shipping
        }

        BigDecimal discount;
        if ("PERCENTAGE".equals(discountType)) {
            discount = amount.multiply(discountValue).divide(new BigDecimal("100"));
            if (maximumDiscount != null && discount.compareTo(maximumDiscount) > 0) {
                discount = maximumDiscount;
            }
        } else { // FIXED
            discount = discountValue;
            if (discount.compareTo(amount) > 0) {
                discount = amount; // No puede ser mayor al total
            }
        }

        return discount;
    }
}
