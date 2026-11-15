package com.security.dto.response;

import com.security.entity.Category;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.Hibernate;

import java.time.LocalDateTime;

/**
 * DTO para respuesta de categoría
 * Evita problemas de serialización JSON con relaciones bidireccionales
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryDTO {

    private Long id;
    private String name;
    private String description;
    private String imageUrl;
    private String imagePublicId;
    private String imageProvider;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Campos para jerarquía
    private Long parentId;
    private String parentName;
    private Integer subcategoryCount;
    private Boolean hasChildren;
    private Integer level;
    private String hierarchyPath;

    // Contador de productos
    private Integer productCount;

    /**
     * Convertir entidad Category a DTO
     * IMPORTANTE: Maneja las relaciones LAZY de forma segura
     */
    public static CategoryDTO fromEntity(Category category) {
        if (category == null) {
            return null;
        }

        // 🔒 Extraer datos de forma segura sin tocar colecciones LAZY
        Long parentId = null;
        String parentName = null;

        // Solo acceder al parent si está inicializado
        if (category.getParent() != null && Hibernate.isInitialized(category.getParent())) {
            parentId = category.getParent().getId();
            parentName = category.getParent().getName();
        }

        // Para subcategories: verificar si está inicializada (JOIN FETCH en el repo)
        Integer subcategoryCount = 0;
        if (Hibernate.isInitialized(category.getSubcategories())) {
            subcategoryCount = category.getSubcategories() != null ? category.getSubcategories().size() : 0;
        }

        // productCount viene del campo @Formula de Hibernate — siempre disponible,
        // calculado como subquery SQL sin necesidad de cargar la colección LAZY.
        Integer productCount = category.getProductCount();

        // Determinar level y hierarchyPath iterando hacia arriba si es posible
        // Como el parent puede estar lazy, esto se limitará al padre inmediato si no se hace en un join fetch más grande.
        // Pero para el DTO simple, podemos establecer nivel 0 o 1.
        Integer level = parentId == null ? 0 : 1;
        String hierarchyPath = parentId == null ? category.getName() : parentName + " > " + category.getName();

        return CategoryDTO.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .imageUrl(category.getImageUrl())
                .imagePublicId(category.getImagePublicId())
                .imageProvider(category.getImageProvider())
                .active(category.getActive())
                .createdAt(category.getCreatedAt())
                .updatedAt(category.getUpdatedAt())
                .parentId(parentId)
                .parentName(parentName)
                .subcategoryCount(subcategoryCount)
                .hasChildren(subcategoryCount > 0)
                .level(level)
                .hierarchyPath(hierarchyPath)
                .productCount(productCount)
                .build();
    }
}
