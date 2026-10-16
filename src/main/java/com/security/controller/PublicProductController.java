package com.security.controller;

import com.security.dto.public_api.PublicProductDTO;
import com.security.service.PublicProductService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controlador REST público para el Storefront de Casa de Música Castillo.
 *
 * <h3>Seguridad:</h3>
 * <ul>
 * <li>Ruta base {@code /api/public/products} declarada como {@code permitAll()}
 * en {@code SecurityConfig} — sin JWT ni autenticación.</li>
 * <li>No acepta parámetros de escritura (solo GET).</li>
 * <li>Tamaño de página clampado internamente por {@link PublicProductService} a
 * máximo 50; el cliente no puede superar ese límite.</li>
 * <li>Nunca expone entidades JPA — solo {@link PublicProductDTO} (record).</li>
 * <li>Los IDs ({@code categoryId}, {@code brandId}) son {@code Long} →
 * Spring rechaza automáticamente valores no numéricos con 400.</li>
 * <li>{@code keyword} se sanitiza (trim + truncado a 100 chars) en el
 * servicio.</li>
 * </ul>
 */
@RestController
@RequestMapping({"/api/public/products", "/api/products"})
public class PublicProductController {

    private final PublicProductService publicProductService;
    private final com.security.service.PublicProductRecommendationService publicProductRecommendationService;
    private final com.security.service.PublicDemandTrendService publicDemandTrendService;

    public PublicProductController(PublicProductService publicProductService,
                                   com.security.service.PublicProductRecommendationService publicProductRecommendationService,
                                   com.security.service.PublicDemandTrendService publicDemandTrendService) {
        this.publicProductService = publicProductService;
        this.publicProductRecommendationService = publicProductRecommendationService;
        this.publicDemandTrendService = publicDemandTrendService;
    }

    // ── GET /api/public/products (y /api/products) ────────────────────────────

    /**
     * Endpoint raíz. Actúa como alias seguro para devolver el catálogo por defecto.
     * Evita arrojar 404/500 cuando se consulta la raíz pública de productos.
     */
    @GetMapping
    public ResponseEntity<Page<PublicProductDTO>> getRootCatalog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ResponseEntity.ok(
                publicProductService.getCatalog(null, null, null, page, size, "featured"));
    }

    // ── GET /api/public/products/latest ───────────────────────────────────────

    /**
     * Devuelve los últimos 8 productos activos (ordenados por
     * {@code createdAt DESC}).
     *
     * <p>
     * El límite de 8 es fijo e inmutable desde el cliente.
     * </p>
     *
     * @return 200 OK con lista de hasta 8 productos
     */
    @GetMapping("/latest")
    public ResponseEntity<List<PublicProductDTO>> getLatest() {
        return ResponseEntity.ok(publicProductService.getLatest());
    }

    // ── GET /api/public/products/featured ─────────────────────────────────────

    /**
     * Devuelve productos destacados ({@code featured = true AND active = true}),
     * paginados.
     *
     * @param page página 0-indexed (default 0)
     * @param size tamaño de página (default 12, máx 50)
     * @return 200 OK con página de productos destacados
     */
    @GetMapping("/featured")
    public ResponseEntity<Page<PublicProductDTO>> getFeatured(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ResponseEntity.ok(publicProductService.getFeatured(page, size));
    }

    // ── GET /api/public/products/catalog ──────────────────────────────────────

    /**
     * Vista de tienda: catálogo completo con filtros opcionales.
     *
     * <p>
     * Todos los filtros son opcionales. Si se omiten, devuelve todos los
     * productos activos paginados.
     * </p>
     *
     * <table>
     * <tr>
     * <th>Parámetro</th>
     * <th>Tipo</th>
     * <th>Descripción</th>
     * </tr>
     * <tr>
     * <td>keyword</td>
     * <td>String</td>
     * <td>Búsqueda en nombre y SKU (max 100 chars)</td>
     * </tr>
     * <tr>
     * <td>categoryId</td>
     * <td>Long</td>
     * <td>Filtrar por categoría</td>
     * </tr>
     * <tr>
     * <td>brandId</td>
     * <td>Long</td>
     * <td>Filtrar por marca</td>
     * </tr>
     * <tr>
     * <td>page</td>
     * <td>int</td>
     * <td>Página 0-indexed (default 0)</td>
     * </tr>
     * <tr>
     * <td>size</td>
     * <td>int</td>
     * <td>Tamaño (default 12, máx 50)</td>
     * </tr>
     * </table>
     *
     * @return 200 OK con página de productos filtrados
     */
    @GetMapping("/catalog")
    public ResponseEntity<Page<PublicProductDTO>> getCatalog(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long brandId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(defaultValue = "featured") String sortBy) {
        return ResponseEntity.ok(
                publicProductService.getCatalog(keyword, categoryId, brandId, page, size, sortBy));
    }

    // ── GET /api/public/products/{id} ─────────────────────────────────────────

    /**
     * Devuelve el detalle de un producto activo.
     *
     * <p>
     * Si el ID no existe, o el producto existe pero tiene {@code active = false},
     * retorna estrictamente <strong>404 Not Found</strong>. Nunca se expone
     * información de borradores al público.
     * </p>
     *
     * @param id ID del producto
     * @return 200 OK con detalle del producto, o 404 si no existe/inactivo
     */
    @GetMapping("/{id}")
    public ResponseEntity<PublicProductDTO> getById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(publicProductService.getById(id));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── GET /api/public/products/{id}/recommendations ─────────────────────────

    /**
     * Devuelve las recomendaciones (productos complementarios) para un producto.
     *
     * <p>
     * Si el ID no existe, o el producto existe pero tiene {@code active = false},
     * retorna estrictamente <strong>404 Not Found</strong>.
     * </p>
     *
     * @param id ID del producto
     * @return 200 OK con la lista de recomendaciones, o 404 si no existe/inactivo
     */
    @GetMapping("/{id}/recommendations")
    public ResponseEntity<List<com.security.dto.public_api.PublicProductRecommendationDTO>> getRecommendations(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(publicProductRecommendationService.getRecommendations(id));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── GET /api/public/products/trending ─────────────────────────────────────

    /**
     * Devuelve los productos en tendencia basados en el pronóstico de demanda de medias móviles.
     */
    @GetMapping("/trending")
    public ResponseEntity<List<com.security.dto.public_api.PublicTrendingProductDTO>> getTrendingProducts(
            @RequestParam(defaultValue = "8") int limit) {
        return ResponseEntity.ok(publicDemandTrendService.getTrendingProducts(limit));
    }

    // ── GET /api/public/products/{id}/trend-status ────────────────────────────

    /**
     * Verifica de forma segura si un producto está en tendencia.
     */
    @GetMapping("/{id}/trend-status")
    public ResponseEntity<com.security.dto.public_api.ProductTrendStatusDTO> getTrendStatus(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(publicDemandTrendService.getProductTrendStatus(id));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.notFound().build();
        }
    }
}
