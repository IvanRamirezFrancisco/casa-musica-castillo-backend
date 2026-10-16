package com.security.service;

import com.security.dto.public_api.PublicProductRecommendationDTO;
import com.security.repository.PublicProductRecommendationRepository;
import com.security.repository.projection.ProductRecommendationProjection;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicProductRecommendationServiceTest {

    @Mock
    private PublicProductRecommendationRepository recommendationRepository;

    @Mock
    private PublicProductService publicProductService;

    @InjectMocks
    private PublicProductRecommendationService recommendationService;

    private ProductRecommendationProjection mockProjection(Long id, String name, BigDecimal regular, BigDecimal discount, Boolean hasDiscount) {
        ProductRecommendationProjection projection = mock(ProductRecommendationProjection.class);
        lenient().when(projection.getProductId()).thenReturn(id);
        lenient().when(projection.getSku()).thenReturn("SKU-" + id);
        lenient().when(projection.getName()).thenReturn(name);
        lenient().when(projection.getRegularPrice()).thenReturn(regular);
        lenient().when(projection.getEffectivePrice()).thenReturn(discount != null ? discount : regular);
        lenient().when(projection.getDiscountPrice()).thenReturn(discount);
        lenient().when(projection.getHasDiscount()).thenReturn(hasDiscount);
        return projection;
    }

    @Test
    void getRecommendations_ProductExists_ReturnsRecommendations() {
        Long productId = 294L;
        
        // Mock valid product existence
        when(publicProductService.getById(productId)).thenReturn(null); // Just needs to not throw

        ProductRecommendationProjection proj42 = mockProjection(42L, "Afinador de Pinza", new BigDecimal("350.00"), null, false);
        when(recommendationRepository.findRecommendationsByProductId(productId)).thenReturn(List.of(proj42));

        List<PublicProductRecommendationDTO> result = recommendationService.getRecommendations(productId);

        assertEquals(1, result.size());
        assertEquals(42L, result.get(0).productId());
        assertEquals("Frecuentemente comprado junto con este producto", result.get(0).recommendationReason());
        assertFalse(result.get(0).hasDiscount());
        
        verify(publicProductService, times(1)).getById(productId);
        verify(recommendationRepository, times(1)).findRecommendationsByProductId(productId);
    }

    @Test
    void getRecommendations_ProductExists_ReturnsMultipleOrdered() {
        Long productId = 290L;
        when(publicProductService.getById(productId)).thenReturn(null);

        ProductRecommendationProjection proj52 = mockProjection(52L, "Atril", new BigDecimal("100.00"), null, false);
        ProductRecommendationProjection proj42 = mockProjection(42L, "Afinador", new BigDecimal("350.00"), null, false);
        
        when(recommendationRepository.findRecommendationsByProductId(productId)).thenReturn(List.of(proj52, proj42));

        List<PublicProductRecommendationDTO> result = recommendationService.getRecommendations(productId);

        assertEquals(2, result.size());
        assertEquals(52L, result.get(0).productId());
        assertEquals(42L, result.get(1).productId());
    }

    @Test
    void getRecommendations_ProductWithoutRecommendations_ReturnsEmptyList() {
        Long productId = 999L;
        when(publicProductService.getById(productId)).thenReturn(null);
        when(recommendationRepository.findRecommendationsByProductId(productId)).thenReturn(Collections.emptyList());

        List<PublicProductRecommendationDTO> result = recommendationService.getRecommendations(productId);

        assertTrue(result.isEmpty());
    }

    @Test
    void getRecommendations_ProductNotFound_ThrowsException() {
        Long productId = 1000L;
        when(publicProductService.getById(productId)).thenThrow(new EntityNotFoundException("Not found"));

        assertThrows(EntityNotFoundException.class, () -> recommendationService.getRecommendations(productId));
        verify(recommendationRepository, never()).findRecommendationsByProductId(anyLong());
    }
}
