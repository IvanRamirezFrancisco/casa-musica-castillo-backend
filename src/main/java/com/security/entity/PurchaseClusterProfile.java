package com.security.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Entidad JPA de solo lectura que mapea {@code analytics.purchase_cluster_profiles}.
 *
 * <p>
 * Cada registro describe las características estadísticas de un cluster dentro de
 * una ejecución del modelo K-Means. Incluye métricas de comportamiento de compra
 * y recomendaciones de acción comercial.
 * </p>
 *
 * <p>
 * La combinación {@code run_id + cluster_number} es única en la base de datos.
 * </p>
 */
@Entity
@Immutable
@Table(name = "purchase_cluster_profiles", schema = "analytics")
@Getter
@NoArgsConstructor
public class PurchaseClusterProfile {

    @Id
    private Long id;

    /** Ejecución del modelo a la que pertenece este perfil. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private PurchaseClusteringRun run;

    @Column(name = "cluster_number", nullable = false)
    private Short clusterNumber;

    @Column(name = "cluster_name", nullable = false, length = 150)
    private String clusterName;

    @Column(name = "cluster_description", columnDefinition = "TEXT")
    private String clusterDescription;

    @Column(name = "order_count", nullable = false)
    private Integer orderCount;

    @Column(name = "percentage", nullable = false, precision = 7, scale = 4)
    private BigDecimal percentage;

    @Column(name = "average_total", precision = 14, scale = 2)
    private BigDecimal averageTotal;

    @Column(name = "average_discount_pct", precision = 10, scale = 4)
    private BigDecimal averageDiscountPct;

    @Column(name = "average_shipping_pct", precision = 10, scale = 4)
    private BigDecimal averageShippingPct;

    @Column(name = "average_unit_price", precision = 14, scale = 2)
    private BigDecimal averageUnitPrice;

    @Column(name = "average_total_units", precision = 10, scale = 4)
    private BigDecimal averageTotalUnits;

    @Column(name = "average_unique_products", precision = 10, scale = 4)
    private BigDecimal averageUniqueProducts;

    @Column(name = "average_unique_categories", precision = 10, scale = 4)
    private BigDecimal averageUniqueCategories;

    @Column(name = "average_units_per_product", precision = 10, scale = 4)
    private BigDecimal averageUnitsPerProduct;

    @Column(name = "average_accessory_units", precision = 10, scale = 4)
    private BigDecimal averageAccessoryUnits;

    @Column(name = "average_instrument_units", precision = 10, scale = 4)
    private BigDecimal averageInstrumentUnits;

    @Column(name = "average_violin_units", precision = 10, scale = 4)
    private BigDecimal averageViolinUnits;

    @Column(name = "average_jarana_units", precision = 10, scale = 4)
    private BigDecimal averageJaranaUnits;

    @Column(name = "average_quinta_units", precision = 10, scale = 4)
    private BigDecimal averageQuintaUnits;

    @Column(name = "average_accessory_value_share", precision = 10, scale = 6)
    private BigDecimal averageAccessoryValueShare;

    @Column(name = "average_instrument_value_share", precision = 10, scale = 6)
    private BigDecimal averageInstrumentValueShare;

    @Column(name = "average_largest_product_value_share", precision = 10, scale = 6)
    private BigDecimal averageLargestProductValueShare;

    @Column(name = "weekend_percentage", precision = 7, scale = 4)
    private BigDecimal weekendPercentage;

    @Column(name = "dominant_payment_method", length = 60)
    private String dominantPaymentMethod;

    @Column(name = "dominant_delivery_type", length = 80)
    private String dominantDeliveryType;

    @Column(name = "recommended_action", nullable = false, columnDefinition = "TEXT")
    private String recommendedAction;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
