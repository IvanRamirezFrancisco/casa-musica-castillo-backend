package com.security.controller.admin;

import com.security.dto.BrandDTO;
import com.security.service.BrandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.security.dto.StorageUploadResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller para administración de marcas
 * Solo accesible por usuarios con rol ADMIN
 * CORS se maneja globalmente en SecurityConfig
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/brands")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    /**
     * Obtener todas las marcas con paginación
     * GET /api/admin/brands?page=0&size=20&sortBy=name&sortDir=asc
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PRODUCT_READ') or hasAuthority('BRAND_MANAGE')")
    public ResponseEntity<BrandDTO.BrandListResponse> getAllBrands(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {

        log.info("Obteniendo marcas - página: {}, tamaño: {}", page, size);
        BrandDTO.BrandListResponse response = brandService.getAllBrands(page, size, sortBy, sortDir);
        return ResponseEntity.ok(response);
    }

    /**
     * Buscar marcas con filtros
     * GET /api/admin/brands/search?name=fender&active=true&page=0&size=20
     */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('PRODUCT_READ') or hasAuthority('BRAND_MANAGE')")
    public ResponseEntity<BrandDTO.BrandListResponse> searchBrands(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String countryOrigin,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("Buscando marcas con filtros - name: {}, active: {}", name, active);
        BrandDTO.BrandListResponse response = brandService.searchBrands(name, active, countryOrigin, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * Obtener marcas activas (para select en formulario de productos)
     * GET /api/admin/brands/active
     */
    @GetMapping("/active")
    @PreAuthorize("hasAuthority('PRODUCT_READ') or hasAuthority('BRAND_MANAGE')")
    public ResponseEntity<List<BrandDTO.BrandBasicInfo>> getActiveBrands() {
        log.info("Obteniendo marcas activas");
        List<BrandDTO.BrandBasicInfo> brands = brandService.getActiveBrands();
        return ResponseEntity.ok(brands);
    }

    /**
     * Obtener marca por ID
     * GET /api/admin/brands/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUCT_READ') or hasAuthority('BRAND_MANAGE')")
    public ResponseEntity<BrandDTO.BrandResponse> getBrandById(@PathVariable Long id) {
        log.info("Obteniendo marca con ID: {}", id);
        BrandDTO.BrandResponse brand = brandService.getBrandById(id);
        return ResponseEntity.ok(brand);
    }

    /**
     * Crear nueva marca
     * POST /api/admin/brands
     */
    @PostMapping
    @PreAuthorize("hasAuthority('BRAND_MANAGE')")
    public ResponseEntity<BrandDTO.BrandResponse> createBrand(@Valid @RequestBody BrandDTO.BrandRequest request) {
        log.info("Creando nueva marca: {}", request.getName());
        BrandDTO.BrandResponse brand = brandService.createBrand(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(brand);
    }

    /**
     * Actualizar marca existente
     * PUT /api/admin/brands/{id}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('BRAND_MANAGE')")
    public ResponseEntity<BrandDTO.BrandResponse> updateBrand(
            @PathVariable Long id,
            @Valid @RequestBody BrandDTO.BrandRequest request) {

        log.info("Actualizando marca con ID: {}", id);
        BrandDTO.BrandResponse brand = brandService.updateBrand(id, request);
        return ResponseEntity.ok(brand);
    }

    /**
     * Eliminar marca
     * DELETE /api/admin/brands/{id}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('BRAND_MANAGE')")
    public ResponseEntity<Map<String, String>> deleteBrand(@PathVariable Long id) {
        log.info("Eliminando marca con ID: {}", id);
        brandService.deleteBrand(id);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Marca eliminada exitosamente");
        return ResponseEntity.ok(response);
    }

    /**
     * Activar/Desactivar marca
     * PATCH /api/admin/brands/{id}/toggle-status
     */
    @PatchMapping("/{id}/toggle-status")
    @PreAuthorize("hasAuthority('BRAND_MANAGE')")
    public ResponseEntity<BrandDTO.BrandResponse> toggleBrandStatus(@PathVariable Long id) {
        log.info("Cambiando estado de marca con ID: {}", id);
        BrandDTO.BrandResponse brand = brandService.toggleBrandStatus(id);
        return ResponseEntity.ok(brand);
    }

    /**
     * Actualizar contador de productos de una marca
     * PATCH /api/admin/brands/{id}/update-count
     */
    @PatchMapping("/{id}/update-count")
    @PreAuthorize("hasAuthority('BRAND_MANAGE')")
    public ResponseEntity<Map<String, String>> updateProductCount(@PathVariable Long id) {
        log.info("Actualizando contador de productos para marca ID: {}", id);
        brandService.updateProductCount(id);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Contador actualizado exitosamente");
        return ResponseEntity.ok(response);
    }

    /**
     * Subir logo de marca
     * POST /api/admin/brands/{id}/logo
     */
    @PostMapping("/{id}/logo")
    @PreAuthorize("hasAuthority('BRAND_MANAGE')")
    public ResponseEntity<?> uploadLogo(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        log.info("Subiendo logo para la marca ID: {}", id);
        try {
            StorageUploadResult result = brandService.uploadLogo(id, file);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Logo subido exitosamente",
                    "data", result
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error al subir logo: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al procesar el logo: " + e.getMessage()));
        }
    }

    /**
     * Eliminar logo de marca
     * DELETE /api/admin/brands/{id}/logo
     */
    @DeleteMapping("/{id}/logo")
    @PreAuthorize("hasAuthority('BRAND_MANAGE')")
    public ResponseEntity<?> deleteLogo(@PathVariable Long id) {
        log.info("Eliminando logo para la marca ID: {}", id);
        try {
            brandService.deleteLogo(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Logo eliminado exitosamente"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error al eliminar logo: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al eliminar el logo: " + e.getMessage()));
        }
    }
}
