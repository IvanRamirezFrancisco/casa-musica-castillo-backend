package com.security.dto.public_api;

/**
 * DTO inmutable para el estado individual de tendencia de un producto.
 */
public record ProductTrendStatusDTO(
        Long productId,
        boolean trending,
        String label,
        String reason
) {
}
