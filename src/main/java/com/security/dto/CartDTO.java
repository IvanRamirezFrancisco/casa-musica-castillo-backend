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
 * DTOs para operaciones del carrito de compras
 */
public class CartDTO {

    /**
     * Request para agregar item al carrito
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddItemRequest {

        @NotNull(message = "El ID del producto es obligatorio")
        private Long productId;

        @NotNull(message = "La cantidad es obligatoria")
        @Min(value = 1, message = "La cantidad debe ser mayor a 0")
        @Max(value = 100, message = "La cantidad máxima es 100")
        private Integer quantity;
    }

    /**
     * Request para actualizar cantidad de un item
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateItemRequest {

        @NotNull(message = "El ID del item es obligatorio")
        private Long itemId;

        @NotNull(message = "La cantidad es obligatoria")
        @Min(value = 1, message = "La cantidad debe ser mayor a 0")
        @Max(value = 100, message = "La cantidad máxima es 100")
        private Integer quantity;
    }

    /**
     * Request para aplicar cupón
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApplyCouponRequest {

        @NotBlank(message = "El código del cupón es obligatorio")
        @Size(min = 3, max = 50, message = "El código debe tener entre 3 y 50 caracteres")
        private String couponCode;
    }

    /**
     * Response con item individual del carrito
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartItemResponse {
        private Long itemId;
        private Long productId;
        private String productName;
        private String productImage;
        private String productSku;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;
        private Integer availableStock;
        private Boolean available;
        private String availabilityStatus;
        private String warningMessage;
        private LocalDateTime addedAt;
    }

    /**
     * Response completo del carrito
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartResponse {
        private Long cartId;
        private Long userId;
        private String sessionId;
        private List<CartItemResponse> items;
        private Integer totalItems;
        private BigDecimal subtotal;
        private BigDecimal tax;
        private BigDecimal taxRate;
        private BigDecimal discount;
        private BigDecimal total;
        private String couponCode;
        private String status;
        private Boolean canCheckout;
        private String warningMessage;
        private BigDecimal unavailableItemsTotal;
        private LocalDateTime expiresAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        /**
         * Calcula el total de items en el carrito
         */
        public Integer calculateTotalItems() {
            return items != null ? items.stream()
                    .mapToInt(CartItemResponse::getQuantity)
                    .sum() : 0;
        }
    }

    /**
     * Response con resumen del carrito
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartSummaryResponse {
        private Long cartId;
        private Integer totalItems;
        private BigDecimal subtotal;
        private BigDecimal total;
        private String status;
        private Boolean canCheckout;
        private Integer unavailableItemsCount;
        private String warningMessage;
    }

    /**
     * Response al aplicar cupón
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CouponAppliedResponse {
        private String couponCode;
        private String discountType;
        private BigDecimal discountValue;
        private BigDecimal discountApplied;
        private BigDecimal newTotal;
        private String message;
    }

    /**
     * Response para validación del carrito
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartValidationResponse {
        private boolean valid;
        private boolean canCheckout;
        private List<String> globalErrors;
        private List<CartItemValidation> itemWarnings;
    }

    /**
     * Warning específico para un item en validación
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartItemValidation {
        private Long itemId;
        private Long productId;
        private String availabilityStatus;
        private String warningMessage;
    }
}
