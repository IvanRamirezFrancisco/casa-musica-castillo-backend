package com.security.dto.admin.analytics;

import java.math.BigDecimal;

/**
 * Resultado de la verificación de integridad entre la ejecución activa,
 * los perfiles registrados y las asignaciones de órdenes.
 *
 * @param expectedClusters    Número de clusters esperados (igual a {@code selectedK}).
 * @param actualProfiles      Cantidad real de perfiles registrados para la ejecución.
 * @param expectedOrders      Número de órdenes analizadas según la ejecución.
 * @param assignedOrders      Número real de asignaciones en {@code purchase_order_clusters}.
 * @param profileOrdersSum    Suma de {@code order_count} de todos los perfiles.
 * @param profilePercentageSum Suma de {@code percentage} de todos los perfiles.
 * @param consistent          {@code true} solo cuando todas las comprobaciones pasan.
 */
public record PurchaseClusteringIntegrityDTO(
        int expectedClusters,
        long actualProfiles,
        int expectedOrders,
        long assignedOrders,
        long profileOrdersSum,
        BigDecimal profilePercentageSum,
        boolean consistent
) {
}
