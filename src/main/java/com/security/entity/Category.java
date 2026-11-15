package com.security.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entidad para categorías de productos musicales
 */
@Entity
@Table(name = "categories", schema = "catalog")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "El nombre de la categoría es obligatorio")
    @Size(min = 2, max = 100, message = "El nombre debe tener entre 2 y 100 caracteres")
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "image_public_id")
    private String imagePublicId;

    @Column(name = "image_provider", length = 50)
    private String imageProvider;

    @Column(nullable = false)
    private Boolean active = true;

    // ==================== RELACIONES JERÁRQUICAS ====================

    /**
     * Categoría padre (para crear jerarquías)
     * Ejemplo: "Guitarras Eléctricas" tiene padre "Guitarras"
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @JsonIgnore
    private Category parent;

    /**
     * Subcategorías (categorías hijas)
     * Se ignora en JSON para evitar recursión infinita
     */
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Category> subcategories = new ArrayList<>();

    // ==================== RELACIÓN CON PRODUCTOS ====================

    @JsonIgnore
    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Product> products = new ArrayList<>();

    /**
     * Conteo de productos calculado en BD por Hibernate (@Formula).
     * Se ejecuta como un subquery SQL en el SELECT principal —
     * sin cargar la colección LAZY y sin problema de N+1.
     *
     * @Getter(NONE) evita que Lombok genere un getter duplicado;
     *               el getter manual getProductCount() devuelve 0 si aún es null.
     */
    @Formula("(SELECT COUNT(p.id) FROM catalog.products p WHERE p.category_id = id)")
    @Getter(AccessLevel.NONE)
    private Integer productCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Devuelve el conteo de productos calculado por Hibernate @Formula.
     * Siempre disponible sin necesidad de cargar la colección LAZY.
     */
    public Integer getProductCount() {
        return productCount != null ? productCount : 0;
    }

    /**
     * Obtener el ID de la categoría padre
     * Útil para serialización JSON sin cargar toda la entidad padre
     */
    public Long getParentId() {
        return parent != null ? parent.getId() : null;
    }

    /**
     * Obtener el nombre de la categoría padre
     * Útil para serialización JSON
     */
    public String getParentName() {
        return parent != null ? parent.getName() : null;
    }

    /**
     * Obtener el conteo de subcategorías
     */
    public Integer getSubcategoryCount() {
        if (subcategories == null) {
            return 0;
        }
        return subcategories.size();
    }

    /**
     * Verificar si esta categoría es una categoría raíz (sin padre)
     */
    public boolean isRootCategory() {
        return parent == null;
    }

    /**
     * Verificar si esta categoría tiene subcategorías
     */
    public boolean hasSubcategories() {
        return subcategories != null && !subcategories.isEmpty();
    }
}
