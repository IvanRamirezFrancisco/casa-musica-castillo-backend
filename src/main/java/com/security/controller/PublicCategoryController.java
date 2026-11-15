package com.security.controller;

import com.security.dto.public_api.PublicCategoryDTO;
import com.security.service.PublicCategoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controlador REST público para categorías del Storefront.
 *
 * <h3>Seguridad:</h3>
 * <ul>
 * <li>Ruta base {@code /api/public/categories} con {@code permitAll()} en
 * {@code SecurityConfig}.</li>
 * <li>Solo operaciones GET — sin escritura posible.</li>
 * <li>Solo retorna categorías activas; las inactivas son invisibles.</li>
 * <li>Nunca expone entidades JPA — solo {@link PublicCategoryDTO}
 * (record).</li>
 * </ul>
 */
@RestController
@RequestMapping({"/api/public/categories", "/api/categories"})
public class PublicCategoryController {

    private final PublicCategoryService publicCategoryService;

    public PublicCategoryController(PublicCategoryService publicCategoryService) {
        this.publicCategoryService = publicCategoryService;
    }

    // ── GET /api/public/categories/active ─────────────────────────────────────

    /**
     * Devuelve todas las categorías activas.
     *
     * <p>
     * Incluye categorías sin productos (útil para menús de navegación
     * completos donde se quiera mostrar todas las categorías habilitadas).
     * </p>
     *
     * <p>
     * El parámetro opcional {@code withProducts=true} cambia el comportamiento
     * para devolver solo las categorías que tengan al menos 1 producto activo
     * (útil para el sidebar del catálogo).
     * </p>
     *
     * @param withProducts si {@code true}, solo categorías con productos activos
     * @return 200 OK con lista de categorías activas
     */
    @GetMapping("/active")
    public ResponseEntity<List<PublicCategoryDTO>> getActive(
            @RequestParam(defaultValue = "false") boolean withProducts) {
        List<PublicCategoryDTO> result = withProducts
                ? publicCategoryService.getActiveCategoriesWithProducts()
                : publicCategoryService.getActiveCategories();
        return ResponseEntity.ok(result);
    }
}
