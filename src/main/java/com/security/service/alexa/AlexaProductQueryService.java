package com.security.service.alexa;

import com.security.dto.alexa.AlexaProductDetailDTO;
import com.security.dto.alexa.AlexaProductSearchResponseDTO;
import com.security.dto.alexa.AlexaProductSummaryDTO;
import com.security.entity.Category;
import com.security.entity.Product;
import com.security.repository.CategoryRepository;
import com.security.repository.PublicProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.web.util.HtmlUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlexaProductQueryService {

    private final PublicProductRepository publicProductRepository;
    private final CategoryRepository categoryRepository;
    private final com.security.service.SearchNormalizationService normalizationService;

    @Value("${frontend.public.base-url:}")
    private String frontendBaseUrl;

    @Value("${product.detail.path:/producto/{id}}")
    private String productDetailPath;

    public AlexaProductSearchResponseDTO searchProducts(String query, int page, int size) {
        String exact = normalizationService.normalizeForSearch(query);
        String escaped = normalizationService.escapeForLike(exact);
        Page<Product> productPage = publicProductRepository.findCatalogWithKeyword(
                exact, escaped, exact, escaped, null, null, null, null, PageRequest.of(page, size, org.springframework.data.domain.Sort.unsorted()));
        return buildSearchResponse(query, productPage);
    }

    public AlexaProductSearchResponseDTO getProductsByCategory(String categoryName, int page, int size) {
        List<String> aliases = resolveCategoryAlias(categoryName);
        
        // 1. Búsqueda por categoría exacta normalizada
        List<Category> allCategories = categoryRepository.findByActiveTrue();
        Category category = null;
        for (Category c : allCategories) {
            String normDbCategory = normalizeString(c.getName());
            if (aliases.stream().map(this::normalizeString).anyMatch(a -> a.equals(normDbCategory))) {
                category = c;
                break;
            }
        }

        if (category != null) {
            Page<Product> productPage = publicProductRepository.findCatalogWithoutKeyword(
                    category.getId(), null, PageRequest.of(page, size, org.springframework.data.domain.Sort.by("featured").descending().and(org.springframework.data.domain.Sort.by("createdAt").descending())));
            if (productPage.getTotalElements() > 0) {
                return buildSearchResponse(categoryName, productPage);
            }
        }

        // 2. Búsqueda por texto usando aliases
        List<Product> combinedProducts = new java.util.ArrayList<>();
        for (String alias : aliases) {
            String exact = normalizationService.normalizeForSearch(alias);
            String escaped = normalizationService.escapeForLike(exact);
            Page<Product> pPage = publicProductRepository.findCatalogWithKeyword(
                    exact, escaped, exact, escaped, null, null, null, null, PageRequest.of(0, 100, org.springframework.data.domain.Sort.unsorted()));
            for (Product p : pPage.getContent()) {
                if (combinedProducts.stream().noneMatch(existing -> existing.getId().equals(p.getId()))) {
                    combinedProducts.add(p);
                }
            }
        }

        // 3. Fallback al término original si los aliases no arrojan resultados
        if (combinedProducts.isEmpty()) {
            String exact = normalizationService.normalizeForSearch(categoryName);
            String escaped = normalizationService.escapeForLike(exact);
            Page<Product> pPage = publicProductRepository.findCatalogWithKeyword(
                    exact, escaped, exact, escaped, null, null, null, null, PageRequest.of(0, 100, org.springframework.data.domain.Sort.unsorted()));
            for (Product p : pPage.getContent()) {
                if (combinedProducts.stream().noneMatch(existing -> existing.getId().equals(p.getId()))) {
                    combinedProducts.add(p);
                }
            }
        }

        // Paginación en memoria
        int totalElements = combinedProducts.size();
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        List<Product> paginated = combinedProducts.subList(fromIndex, toIndex);

        List<AlexaProductSummaryDTO> dtos = paginated.stream()
                .map(this::mapToSummaryDTO)
                .collect(Collectors.toList());

        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new AlexaProductSearchResponseDTO(
                categoryName, page, size, totalElements, totalPages, dtos
        );
    }

    public AlexaProductDetailDTO getProductById(Long id) {
        return publicProductRepository.findActiveById(id)
                .map(this::mapToDetailDTO)
                .orElse(null);
    }

    public AlexaProductSearchResponseDTO getOffers(int page, int size) {
        // Asumiendo que 'featured' actúa como ofertas o destacados para Alexa según lo conversado
        Page<Product> productPage = publicProductRepository.findFeaturedActive(PageRequest.of(page, size));
        return buildSearchResponse("offers", productPage);
    }

    private AlexaProductSearchResponseDTO buildSearchResponse(String query, Page<Product> productPage) {
        List<AlexaProductSummaryDTO> dtos = productPage.getContent().stream()
                .map(this::mapToSummaryDTO)
                .collect(Collectors.toList());

        return new AlexaProductSearchResponseDTO(
                query,
                productPage.getNumber(),
                productPage.getSize(),
                productPage.getTotalElements(),
                productPage.getTotalPages(),
                dtos
        );
    }

    private AlexaProductSummaryDTO mapToSummaryDTO(Product product) {
        AlexaProductSummaryDTO dto = new AlexaProductSummaryDTO();
        populateSummaryFields(dto, product);
        return dto;
    }

    private AlexaProductDetailDTO mapToDetailDTO(Product product) {
        AlexaProductDetailDTO dto = new AlexaProductDetailDTO();
        populateSummaryFields(dto, product);
        dto.setDescription(product.getDescription() != null ? product.getDescription() : product.getDetailedDescription());
        dto.setProductUrl(buildProductUrl(product.getId()));
        return dto;
    }

    private void populateSummaryFields(AlexaProductSummaryDTO dto, Product p) {
        dto.setId(p.getId());
        dto.setName(p.getName());
        dto.setBrand(p.getBrand() != null ? p.getBrand().getName() : "Sin marca");
        dto.setCategory(p.getCategory() != null ? p.getCategory().getName() : "General");
        
        dto.setOffer(p.getDiscountPrice() != null && p.getDiscountPrice().compareTo(p.getPrice()) < 0);
        dto.setPrice(dto.isOffer() ? p.getDiscountPrice() : p.getPrice());
        dto.setCurrency("MXN");

        int stock = p.getStock() != null ? p.getStock() : 0;
        if (stock <= 0) {
            dto.setAvailabilityStatus("AGOTADO");
            dto.setAvailable(false);
        } else if (stock <= 5) {
            dto.setAvailabilityStatus("POCAS_UNIDADES");
            dto.setAvailable(true);
        } else {
            dto.setAvailabilityStatus("DISPONIBLE");
            dto.setAvailable(true);
        }

        // Si no está activo de alguna manera (aunque el query lo previene, por seguridad)
        if (Boolean.FALSE.equals(p.getActive())) {
            dto.setAvailabilityStatus("NO_DISPONIBLE");
            dto.setAvailable(false);
        }

        // Sanitización de HTML y límite seguro
        String rawDesc = p.getDescription() != null && !p.getDescription().trim().isEmpty() ? p.getDescription() : "Producto musical: " + p.getName();
        String unescaped = HtmlUtils.htmlUnescape(rawDesc);
        String cleanDesc = unescaped.replaceAll("<[^>]*>", "").trim();
        
        if (cleanDesc.length() > 100) {
            cleanDesc = cleanDesc.substring(0, 97) + "...";
        }
        dto.setShortDescription(cleanDesc);
        
        String url = p.getImageUrl();
        dto.setImageUrl(url != null && !url.trim().isEmpty() ? url.trim() : null);
    }

    private String buildProductUrl(Long id) {
        if (frontendBaseUrl == null || frontendBaseUrl.trim().isEmpty()) {
            return null; // Don't return malformed relative URLs
        }
        String path = productDetailPath.replace("{id}", String.valueOf(id));
        return frontendBaseUrl.endsWith("/") && path.startsWith("/") 
            ? frontendBaseUrl + path.substring(1) 
            : frontendBaseUrl + (frontendBaseUrl.endsWith("/") || path.startsWith("/") ? "" : "/") + path;
    }

    private String normalizeString(String input) {
        if (input == null) return "";
        return input.toLowerCase().trim()
            .replaceAll("[áäâà]", "a")
            .replaceAll("[éëêè]", "e")
            .replaceAll("[íïîì]", "i")
            .replaceAll("[óöôò]", "o")
            .replaceAll("[úüûù]", "u");
    }

    private List<String> resolveCategoryAlias(String categoryName) {
        if (categoryName == null) return List.of();
        String normalized = normalizeString(categoryName);
        return switch (normalized) {
            case "guitarras", "guitarra" -> List.of("Guitarras", "guitarra", "jarana", "jaranas", "quinta", "quintas", "quinta huapanguera", "quintas huapangueras", "cuerdas para guitarra", "capotraste", "tahalí", "atril de guitarra");
            case "accesorios", "accesorio" -> List.of("Accesorios", "accesorio");
            case "microfonos", "microfono", "mic", "mics" -> List.of("Micrófonos", "microfono");
            case "teclados", "teclado", "pianos", "piano" -> List.of("Teclados", "teclado", "piano");
            case "instrumentos", "instrumento", "musicales" -> List.of("Instrumentos", "instrumento", "guitarra", "jarana", "quinta", "violin");
            default -> List.of(categoryName);
        };
    }
}
