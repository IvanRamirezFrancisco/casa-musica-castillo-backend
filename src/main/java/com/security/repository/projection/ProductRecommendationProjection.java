package com.security.repository.projection;

import java.math.BigDecimal;

/**
 * Interface Projection para mapear los resultados directos de la vista
 * analytics.vw_active_product_recommendations.
 */
public interface ProductRecommendationProjection {
    Long getProductId();
    String getSku();
    String getName();
    String getDescription();
    String getImageUrl();
    Long getCategoryId();
    String getCategoryName();
    BigDecimal getRegularPrice();
    BigDecimal getEffectivePrice();
    BigDecimal getDiscountPrice();
    Integer getStock();
    Boolean getHasDiscount();
}
