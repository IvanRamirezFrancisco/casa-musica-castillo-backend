package com.security.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Entidad para productos musicales
 */
@Entity
@Table(name = "products", schema = "catalog")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "El nombre del producto es obligatorio")
    @Size(min = 3, max = 200, message = "El nombre debe tener entre 3 y 200 caracteres")
    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @NotNull(message = "El precio es obligatorio")
    @DecimalMin(value = "0.01", message = "El precio debe ser mayor a 0")
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "discount_price", precision = 10, scale = 2)
    private BigDecimal discountPrice;

    @NotNull(message = "El stock es obligatorio")
    @Min(value = 0, message = "El stock no puede ser negativo")
    @Column(nullable = false)
    private Integer stock = 0;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @NotBlank(message = "El SKU es obligatorio")
    @Column(nullable = false, unique = true, length = 50)
    private String sku;

    /**
     * Categoría del producto.
     *
     * <p>
     * Puede ser {@code null} únicamente para productos en estado <em>Borrador</em>
     * ({@code active = false}) creados vía importación CSV cuando la categoría no
     * está disponible en el sistema. El administrador debe asignarla antes de
     * activar
     * el producto desde el panel.
     * </p>
     *
     * <p>
     * La validación de negocio (categoría obligatoria para productos activos) se
     * aplica en la capa del servicio y en el DTO del controlador REST
     * ({@code @NotNull
     * categoryId} en {@link com.security.dto.ProductDTO}), no aquí, para permitir
     * el guardado de borradores.
     * </p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = true)
    private Category category;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(nullable = false)
    private Boolean featured = false;

    /**
     * Marca del producto. Siempre opcional — un producto puede no tener marca
     * asignada.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = true)
    private Brand brand;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "weight")
    private Double weight; // en kg

    @Column(name = "dimensions", length = 100)
    private String dimensions; // ej: "30x40x15 cm"

    @Column(name = "views")
    private Long views = 0L;

    @Column(name = "sales_count")
    private Long salesCount = 0L;

    // ============ COLUMNAS DE RATING (FASE 2) ============

    /**
     * Calificación promedio (1.00 - 5.00)
     */
    @Column(name = "average_rating", precision = 3, scale = 2, nullable = false)
    private BigDecimal averageRating = BigDecimal.ZERO;

    /**
     * Total de reseñas
     */
    @Column(name = "review_count", nullable = false)
    private Integer reviewCount = 0;

    /**
     * Reseñas de 5 estrellas
     */
    @Column(name = "five_star_count", nullable = false)
    private Integer fiveStarCount = 0;

    /**
     * Reseñas de 4 estrellas
     */
    @Column(name = "four_star_count", nullable = false)
    private Integer fourStarCount = 0;

    /**
     * Reseñas de 3 estrellas
     */
    @Column(name = "three_star_count", nullable = false)
    private Integer threeStarCount = 0;

    /**
     * Reseñas de 2 estrellas
     */
    @Column(name = "two_star_count", nullable = false)
    private Integer twoStarCount = 0;

    /**
     * Reseñas de 1 estrella
     */
    @Column(name = "one_star_count", nullable = false)
    private Integer oneStarCount = 0;

    /**
     * Galería de imágenes adicionales del producto.
     *
     * <p>
     * <strong>Por qué {@code Set} y no {@code List}:</strong> Hibernate lanza
     * {@code MultipleBagFetchException} cuando se intentan cargar simultáneamente
     * dos o más colecciones de tipo {@code List} (bags) con {@code JOIN FETCH}.
     * Al usar {@code Set} (backed por {@code LinkedHashSet} para preservar el
     * orden de inserción), Hibernate puede hacer el fetch de ambas colecciones
     * en la misma query sin el error. La ordenación se delega a {@code @OrderBy}
     * que Hibernate resuelve en SQL.
     * </p>
     */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    @org.hibernate.annotations.BatchSize(size = 50)
    private Set<ProductImage> images = new LinkedHashSet<>();

    /**
     * Atributos dinámicos personalizados (Sistema Híbrido).
     *
     * <p>
     * Mismo razonamiento que {@code images}: {@code Set} evita
     * {@code MultipleBagFetchException} al hacer fetch conjunto con imágenes.
     * </p>
     */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    @org.hibernate.annotations.BatchSize(size = 50)
    private Set<ProductAttribute> customAttributes = new LinkedHashSet<>();

    /**
     * Descripción detallada con HTML (para editor rico)
     */
    @Column(name = "detailed_description", columnDefinition = "TEXT")
    private String detailedDescription;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ============ MÉTODOS HELPER (BIDIRECCIONAL) ============

    public void addImage(ProductImage image) {
        if (this.images == null) {
            this.images = new LinkedHashSet<>();
        }
        this.images.add(image);
        image.setProduct(this);
    }

    public void removeImage(ProductImage image) {
        if (this.images != null) {
            this.images.remove(image);
        }
        image.setProduct(null);
    }
}
