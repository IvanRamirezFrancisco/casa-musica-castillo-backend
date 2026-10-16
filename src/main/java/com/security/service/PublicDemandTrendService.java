package com.security.service;

import com.security.dto.admin.analytics.forecast.DemandForecastListDTO;
import com.security.dto.public_api.ProductTrendStatusDTO;
import com.security.dto.public_api.PublicProductDTO;
import com.security.dto.public_api.PublicTrendingProductDTO;
import com.security.enums.DemandForecastStatus;
import com.security.enums.DemandPriority;
import com.security.enums.DemandTrend;
import com.security.service.analytics.DemandForecastService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class PublicDemandTrendService {

    private static final String TREND_LABEL = "Producto en tendencia";
    private static final String TREND_REASON = "Demanda creciente para la próxima semana";

    private final DemandForecastService demandForecastService;
    private final PublicProductService publicProductService;

    public PublicDemandTrendService(DemandForecastService demandForecastService,
                                    PublicProductService publicProductService) {
        this.demandForecastService = demandForecastService;
        this.publicProductService = publicProductService;
    }

    /**
     * Obtiene los productos públicos en tendencia aplicando las reglas de negocio estrictas.
     */
    public List<PublicTrendingProductDTO> getTrendingProducts(int limit) {
        int actualLimit = Math.max(1, Math.min(12, limit)); // Limitar entre 1 y 12

        List<DemandForecastListDTO> globalBatch;
        try {
            globalBatch = demandForecastService.calculateGlobalBatch();
        } catch (IllegalStateException e) {
            // Artefacto no disponible, retornamos lista vacía sin romper el frontend
            return List.of();
        }

        return globalBatch.stream()
                .filter(dto -> Boolean.TRUE.equals(dto.productActive()))
                .filter(dto -> dto.currentStock() > 0)
                .filter(dto -> dto.forecastStatus() == DemandForecastStatus.AVAILABLE)
                .filter(dto -> dto.trend() == DemandTrend.CRECIENTE)
                .filter(dto -> dto.demandPriority() == DemandPriority.ALTA)
                .filter(dto -> dto.forecastUnits() != null && dto.forecastUnits().compareTo(BigDecimal.ZERO) >= 0)
                .sorted(Comparator.comparing(DemandForecastListDTO::forecastUnits, Comparator.reverseOrder())
                        .thenComparing(DemandForecastListDTO::productName, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(actualLimit)
                .map(this::mapToTrendingDTO)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /**
     * Identifica de forma segura si un producto individual está en tendencia.
     */
    public ProductTrendStatusDTO getProductTrendStatus(Long productId) {
        // Valida la existencia del producto y su estado activo. Si no, lanza EntityNotFoundException (devuelve 404 seguro)
        publicProductService.getById(productId);

        List<DemandForecastListDTO> globalBatch;
        try {
            globalBatch = demandForecastService.calculateGlobalBatch();
        } catch (IllegalStateException e) {
            // Artefacto no disponible
            return new ProductTrendStatusDTO(productId, false, null, null);
        }

        boolean isTrending = globalBatch.stream()
                .filter(dto -> dto.productId().equals(productId))
                .anyMatch(dto -> 
                        Boolean.TRUE.equals(dto.productActive()) &&
                        dto.currentStock() > 0 &&
                        dto.forecastStatus() == DemandForecastStatus.AVAILABLE &&
                        dto.trend() == DemandTrend.CRECIENTE &&
                        dto.demandPriority() == DemandPriority.ALTA &&
                        dto.forecastUnits() != null && dto.forecastUnits().compareTo(BigDecimal.ZERO) >= 0
                );

        if (isTrending) {
            return new ProductTrendStatusDTO(productId, true, TREND_LABEL, TREND_REASON);
        } else {
            return new ProductTrendStatusDTO(productId, false, null, null);
        }
    }

    private Optional<PublicTrendingProductDTO> mapToTrendingDTO(DemandForecastListDTO forecastDto) {
        try {
            // Reutiliza el mapeo comercial seguro existente
            PublicProductDTO productDto = publicProductService.getById(forecastDto.productId());
            
            return Optional.of(new PublicTrendingProductDTO(
                    productDto.id(),
                    productDto.sku(),
                    productDto.name(),
                    productDto.description(),
                    productDto.imageUrl(),
                    productDto.category() != null ? productDto.category().id() : null,
                    productDto.category() != null ? productDto.category().name() : null,
                    productDto.price(),
                    productDto.discountPrice() != null ? productDto.discountPrice() : productDto.price(),
                    productDto.discountPrice(),
                    productDto.discountPrice() != null,
                    TREND_LABEL,
                    TREND_REASON
            ));
        } catch (EntityNotFoundException e) {
            // Puede ocurrir en casos de carrera si el producto fue desactivado o eliminado
            return Optional.empty();
        }
    }
}
