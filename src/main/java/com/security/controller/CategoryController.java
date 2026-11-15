package com.security.controller;

import com.security.dto.request.CategoryRequest;
import com.security.dto.response.CategoryDTO;
import com.security.entity.Category;
import com.security.service.CategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.security.dto.StorageUploadResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * Controlador REST para gestión de categorías de productos
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
@Slf4j
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * Obtener todas las categorías
     * Endpoint público - no requiere autenticación
     */
    @GetMapping
    public ResponseEntity<?> getAllCategories() {
        try {
            log.info("📂 Obteniendo todas las categorías");
            List<Category> categories = categoryService.getAllCategories();
            List<CategoryDTO> dtos = categories.stream()
                    .map(CategoryDTO::fromEntity)
                    .collect(Collectors.toList());
            log.info("✅ Se encontraron {} categorías", dtos.size());
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            log.error("❌ Error al obtener categorías: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Error al obtener categorías",
                            "message", e.getMessage()));
        }
    }

    /**
     * Obtener solo categorías activas
     * Endpoint público - no requiere autenticación
     */
    @GetMapping("/active")
    public ResponseEntity<?> getActiveCategories() {
        try {
            log.info("📂 Obteniendo categorías activas");
            List<Category> categories = categoryService.getAllActiveCategories();
            List<CategoryDTO> dtos = categories.stream()
                    .map(CategoryDTO::fromEntity)
                    .collect(Collectors.toList());
            log.info("✅ Se encontraron {} categorías activas", dtos.size());
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            log.error("❌ Error al obtener categorías activas: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Error al obtener categorías activas",
                            "message", e.getMessage()));
        }
    }

    /**
     * Obtener categoría por ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getCategoryById(@PathVariable Long id) {
        try {
            log.info("📂 Obteniendo categoría con ID: {}", id);
            return categoryService.getCategoryById(id)
                    .map(category -> {
                        log.info("✅ Categoría encontrada: {}", category.getName());
                        CategoryDTO dto = CategoryDTO.fromEntity(category);
                        return ResponseEntity.ok((Object) dto);
                    })
                    .orElseGet(() -> {
                        log.warn("⚠️ Categoría no encontrada con ID: {}", id);
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body((Object) Map.of("error", "Categoría no encontrada", "id", id));
                    });
        } catch (Exception e) {
            log.error("❌ Error al obtener categoría: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Error al obtener categoría",
                            "message", e.getMessage()));
        }
    }

    /**
     * Crear nueva categoría
     * Solo ADMIN
     */
    @PostMapping
    @PreAuthorize("hasAuthority('CATEGORY_MANAGE')")
    public ResponseEntity<?> createCategory(@Valid @RequestBody CategoryRequest request) {
        try {
            log.info("➕ Creando nueva categoría: {} (parentId: {})", request.getName(), request.getParentId());

            // Convertir DTO a entidad
            Category category = new Category();
            category.setName(request.getName());
            category.setDescription(request.getDescription());
            category.setImageUrl(request.getImageUrl());
            category.setActive(request.getActive() != null ? request.getActive() : true);

            // El servicio manejará la relación con el padre
            Category createdCategory = categoryService.createCategory(category, request.getParentId());

            log.info("✅ Categoría creada exitosamente con ID: {} (es subcategoría: {})",
                    createdCategory.getId(), createdCategory.getParentId() != null);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(CategoryDTO.fromEntity(createdCategory));
        } catch (Exception e) {
            log.error("❌ Error al crear categoría: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Error al crear categoría",
                            "message", e.getMessage()));
        }
    }

    /**
     * Actualizar categoría existente
     * Solo ADMIN
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CATEGORY_MANAGE')")
    public ResponseEntity<?> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody CategoryRequest request) {
        try {
            log.info("✏️ Actualizando categoría con ID: {} (nuevo parentId: {})", id, request.getParentId());

            // Convertir DTO a entidad
            Category categoryDetails = new Category();
            categoryDetails.setName(request.getName());
            categoryDetails.setDescription(request.getDescription());
            categoryDetails.setImageUrl(request.getImageUrl());
            categoryDetails.setActive(request.getActive() != null ? request.getActive() : true);

            Category updatedCategory = categoryService.updateCategory(id, categoryDetails, request.getParentId());

            log.info("✅ Categoría actualizada exitosamente");
            return ResponseEntity.ok(CategoryDTO.fromEntity(updatedCategory));
        } catch (RuntimeException e) {
            log.error("❌ Error al actualizar categoría: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "error", "Categoría no encontrada",
                            "message", e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Error inesperado al actualizar categoría: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Error al actualizar categoría",
                            "message", e.getMessage()));
        }
    }

    /**
     * ELIMINAR CATEGORÍA PERMANENTEMENTE (HARD DELETE con validaciones)
     * Solo ADMIN
     * 
     * ⚠️ VALIDACIONES PROFESIONALES:
     * - Si tiene subcategorías → ERROR (debe moverlas o eliminarlas primero)
     * - Si tiene productos → ERROR (debe moverlos o eliminarlos primero)
     * - Si está vacía → ÉXITO (borrado físico de la base de datos)
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('CATEGORY_MANAGE')")
    public ResponseEntity<?> deleteCategory(@PathVariable Long id) {
        try {
            log.info("🗑️ Intentando eliminar permanentemente categoría con ID: {}", id);
            categoryService.deleteCategory(id);
            log.info("✅ Categoría eliminada permanentemente de la base de datos");
            return ResponseEntity.ok(Map.of(
                    "message", "Categoría eliminada permanentemente de la base de datos",
                    "id", id));
        } catch (RuntimeException e) {
            // Errores de validación (tiene productos o subcategorías)
            log.warn("⚠️ Validación fallida: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                            "error", "No se puede eliminar la categoría",
                            "message", e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Error inesperado al eliminar categoría: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Error al eliminar categoría",
                            "message", e.getMessage()));
        }
    }

    /**
     * Subir imagen de categoría
     * POST /api/categories/{id}/image
     */
    @PostMapping("/{id}/image")
    @PreAuthorize("hasAuthority('CATEGORY_MANAGE')")
    public ResponseEntity<?> uploadImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        log.info("Subiendo imagen para la categoría ID: {}", id);
        try {
            StorageUploadResult result = categoryService.uploadImage(id, file);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Imagen subida exitosamente",
                    "data", result
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error al subir imagen de categoría: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al procesar la imagen: " + e.getMessage()));
        }
    }

    /**
     * Eliminar imagen de categoría
     * DELETE /api/categories/{id}/image
     */
    @DeleteMapping("/{id}/image")
    @PreAuthorize("hasAuthority('CATEGORY_MANAGE')")
    public ResponseEntity<?> deleteImage(@PathVariable Long id) {
        log.info("Eliminando imagen para la categoría ID: {}", id);
        try {
            categoryService.deleteImage(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Imagen eliminada exitosamente"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error al eliminar imagen de categoría: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al eliminar la imagen: " + e.getMessage()));
        }
    }
}
