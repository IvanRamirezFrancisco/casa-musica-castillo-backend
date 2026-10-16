package com.security.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Proyección de Spring Data JPA para el listado paginado de órdenes de un cluster.
 *
 * <p>
 * Los getters corresponden <strong>exactamente</strong> a los alias definidos en la
 * consulta nativa de {@code PurchaseOrderClusterRepository}. Cualquier diferencia
 * entre el alias SQL y el nombre del método causará que el campo llegue como
 * {@code null} en tiempo de ejecución.
 * </p>
 *
 * <p>
 * El campo {@code weekend} proviene de la columna {@code is_weekend} de la vista
 * analítica. PostgreSQL puede retornarlo como {@code Boolean} o como {@code Integer}
 * (0/1) dependiendo del driver; se declara como {@code Boolean} para consistencia.
 * </p>
 */
public interface PurchaseClusterOrderProjection {

    Long getOrderId();

    String getOrderNumber();

    /** Fecha de creación de la orden. La vista expone TIMESTAMP WITHOUT TIME ZONE. */
    LocalDateTime getCreatedAt();

    BigDecimal getSubtotal();

    BigDecimal getDiscount();

    BigDecimal getShipping();

    BigDecimal getTotal();

    BigDecimal getDiscountPct();

    BigDecimal getShippingPct();

    Integer getTotalUnits();

    Integer getUniqueProducts();

    Integer getUniqueCategories();

    BigDecimal getUnitsPerProduct();

    Integer getAccessoryUnits();

    Integer getInstrumentUnits();

    Integer getViolinUnits();

    Integer getJaranaUnits();

    Integer getQuintaUnits();

    BigDecimal getAccessoryValueShare();

    BigDecimal getInstrumentValueShare();

    BigDecimal getLargestProductValueShare();

    BigDecimal getAverageUnitPrice();

    /** {@code true} si la orden fue realizada en fin de semana. */
    Boolean getWeekend();

    String getPaymentMethod();

    String getDeliveryType();

    Short getClusterNumber();

    String getClusterName();

    String getRecommendedAction();
}
