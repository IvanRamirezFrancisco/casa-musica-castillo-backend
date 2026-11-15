package com.security.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductDTO {

    private Long id;

    @NotBlank(message = "El nombre del producto es obligatorio")
    @Size(min = 3, max = 200)
    private String name;

    private String description;

    @NotNull(message = "El precio es obligatorio")
    @DecimalMin(value = "0.01", message = "El precio debe ser mayor a 0")
    private BigDecimal price;

    private BigDecimal discountPrice;

    @NotNull(message = "El stock es obligatorio")
    @Min(value = 0, message = "El stock no puede ser negativo")
    private Integer stock;

    private String imageUrl;

    /**
     * Galería de imágenes adicionales del producto
     * (Tabla product_images)
     */
    private List<ProductImageDTO> images = new ArrayList<>();

    /**
     * Atributos dinámicos personalizados (Sistema Híbrido)
     * (Tabla product_attributes)
     */
    private List<ProductAttributeDTO> customAttributes = new ArrayList<>();

    /**
     * Descripción detallada con HTML (para editor rico)
     */
    private String detailedDescription;

    // SKU es autogenerado por el backend cuando no viene en el request.
    // Solo el CSV import lo envía explícitamente.
    private String sku;

    @NotNull(message = "La categoría es obligatoria")
    private Long categoryId;

    private String categoryName;

    private Boolean active;
    private Boolean featured;

    // Brand como relación (reemplaza String brand)
    private Long brandId;
    private String brandName;
    private String brandLogoUrl;

    private String model;
    private Double weight;
    private String dimensions;
    private Long views;
    private Long salesCount;
}
