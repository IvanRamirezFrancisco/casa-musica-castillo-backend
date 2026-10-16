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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicDemandTrendServiceTest {

    @Mock
    private DemandForecastService demandForecastService;

    @Mock
    private PublicProductService publicProductService;

    @InjectMocks
    private PublicDemandTrendService publicDemandTrendService;

    private DemandForecastListDTO activeTrendingDto;
    private DemandForecastListDTO inactiveDto;
    private DemandForecastListDTO outOfStockDto;
    private DemandForecastListDTO stableTrendDto;

    @BeforeEach
    void setUp() {
        activeTrendingDto = new DemandForecastListDTO(
                1L, "SKU-1", "Guitarra A", 10L, "Cat", true, 
                DemandForecastStatus.AVAILABLE, LocalDate.now(), LocalDate.now().plusDays(7),
                "V1", "SMA", 12, new BigDecimal("5.5"), 6, DemandTrend.CRECIENTE, 
                DemandPriority.ALTA, 10, 0, 12, LocalDate.now().minusDays(1), "Msg");

        inactiveDto = new DemandForecastListDTO(
                2L, "SKU-2", "Bajo B", 10L, "Cat", false, 
                DemandForecastStatus.AVAILABLE, LocalDate.now(), LocalDate.now().plusDays(7),
                "V1", "SMA", 12, new BigDecimal("15.0"), 15, DemandTrend.CRECIENTE, 
                DemandPriority.ALTA, 10, 5, 12, LocalDate.now().minusDays(1), "Msg");

        outOfStockDto = new DemandForecastListDTO(
                3L, "SKU-3", "Piano C", 10L, "Cat", true, 
                DemandForecastStatus.AVAILABLE, LocalDate.now(), LocalDate.now().plusDays(7),
                "V1", "SMA", 12, new BigDecimal("8.0"), 8, DemandTrend.CRECIENTE, 
                DemandPriority.ALTA, 0, 8, 12, LocalDate.now().minusDays(1), "Msg");

        stableTrendDto = new DemandForecastListDTO(
                4L, "SKU-4", "Batería D", 10L, "Cat", true, 
                DemandForecastStatus.AVAILABLE, LocalDate.now(), LocalDate.now().plusDays(7),
                "V1", "SMA", 12, new BigDecimal("2.0"), 2, DemandTrend.ESTABLE, 
                DemandPriority.BAJA, 10, 0, 12, LocalDate.now().minusDays(1), "Msg");
    }

    @Test
    void getTrendingProducts_ReturnsOnlyValidProducts() {
        when(demandForecastService.calculateGlobalBatch()).thenReturn(
                List.of(activeTrendingDto, inactiveDto, outOfStockDto, stableTrendDto)
        );

        PublicProductDTO pubDto = new PublicProductDTO(
                1L, "SKU-1", "Guitarra A", "Desc", "Desc2", new BigDecimal("100"), 
                null, 10, "url", List.of(), null, null, List.of(), "mod", 1.0, "dim", false, BigDecimal.ZERO, 0
        );

        when(publicProductService.getById(1L)).thenReturn(pubDto);

        List<PublicTrendingProductDTO> result = publicDemandTrendService.getTrendingProducts(8);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).productId());
        assertEquals("Producto en tendencia", result.get(0).trendLabel());
    }

    @Test
    void getProductTrendStatus_ValidProduct_ReturnsTrue() {
        when(demandForecastService.calculateGlobalBatch()).thenReturn(List.of(activeTrendingDto));
        when(publicProductService.getById(1L)).thenReturn(null); // Solo importa que no lance EntityNotFoundException

        ProductTrendStatusDTO status = publicDemandTrendService.getProductTrendStatus(1L);

        assertTrue(status.trending());
        assertEquals(1L, status.productId());
        assertEquals("Producto en tendencia", status.label());
    }

    @Test
    void getProductTrendStatus_InvalidProduct_ReturnsFalse() {
        when(demandForecastService.calculateGlobalBatch()).thenReturn(List.of(stableTrendDto));
        when(publicProductService.getById(4L)).thenReturn(null);

        ProductTrendStatusDTO status = publicDemandTrendService.getProductTrendStatus(4L);

        assertFalse(status.trending());
        assertNull(status.label());
    }

    @Test
    void getProductTrendStatus_ProductNotFound_ThrowsException() {
        when(publicProductService.getById(99L)).thenThrow(new EntityNotFoundException("Not found"));

        assertThrows(EntityNotFoundException.class, () -> publicDemandTrendService.getProductTrendStatus(99L));
    }

    @Test
    void getTrendingProducts_ArtifactUnavailable_ReturnsEmptyList() {
        when(demandForecastService.calculateGlobalBatch()).thenThrow(new IllegalStateException("Unavailable"));

        List<PublicTrendingProductDTO> result = publicDemandTrendService.getTrendingProducts(8);

        assertTrue(result.isEmpty());
    }
}
