package com.security.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Entidad JPA de solo lectura que mapea {@code analytics.purchase_order_clusters}.
 *
 * <p>
 * Relaciona cada orden original del sistema con el cluster que el modelo K-Means
 * le asignó en una ejecución determinada. La combinación {@code run_id + order_id}
 * es única.
 * </p>
 *
 * <p>
 * Para las consultas enriquecidas del listado administrativo se usa una consulta
 * nativa con JOIN a {@code analytics.vw_clustering_tipos_compra_v2}, por lo que
 * no se mapea una relación JPA compleja a {@link PurchaseClusterProfile} mediante
 * clave compuesta.
 * </p>
 */
@Entity
@Immutable
@Table(name = "purchase_order_clusters", schema = "analytics")
@Getter
@NoArgsConstructor
public class PurchaseOrderCluster {

    @Id
    private Long id;

    /** Ejecución del modelo a la que pertenece esta asignación. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private PurchaseClusteringRun run;

    /**
     * Orden del sistema de ventas asignada a un cluster.
     * Se mapea con LAZY para no cargar la entidad completa al paginar.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Número de cluster (0-based) al que se asignó la orden. */
    @Column(name = "cluster_number", nullable = false)
    private Short clusterNumber;

    /** Distancia euclídea al centroide del cluster. */
    @Column(name = "distance_to_centroid", precision = 20, scale = 10)
    private BigDecimal distanceToCentroid;

    /** Primera componente PCA (para visualizaciones 2D). */
    @Column(name = "pca_component_1", precision = 20, scale = 10)
    private BigDecimal pcaComponent1;

    /** Segunda componente PCA (para visualizaciones 2D). */
    @Column(name = "pca_component_2", precision = 20, scale = 10)
    private BigDecimal pcaComponent2;

    @Column(name = "assignment_method", nullable = false, length = 20)
    private String assignmentMethod;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;
}
