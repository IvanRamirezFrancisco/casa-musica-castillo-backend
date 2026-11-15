package com.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs para gestión de marcas
 */
public class BrandDTO {

    /**
     * DTO para crear/actualizar marca
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrandRequest {
        @NotBlank(message = "El nombre de la marca es obligatorio")
        @Size(min = 2, max = 100, message = "El nombre debe tener entre 2 y 100 caracteres")
        private String name;

        private String description;
        private String logoUrl;
        private String websiteUrl;
        private String countryOrigin;
        private Boolean active;
    }

    /**
     * DTO de respuesta con información de marca
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrandResponse {
        private Long id;
        private String name;
        private String description;
        private String logoUrl;
        private String logoProvider;
        private String logoPublicId;
        private String websiteUrl;
        private String countryOrigin;
        private Boolean active;
        private Long productCount;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /**
     * DTO simplificado para selección (usado en productos)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrandBasicInfo {
        private Long id;
        private String name;
        private String logoUrl;
        private String logoProvider;
        private Boolean active;
    }

    /**
     * Respuesta paginada de marcas
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrandListResponse {
        private List<BrandResponse> brands;
        private Integer totalBrands;
        private Integer currentPage;
        private Integer totalPages;
    }

    /**
     * Estadísticas de marca
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrandStatsResponse {
        private Long brandId;
        private String brandName;
        private Long productCount;
        private Long totalSales;
        private Long totalViews;
    }
}
