package com.security.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Entidad JPA de solo lectura que mapea {@code analytics.purchase_clustering_runs}.
 *
 * <p>
 * Representa una ejecución completa del modelo K-Means de clustering de tipos de compra.
 * La tabla es gestionada exclusivamente por el notebook Jupyter; este backend solo la lee.
 * </p>
 *
 * <p>
 * Se marca con {@link Immutable} para que Hibernate 6 nunca intente persistir cambios
 * accidentales sobre los datos analíticos.
 * </p>
 */
@Entity
@Immutable
@Table(name = "purchase_clustering_runs", schema = "analytics")
@Getter
@NoArgsConstructor
public class PurchaseClusteringRun {

    @Id
    private Long id;

    @Column(name = "model_version", nullable = false, length = 100)
    private String modelVersion;

    @Column(name = "algorithm", nullable = false, length = 40)
    private String algorithm;

    @Column(name = "selected_k", nullable = false)
    private Short selectedK;

    @Column(name = "orders_analyzed", nullable = false)
    private Integer ordersAnalyzed;

    @Column(name = "feature_count", nullable = false)
    private Short featureCount;

    @Column(name = "dataset_view", nullable = false, length = 160)
    private String datasetView;

    @Column(name = "data_origin", nullable = false, length = 40)
    private String dataOrigin;

    /** Inicio del período analizado (TIMESTAMP WITHOUT TIME ZONE). */
    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    /** Fin del período analizado (TIMESTAMP WITHOUT TIME ZONE). */
    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    @Column(name = "inertia", precision = 20, scale = 8)
    private BigDecimal inertia;

    @Column(name = "silhouette_score", precision = 12, scale = 8)
    private BigDecimal silhouetteScore;

    @Column(name = "davies_bouldin_score", precision = 12, scale = 8)
    private BigDecimal daviesBouldinScore;

    @Column(name = "calinski_harabasz_score", precision = 20, scale = 8)
    private BigDecimal calinskiHarabaszScore;

    /**
     * Nombres de las características usadas en el modelo (columna JSONB).
     * Hibernate 6 deserializa automáticamente a {@code List<String>}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_names", nullable = false, columnDefinition = "jsonb")
    private List<String> featureNames;

    /**
     * Configuración de preprocesamiento del modelo (columna JSONB).
     * Hibernate 6 deserializa automáticamente a {@code Map<String, Object>}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preprocessing_config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> preprocessingConfig;

    @Column(name = "notebook_name", length = 200)
    private String notebookName;

    /** Estado de la ejecución: COMPLETED, RUNNING, FAILED, etc. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** Indica si esta ejecución es la activa para consultas del módulo. */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** Fecha de ejecución del modelo (TIMESTAMP WITH TIME ZONE). */
    @Column(name = "executed_at", nullable = false)
    private OffsetDateTime executedAt;

    /** Fecha de registro en la base de datos (TIMESTAMP WITH TIME ZONE). */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
