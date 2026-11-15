package com.security.service.alexa;

import com.security.dto.alexa.AlexaRecommendationDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlexaRecommendationService {

    private final AlexaProductQueryService alexaProductQueryService;

    public AlexaRecommendationDTO getRecommendations(String category, String level, Integer budget, int page, int size) {
        var searchResult = category != null && !category.isEmpty() 
            ? alexaProductQueryService.getProductsByCategory(category, 0, 50) // Pedimos más para poder filtrar en memoria
            : alexaProductQueryService.searchProducts(level != null ? level : "instrumento", 0, 50);

        if (searchResult.getProducts().isEmpty() && category != null && !category.isEmpty()) {
            // Fallback a búsqueda por texto combinando categoría y nivel
            String q = category + (level != null ? " " + level : "");
            searchResult = alexaProductQueryService.searchProducts(q.trim(), 0, 50);
        }
        
        var recommendedProducts = searchResult.getProducts().stream()
            .filter(p -> p.isAvailable()) // Priorizar disponibles
            .filter(p -> budget == null || budget <= 0 || p.getPrice().doubleValue() <= budget) // Respetar presupuesto
            .sorted((p1, p2) -> {
                int score1 = "DISPONIBLE".equals(p1.getAvailabilityStatus()) ? 0 : 1;
                int score2 = "DISPONIBLE".equals(p2.getAvailabilityStatus()) ? 0 : 1;
                return Integer.compare(score1, score2);
            })
            .limit(size) // Limitar tamaño después del filtrado y ordenamiento
            .toList();

        String message = recommendedProducts.isEmpty() 
            ? "Lo siento, no encontramos recomendaciones que se ajusten a tus criterios actuales." 
            : "Aquí tienes algunas recomendaciones que encontramos para ti.";

        return new AlexaRecommendationDTO(
                category,
                level,
                budget,
                message,
                recommendedProducts
        );
    }
}
