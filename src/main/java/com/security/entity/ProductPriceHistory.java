package com.security.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidad para historial de cambios de precios de productos
 * Tabla: product_price_history
 */
@Entity
@Table(name = "product_price_history", schema = "catalog", indexes = {
        @Index(name = "idx_price_history_product", columnList = "product_id, effective_from"),
        @Index(name = "idx_price_history_dates", columnList = "effective_from, effective_to"),
        @Index(name = "idx_price_history_user", columnList = "changed_by")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductPriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "old_price", precision = 10, scale = 2)
    private BigDecimal oldPrice; // Precio anterior

    @Column(name = "new_price", precision = 10, scale = 2, nullable = false)
    private BigDecimal newPrice; // Precio nuevo

    @Column(name = "old_discount_price", precision = 10, scale = 2)
    private BigDecimal oldDiscountPrice; // Precio con descuento anterior

    @Column(name = "new_discount_price", precision = 10, scale = 2)
    private BigDecimal newDiscountPrice; // Precio con descuento nuevo

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by")
    private User changedBy; // Usuario que realizó el cambio

    @Column(name = "reason", length = 200)
    private String reason; // Razón del cambio (ej: "Black Friday", "Liquidación")

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom; // Desde cuándo aplica

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo; // Hasta cuándo aplicó (NULL = vigente)

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.effectiveFrom == null) {
            this.effectiveFrom = LocalDateTime.now();
        }
    }

    /**
     * Verifica si este precio está actualmente vigente
     */
    public boolean isCurrentlyActive() {
        return this.effectiveTo == null;
    }

    /**
     * Cierra la vigencia de este precio
     */
    public void closeValidity() {
        this.effectiveTo = LocalDateTime.now();
    }
}
