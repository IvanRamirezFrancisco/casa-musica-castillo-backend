package com.security.dto.public_api;

import java.math.BigDecimal;

/**
 * DTO público para recomendaciones de productos.
 * Contiene únicamente información comercial segura de exponer.
 */
public record PublicProductRecommendationDTO(
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
        Integer stock,
        Boolean hasDiscount,
        String recommendationReason
) {
}
