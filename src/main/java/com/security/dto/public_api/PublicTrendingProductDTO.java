package com.security.dto.public_api;

import java.math.BigDecimal;

/**
 * DTO público y seguro para productos en tendencia.
 * No expone información analítica interna, como métricas de entrenamiento,
 * pronósticos decimales, percentiles de prioridad o reposición sugerida.
 */
public record PublicTrendingProductDTO(
        Long productId,
        String sku,
        String name,
        String description,
        String imageUrl,
        Long categoryId,
        String categoryName,
        BigDecimal regularPrice,
        BigDecimal effectivePrice,
        BigDecimal discountPrice,
        boolean hasDiscount,
        String trendLabel,
        String trendReason
) {
}
