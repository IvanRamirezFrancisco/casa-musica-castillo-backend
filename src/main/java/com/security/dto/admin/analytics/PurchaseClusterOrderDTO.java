package com.security.dto.admin.analytics;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO que representa una orden individual dentro de un cluster de tipos de compra.
 *
 * <p>
 * Los campos provienen del JOIN entre {@code analytics.purchase_order_clusters},
 * {@code analytics.vw_clustering_tipos_compra_v2} y
 * {@code analytics.purchase_cluster_profiles}, mapeados a través de
 * {@link com.security.repository.projection.PurchaseClusterOrderProjection}.
 * </p>
 *
 * @param orderId                      Identificador de la orden en el sistema de ventas.
 * @param orderNumber                  Número de orden legible (ej. ORD-001).
 * @param createdAt                    Fecha y hora de creación de la orden.
 * @param subtotal                     Subtotal de la orden.
 * @param discount                     Descuento aplicado.
 * @param shipping                     Costo de envío.
 * @param total                        Total final pagado.
 * @param discountPct                  Porcentaje de descuento respecto al subtotal.
 * @param shippingPct                  Porcentaje de envío respecto al subtotal.
 * @param totalUnits                   Total de unidades en la orden.
 * @param uniqueProducts               Número de productos distintos.
 * @param uniqueCategories             Número de categorías distintas.
 * @param unitsPerProduct              Promedio de unidades por producto.
 * @param accessoryUnits               Unidades de accesorios.
 * @param instrumentUnits              Unidades de instrumentos.
 * @param violinUnits                  Unidades de violines.
 * @param jaranaUnits                  Unidades de jaranas.
 * @param quintaUnits                  Unidades de quintas.
 * @param accessoryValueShare          Participación en valor de accesorios.
 * @param instrumentValueShare         Participación en valor de instrumentos.
 * @param largestProductValueShare     Participación del producto de mayor valor.
 * @param averageUnitPrice             Precio unitario promedio de la orden.
 * @param weekend                      {@code true} si la orden se realizó en fin de semana.
 * @param paymentMethod                Método de pago utilizado.
 * @param deliveryType                 Tipo de entrega elegida.
 * @param clusterNumber                Número del cluster asignado.
 * @param clusterName                  Nombre del cluster.
 * @param recommendedAction            Acción comercial recomendada para este cluster.
 */
public record PurchaseClusterOrderDTO(
        Long orderId,
        String orderNumber,
        LocalDateTime createdAt,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal shipping,
        BigDecimal total,
        BigDecimal discountPct,
        BigDecimal shippingPct,
        Integer totalUnits,
        Integer uniqueProducts,
        Integer uniqueCategories,
        BigDecimal unitsPerProduct,
        Integer accessoryUnits,
        Integer instrumentUnits,
        Integer violinUnits,
        Integer jaranaUnits,
        Integer quintaUnits,
        BigDecimal accessoryValueShare,
        BigDecimal instrumentValueShare,
        BigDecimal largestProductValueShare,
        BigDecimal averageUnitPrice,
        Boolean weekend,
        String paymentMethod,
        String deliveryType,
        Short clusterNumber,
        String clusterName,
        String recommendedAction
) {
}
