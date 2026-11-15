package com.security.service;

import com.security.dto.public_api.PublicProductRecommendationDTO;
import com.security.repository.PublicProductRecommendationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PublicProductRecommendationService {

    private static final String RECOMMENDATION_REASON = "Frecuentemente comprado junto con este producto";

    private final PublicProductRecommendationRepository recommendationRepository;
    private final PublicProductService publicProductService;

    public PublicProductRecommendationService(
            PublicProductRecommendationRepository recommendationRepository,
            PublicProductService publicProductService) {
        this.recommendationRepository = recommendationRepository;
        this.publicProductService = publicProductService;
    }

    /**
     * Obtiene las recomendaciones para un producto.
     * Reutiliza la validación de publicProductService.getById para asegurar que 
     * el producto existe y está activo.
     */
    public List<PublicProductRecommendationDTO> getRecommendations(Long productId) {
        // Valida la existencia y estado activo del producto. Lanza EntityNotFoundException si no es válido.
        publicProductService.getById(productId);

        return recommendationRepository.findRecommendationsByProductId(productId)
                .stream()
                .map(projection -> new PublicProductRecommendationDTO(
                        projection.getProductId(),
                        projection.getSku(),
                        projection.getName(),
                        projection.getDescription(),
                        projection.getImageUrl(),
                        projection.getCategoryId(),
                        projection.getCategoryName(),
                        projection.getRegularPrice(),
                        projection.getEffectivePrice(),
                        projection.getDiscountPrice(),
                        projection.getStock(),
                        Boolean.TRUE.equals(projection.getHasDiscount()),
                        RECOMMENDATION_REASON
                ))
                .collect(Collectors.toList());
    }
}
