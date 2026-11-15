package com.security.repository.projection;

import java.math.BigDecimal;

public interface PurchaseOrderFeatureProjection {
    Long getOrderId();
    String getOrderNumber();
    BigDecimal getTotal();
    BigDecimal getDiscountPct();
    BigDecimal getShippingPct();
    Integer getTotalUnits();
    Integer getUniqueProducts();
    Integer getUniqueCategories();
    BigDecimal getUnitsPerProduct();
    BigDecimal getAccessoryValueShare();
    BigDecimal getLargestProductValueShare();
    BigDecimal getAverageUnitPrice();
}
