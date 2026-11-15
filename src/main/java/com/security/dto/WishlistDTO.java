package com.security.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs para operaciones de wishlist
 */
public class WishlistDTO {

    /**
     * Request para agregar a wishlist
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddToWishlistRequest {

        @NotNull(message = "El ID del producto es obligatorio")
        private Long productId;

        @Min(value = 1, message = "La prioridad mínima es 1")
        @Max(value = 3, message = "La prioridad máxima es 3")
        private Integer priority; // 1=LOW, 2=MEDIUM, 3=HIGH

        @Size(max = 500, message = "Las notas no pueden exceder 500 caracteres")
        private String notes;
    }

    /**
     * Request para actualizar item de wishlist
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateWishlistRequest {

        @Min(value = 1, message = "La prioridad mínima es 1")
        @Max(value = 3, message = "La prioridad máxima es 3")
        private Integer priority;

        @Size(max = 500, message = "Las notas no pueden exceder 500 caracteres")
        private String notes;
    }

    /**
     * Response de item de wishlist
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WishlistItemResponse {
        private Long wishlistId;
        private Long productId;
        private String productName;
        private String productImage;
        private String productSku;
        private BigDecimal currentPrice;
        private BigDecimal priceWhenAdded;
        private BigDecimal priceDifference;
        private Double discountPercentage;
        private Boolean priceDropped;
        private Integer availableStock;
        private Boolean inStock;
        private Boolean available;
        private String availabilityStatus;
        private Boolean canMoveToCart;
        private Integer priority;
        private String priorityLabel; // LOW, MEDIUM, HIGH
        private String notes;
        private Boolean notifiedBackInStock;
        private Boolean notifiedDiscount;
        private LocalDateTime addedAt;
        private LocalDateTime updatedAt;
    }

    /**
     * Response con lista de wishlist
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WishlistResponse {
        private List<WishlistItemResponse> items;
        private Integer totalItems;
        private Integer highPriorityItems;
        private Integer outOfStockItems;
        private Integer priceDroppedItems;
    }

    /**
     * Response de comparación de precios
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceComparisonResponse {
        private Long productId;
        private String productName;
        private BigDecimal originalPrice;
        private BigDecimal currentPrice;
        private BigDecimal savings;
        private Double discountPercentage;
        private LocalDateTime priceChangedAt;
    }

    /**
     * Resumen de wishlist
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WishlistSummaryResponse {
        private Integer totalItems;
        private Integer highPriorityCount;
        private Integer mediumPriorityCount;
        private Integer lowPriorityCount;
        private Integer inStockCount;
        private Integer outOfStockCount;
        private Integer priceDroppedCount;
        private BigDecimal totalValue;
        private BigDecimal potentialSavings;
    }

    /**
     * Notificaciones de wishlist
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WishlistNotification {
        private Long wishlistId;
        private Long productId;
        private String productName;
        private String notificationType; // BACK_IN_STOCK, PRICE_DROP
        private String message;
        private BigDecimal currentPrice;
        private BigDecimal previousPrice;
        private Double discountPercentage;
        private LocalDateTime notifiedAt;
    }

    /**
     * Respuesta ligera para verificar si un producto está en la wishlist.
     * Usada por GET /api/wishlist/check/{productId}
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CheckResponse {
        /** true si el producto está en la wishlist del usuario */
        private boolean inWishlist;
        /** ID del registro de wishlist, o null si no está */
        private Long wishlistId;
    }
}
