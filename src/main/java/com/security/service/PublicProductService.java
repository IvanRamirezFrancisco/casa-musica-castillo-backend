package com.security.service;

import com.security.dto.public_api.PublicProductDTO;
import com.security.entity.Product;
import com.security.repository.PublicProductRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;
import com.security.search.NormalizedSearchQuery;
import com.security.repository.CategoryRepository;
import com.security.repository.BrandRepository;
import com.security.entity.Category;
import com.security.entity.Brand;

/**
 * Servicio de la API pública para productos del Storefront.
 *
 * <h3>Principios de seguridad aplicados:</h3>
 * <ul>
 * <li><strong>Paginación Defensiva:</strong> el tamaño de página nunca supera
 * {@code MAX_PAGE_SIZE} (50), previniendo ataques DoS por sobrecarga de
 * memoria.</li>
 * <li><strong>Sanitización de entrada:</strong> el keyword de búsqueda se
 * trunca
 * a 100 caracteres. Si llega nulo o vacío se convierte a {@code null} para
 * que la query omita ese filtro.</li>
 * <li><strong>Cero exposición de datos internos:</strong> el mapeo a
 * {@link PublicProductDTO} se hace en este servicio; el controlador nunca
 * toca una entidad JPA directamente.</li>
 * <li><strong>active = true garantizado:</strong> toda consulta al repositorio
 * tiene el filtro hardcodeado en JPQL. Este servicio no puede evadirlo.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class PublicProductService {

    /** Tamaño máximo de página permitido para todos los endpoints paginados. */
    private static final int MAX_PAGE_SIZE = 50;

    /** Límite fijo de productos para el endpoint /latest. */
    private static final int LATEST_LIMIT = 8;

    /** Truncado máximo del keyword de búsqueda para prevenir payloads enormes. */
    private static final int MAX_KEYWORD_LENGTH = 100;

    private final PublicProductRepository publicProductRepository;
    private final SearchNormalizationService normalizationService;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;

    public PublicProductService(PublicProductRepository publicProductRepository,
                                SearchNormalizationService normalizationService,
                                CategoryRepository categoryRepository,
                                BrandRepository brandRepository) {
        this.publicProductRepository = publicProductRepository;
        this.normalizationService = normalizationService;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
    }

    // ── Últimos 8 activos ─────────────────────────────────────────────────────

    /**
     * Retorna los últimos {@value #LATEST_LIMIT} productos activos ordenados
     * por {@code createdAt DESC}. El límite es fijo e inmutable desde el cliente.
     */
    public List<PublicProductDTO> getLatest() {
        Pageable pageable = PageRequest.of(0, LATEST_LIMIT, Sort.by("createdAt").descending());
        return publicProductRepository.findLatestActive(pageable)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ── Destacados paginados ──────────────────────────────────────────────────

    /**
     * Retorna productos featured AND active, con paginación defensiva.
     *
     * @param page número de página (0-indexed)
     * @param size tamaño solicitado — se clampea a [1, {@value #MAX_PAGE_SIZE}]
     */
    public Page<PublicProductDTO> getFeatured(int page, int size) {
        Pageable pageable = safePageable(page, size);
        return publicProductRepository.findFeaturedActive(pageable).map(this::toDTO);
    }

    // ── Catálogo con filtros ──────────────────────────────────────────────────

    /**
     * Endpoint de catálogo público con búsqueda y filtros opcionales.
     *
     * <p>
     * El keyword se sanitiza (trim + truncado) antes de pasarlo al repositorio.
     * Los IDs de categoría y marca son {@code Long} — el binding de tipos de Spring
     * impide inyección por tipo.
     * </p>
     *
     * @param keyword    texto de búsqueda (puede ser null o vacío)
     * @param categoryId filtro por categoría (puede ser null)
     * @param brandId    filtro por marca (puede ser null)
     * @param page       página (0-indexed)
     * @param size       tamaño de página — clampado a [1, {@value #MAX_PAGE_SIZE}]
     */
    public Page<PublicProductDTO> getCatalog(
            String keyword,
            Long categoryId,
            Long brandId,
            int page,
            int size,
            String sortBy) {
        
        if (keyword == null || keyword.isBlank()) {
            Pageable pageable = safePageable(page, size, sortBy);
            return publicProductRepository
                    .findCatalogWithoutKeyword(categoryId, brandId, pageable)
                    .map(this::toDTO);
        } else {
            NormalizedSearchQuery query = normalizationService.buildSearchQuery(keyword);
            
            Long categoryIntentId = null;
            Long brandIntentId = null;
            
            // Intención de categoría (insensible a acentos y mayúsculas)
            List<Category> allCategories = categoryRepository.findByActiveTrue();
            for (String variant : query.variants()) {
                Category cat = allCategories.stream()
                    .filter(c -> c.getName() != null &&
                            normalizationService.buildSearchQuery(c.getName()).canonical().equalsIgnoreCase(variant))
                    .findFirst()
                    .orElse(null);
                if (cat != null) {
                    categoryIntentId = cat.getId();
                    break;
                }
            }

            // Intención de marca (insensible a acentos y mayúsculas)
            List<Brand> allBrands = brandRepository.findAll();
            for (String variant : query.variants()) {
                Brand brand = allBrands.stream()
                    .filter(b -> Boolean.TRUE.equals(b.getActive()) && b.getName() != null &&
                            normalizationService.buildSearchQuery(b.getName()).canonical().equalsIgnoreCase(variant))
                    .findFirst()
                    .orElse(null);
                if (brand != null) {
                    brandIntentId = brand.getId();
                    break;
                }
            }

            String escapedOriginal = normalizationService.escapeForLike(query.normalizedOriginal());
            String escapedCanonical = normalizationService.escapeForLike(query.canonical());
            List<String> tokens = query.tokens() != null ? query.tokens() : List.of();
            String token1 = tokens.size() >= 1 ? normalizationService.escapeForLike(tokens.get(0)) : null;
            String token2 = tokens.size() >= 2 ? normalizationService.escapeForLike(tokens.get(1)) : null;
            String token3 = tokens.size() >= 3 ? normalizationService.escapeForLike(tokens.get(2)) : null;

            Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE), Sort.unsorted());
            return publicProductRepository
                    .findCatalogWithKeyword(
                        query.normalizedOriginal(),
                        escapedOriginal,
                        query.canonical(),
                        escapedCanonical,
                        token1,
                        token2,
                        token3,
                        categoryIntentId,
                        brandIntentId,
                        categoryId,
                        brandId,
                        pageable
                    ).map(this::toDTO);
        }
    }

    // ── Detalle de producto ───────────────────────────────────────────────────

    /**
     * Retorna el detalle de un producto activo.
     *
     * @throws EntityNotFoundException si el producto no existe o está inactivo —
     *                                 el controlador convertirá esto en HTTP 404.
     */
    public PublicProductDTO getById(Long id) {
        return publicProductRepository.findActiveById(id)
                .map(this::toDTO)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Producto no encontrado o no disponible: id=" + id));
    }

    // ── Helpers de seguridad ──────────────────────────────────────────────────

    /**
     * Construye un {@link Pageable} con tamaño siempre dentro de límites seguros.
     * Previene que el cliente solicite {@code size=99999} provocando un OOM.
     */
    private Pageable safePageable(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, Sort.by("createdAt").descending());
    }

    /**
     * Builds a Pageable with the requested sort strategy.
     * Accepted values: featured | price_asc | price_desc | name_asc.
     * Any unknown value falls back to "featured" (createdAt DESC).
     */
    private Pageable safePageable(int page, int size, String sortBy) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Sort sort = switch (sortBy == null ? "" : sortBy.trim().toLowerCase()) {
            case "price_asc" -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            case "name_asc" -> Sort.by("name").ascending();
            default -> Sort.by("featured").descending().and(Sort.by("createdAt").descending());
        };
        return PageRequest.of(safePage, safeSize, sort);
    }

    // El sanitizeKeyword privado original se elimina porque el SearchNormalizationService hace el trabajo.

    // ── Mapeo Entidad → DTO (sin exponer entidades JPA) ───────────────────────

    /**
     * Convierte una entidad {@link Product} al DTO público.
     * Todos los campos administrativos son omitidos aquí.
     */
    private PublicProductDTO toDTO(Product p) {
        return new PublicProductDTO(
                p.getId(),
                p.getSku(),
                p.getName(),
                p.getDescription(),
                p.getDetailedDescription(),
                p.getPrice(),
                p.getDiscountPrice(),
                p.getStock(),
                p.getImageUrl(),
                mapImages(p),
                mapCategory(p),
                mapBrand(p),
                mapAttributes(p),
                p.getModel(),
                p.getWeight(),
                p.getDimensions(),
                p.getFeatured(),
                p.getAverageRating(),
                p.getReviewCount());
    }

    private List<PublicProductDTO.PublicImageDTO> mapImages(Product p) {
        if (p.getImages() == null || p.getImages().isEmpty())
            return List.of();
        return p.getImages().stream()
                .map(img -> new PublicProductDTO.PublicImageDTO(
                        img.getImageUrl(),
                        img.getDisplayOrder()))
                .collect(Collectors.toList());
    }

    private PublicProductDTO.PublicCategoryRefDTO mapCategory(Product p) {
        if (p.getCategory() == null)
            return null;
        var cat = p.getCategory();
        return new PublicProductDTO.PublicCategoryRefDTO(
                cat.getId(),
                cat.getName(),
                cat.getParentId(),
                cat.getParentName());
    }

    private PublicProductDTO.PublicBrandRefDTO mapBrand(Product p) {
        if (p.getBrand() == null)
            return null;
        var br = p.getBrand();
        return new PublicProductDTO.PublicBrandRefDTO(
                br.getId(),
                br.getName(),
                br.getLogoUrl());
    }

    private List<PublicProductDTO.PublicAttributeDTO> mapAttributes(Product p) {
        if (p.getCustomAttributes() == null || p.getCustomAttributes().isEmpty())
            return List.of();
        return p.getCustomAttributes().stream()
                .map(attr -> new PublicProductDTO.PublicAttributeDTO(
                        attr.getAttributeName(),
                        attr.getAttributeValue(),
                        attr.getDisplayOrder()))
                .collect(Collectors.toList());
    }
}
