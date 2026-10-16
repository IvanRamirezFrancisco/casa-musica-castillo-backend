package com.security.dto.admin.analytics;

import java.math.BigDecimal;

/**
 * DTO con el perfil estadístico de un cluster de tipos de compra.
 *
 * <p>
 * Expone todas las métricas de comportamiento almacenadas en
 * {@code analytics.purchase_cluster_profiles} de forma tipada y desacoplada
 * de la entidad JPA.
 * </p>
 *
 * @param clusterNumber                   Número del cluster (0-based).
 * @param clusterName                     Nombre descriptivo del cluster.
 * @param clusterDescription              Descripción detallada del cluster.
 * @param orderCount                      Total de órdenes asignadas al cluster.
 * @param percentage                      Porcentaje del total de órdenes analizadas.
 * @param averageTotal                    Valor monetario promedio de la orden.
 * @param averageDiscountPct              Porcentaje de descuento promedio.
 * @param averageShippingPct              Porcentaje de envío promedio.
 * @param averageUnitPrice                Precio unitario promedio.
 * @param averageTotalUnits               Número promedio de unidades por orden.
 * @param averageUniqueProducts           Promedio de productos distintos por orden.
 * @param averageUniqueCategories         Promedio de categorías distintas por orden.
 * @param averageUnitsPerProduct          Promedio de unidades por tipo de producto.
 * @param averageAccessoryUnits           Promedio de unidades de accesorios.
 * @param averageInstrumentUnits          Promedio de unidades de instrumentos.
 * @param averageViolinUnits              Promedio de unidades de violines.
 * @param averageJaranaUnits              Promedio de unidades de jaranas.
 * @param averageQuintaUnits              Promedio de unidades de quintas.
 * @param averageAccessoryValueShare      Participación promedio del valor de accesorios.
 * @param averageInstrumentValueShare     Participación promedio del valor de instrumentos.
 * @param averageLargestProductValueShare Participación del producto de mayor valor.
 * @param weekendPercentage               Porcentaje de órdenes en fin de semana.
 * @param dominantPaymentMethod           Método de pago más frecuente en el cluster.
 * @param dominantDeliveryType            Tipo de entrega más frecuente en el cluster.
 * @param recommendedAction               Acción comercial recomendada para el cluster.
 */
public record PurchaseClusterProfileDTO(
        Short clusterNumber,
        String clusterName,
        String clusterDescription,
        Integer orderCount,
        BigDecimal percentage,
        BigDecimal averageTotal,
        BigDecimal averageDiscountPct,
        BigDecimal averageShippingPct,
        BigDecimal averageUnitPrice,
        BigDecimal averageTotalUnits,
        BigDecimal averageUniqueProducts,
        BigDecimal averageUniqueCategories,
        BigDecimal averageUnitsPerProduct,
        BigDecimal averageAccessoryUnits,
        BigDecimal averageInstrumentUnits,
        BigDecimal averageViolinUnits,
        BigDecimal averageJaranaUnits,
        BigDecimal averageQuintaUnits,
        BigDecimal averageAccessoryValueShare,
        BigDecimal averageInstrumentValueShare,
        BigDecimal averageLargestProductValueShare,
        BigDecimal weekendPercentage,
        String dominantPaymentMethod,
        String dominantDeliveryType,
        String recommendedAction
) {
}
