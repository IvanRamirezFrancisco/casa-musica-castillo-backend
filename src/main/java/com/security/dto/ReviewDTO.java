package com.security.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs para operaciones de reseñas
 */
public class ReviewDTO {

    /**
     * Request para crear reseña
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateReviewRequest {

        @NotNull(message = "El ID del producto es obligatorio")
        private Long productId;

        @NotNull(message = "La calificación es obligatoria")
        @Min(value = 1, message = "La calificación mínima es 1")
        @Max(value = 5, message = "La calificación máxima es 5")
        private Integer rating;

        @Size(max = 120, message = "El título no puede exceder 120 caracteres")
        private String title;

        @NotBlank(message = "El comentario es obligatorio")
        @Size(min = 10, max = 1000, message = "El comentario debe tener entre 10 y 1000 caracteres")
        private String comment;

        private Long orderId; // Opcional para verificar compra
    }

    /**
     * Request para actualizar reseña
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateReviewRequest {

        @NotNull(message = "La calificación es obligatoria")
        @Min(value = 1, message = "La calificación mínima es 1")
        @Max(value = 5, message = "La calificación máxima es 5")
        private Integer rating;

        @Size(max = 120, message = "El título no puede exceder 120 caracteres")
        private String title;

        @NotBlank(message = "El comentario es obligatorio")
        @Size(min = 10, max = 1000, message = "El comentario debe tener entre 10 y 1000 caracteres")
        private String comment;
    }

    /**
     * Request para respuesta del vendedor
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SellerResponseRequest {

        @NotBlank(message = "La respuesta es obligatoria")
        @Size(min = 10, max = 1000, message = "La respuesta debe tener entre 10 y 1000 caracteres")
        private String response;
    }

    /**
     * Request para votar por utilidad de reseña
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VoteHelpfulRequest {

        @NotNull(message = "El voto es obligatorio")
        private Boolean isHelpful; // true = útil, false = no útil
    }

    /**
     * Response de reseña individual
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewResponse {
        private Long reviewId;
        private Long productId;
        private String productName;
        private Long userId;
        private String userName;
        private Integer rating;
        private String title;
        private String comment;
        private Boolean verifiedPurchase;
        private String status;
        private Integer helpfulCount;
        private Integer notHelpfulCount;
        private String sellerResponse;
        private LocalDateTime sellerResponseAt;
        private LocalDateTime createdAt;
        private Boolean currentUserVoted; // Si el usuario actual ya votó
        private Boolean currentUserVoteHelpful; // Cómo votó (null si no votó)
    }

    /**
     * Response con lista de reseñas paginadas
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewListResponse {
        private List<ReviewResponse> reviews;
        private Integer totalReviews;
        private Integer currentPage;
        private Integer totalPages;
        private RatingStatistics statistics;
    }

    /**
     * Estadísticas de calificaciones
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RatingStatistics {
        private Double averageRating;
        private Integer totalReviews;
        private Integer fiveStarCount;
        private Integer fourStarCount;
        private Integer threeStarCount;
        private Integer twoStarCount;
        private Integer oneStarCount;
        private Double fiveStarPercentage;
        private Double fourStarPercentage;
        private Double threeStarPercentage;
        private Double twoStarPercentage;
        private Double oneStarPercentage;
    }

    /**
     * Filtros para buscar reseñas
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewFilters {
        private Long productId;
        private Long userId;
        private Integer rating;
        private Boolean verifiedOnly;
        private String status;
        private String sortBy; // RECENT, HELPFUL, RATING_DESC, RATING_ASC
        private Integer page;
        private Integer size;
    }

    /**
     * Respuesta de elegibilidad para reseñar
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EligibilityResponse {
        private Boolean canReview;
        private Boolean hasPurchased;
        private Boolean alreadyReviewed;
        private Long reviewId;
        private String reviewStatus;
    }
}
