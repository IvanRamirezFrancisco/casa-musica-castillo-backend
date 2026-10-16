package com.security.repository;

import com.security.entity.PurchaseOrderCluster;
import com.security.repository.projection.PurchaseClusterOrderProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio JPA para {@link PurchaseOrderCluster}.
 *
 * <p>
 * El método de listado paginado usa una <strong>consulta nativa</strong> que
 * combina las tres estructuras analíticas mediante JOIN:
 * <ul>
 *   <li>{@code analytics.purchase_order_clusters}</li>
 *   <li>{@code analytics.purchase_cluster_profiles} — para obtener clusterName y recommendedAction</li>
 *   <li>{@code analytics.vw_clustering_tipos_compra_v2} — para todos los campos de la orden</li>
 * </ul>
 * </p>
 *
 * <p>
 * El ordenamiento es fijo ({@code created_at DESC, order_id DESC}) para evitar
 * inyección de SQL por parámetros de ordenamiento enviados desde el cliente.
 * </p>
 */
public interface PurchaseOrderClusterRepository extends JpaRepository<PurchaseOrderCluster, Long> {

    /**
     * Cuenta las asignaciones totales de una ejecución.
     *
     * @param runId identificador de la ejecución
     * @return número de órdenes asignadas en esa ejecución
     */
    long countByRunId(Long runId);

    /**
     * Cuenta las asignaciones de un cluster específico dentro de una ejecución.
     *
     * @param runId         identificador de la ejecución
     * @param clusterNumber número del cluster
     * @return número de órdenes en ese cluster
     */
    long countByRunIdAndClusterNumber(Long runId, Short clusterNumber);

    /**
     * Retorna una página de órdenes enriquecidas de un cluster, uniendo los datos
     * de la vista analítica y el perfil del cluster.
     *
     * <p>
     * Los alias de la proyección ({@code orderId}, {@code orderNumber}, etc.) deben
     * coincidir <strong>exactamente</strong> con los getters de
     * {@link PurchaseClusterOrderProjection}.
     * </p>
     *
     * @param runId         identificador de la ejecución activa
     * @param clusterNumber número del cluster que se quiere listar
     * @param pageable      parámetros de paginación (solo page y size; sort se ignora)
     * @return página de proyecciones tipadas
     */
    @Query(
        value = """
            SELECT
                oc.order_id            AS orderId,
                v.order_number         AS orderNumber,
                v.created_at           AS createdAt,
                v.subtotal             AS subtotal,
                v.discount             AS discount,
                v.shipping             AS shipping,
                v.total                AS total,
                v.discount_pct         AS discountPct,
                v.shipping_pct         AS shippingPct,
                v.total_units          AS totalUnits,
                v.unique_products      AS uniqueProducts,
                v.unique_categories    AS uniqueCategories,
                v.units_per_product    AS unitsPerProduct,
                v.accessory_units      AS accessoryUnits,
                v.instrument_units     AS instrumentUnits,
                v.violin_units         AS violinUnits,
                v.jarana_units         AS jaranaUnits,
                v.quinta_units         AS quintaUnits,
                v.accessory_value_share         AS accessoryValueShare,
                v.instrument_value_share        AS instrumentValueShare,
                v.largest_product_value_share   AS largestProductValueShare,
                v.avg_unit_price       AS averageUnitPrice,
                CASE
                    WHEN v.is_weekend = 1 THEN TRUE
                    ELSE FALSE
                END AS weekend,
                v.payment_method       AS paymentMethod,
                v.delivery_type        AS deliveryType,
                oc.cluster_number      AS clusterNumber,
                p.cluster_name         AS clusterName,
                p.recommended_action   AS recommendedAction
            FROM analytics.purchase_order_clusters oc
            JOIN analytics.purchase_cluster_profiles p
                ON  p.run_id         = oc.run_id
                AND p.cluster_number = oc.cluster_number
            JOIN analytics.vw_clustering_tipos_compra_v2 v
                ON  v.order_id = oc.order_id
            WHERE oc.run_id         = :runId
              AND oc.cluster_number = :clusterNumber
            ORDER BY v.created_at DESC, oc.order_id DESC
            """,
        countQuery = """
            SELECT COUNT(*)
            FROM analytics.purchase_order_clusters oc
            WHERE oc.run_id         = :runId
              AND oc.cluster_number = :clusterNumber
            """,
        nativeQuery = true
    )
    Page<PurchaseClusterOrderProjection> findOrdersByRunIdAndClusterNumber(
            @Param("runId") Long runId,
            @Param("clusterNumber") Short clusterNumber,
            Pageable pageable
    );

    /**
     * Obtiene las características analíticas de una orden desde la vista.
     * Utilizado para predicción en tiempo real.
     */
    @Query(
        value = """
            SELECT
                v.order_id            AS orderId,
                v.order_number        AS orderNumber,
                v.total               AS total,
                v.discount_pct        AS discountPct,
                v.shipping_pct        AS shippingPct,
                v.total_units         AS totalUnits,
                v.unique_products     AS uniqueProducts,
                v.unique_categories   AS uniqueCategories,
                v.units_per_product   AS unitsPerProduct,
                v.accessory_value_share       AS accessoryValueShare,
                v.largest_product_value_share AS largestProductValueShare,
                v.avg_unit_price      AS averageUnitPrice
            FROM analytics.vw_clustering_order_features_live_v1 v
            WHERE v.order_id = :orderId
            """,
        nativeQuery = true
    )
    java.util.Optional<com.security.repository.projection.PurchaseOrderFeatureProjection> findOrderFeaturesById(@Param("orderId") Long orderId);

    /**
     * Busca la asignación de un cluster para una orden en una ejecución específica.
     */
    java.util.Optional<PurchaseOrderCluster> findByRunIdAndOrderId(Long runId, Long orderId);
}
