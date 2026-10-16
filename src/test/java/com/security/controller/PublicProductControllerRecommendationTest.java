package com.security.controller;

import com.security.dto.public_api.PublicProductRecommendationDTO;
import com.security.service.PublicProductRecommendationService;
import com.security.service.PublicProductService;
import com.security.service.PublicDemandTrendService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicProductControllerRecommendationTest {

    @Mock
    private PublicProductRecommendationService recommendationService;

    @Mock
    private PublicProductService publicProductService;

    @Mock
    private PublicDemandTrendService publicDemandTrendService;

    @InjectMocks
    private PublicProductController controller;

    @Test
    void getRecommendations_Success_Returns200AndList() {
        Long productId = 294L;
        PublicProductRecommendationDTO dto = new PublicProductRecommendationDTO(
                42L, "SKU-42", "Afinador", "Desc", "url", 1L, "Cat",
                new BigDecimal("350.00"), new BigDecimal("350.00"), null, 25, false, "Razón"
        );
        
        when(recommendationService.getRecommendations(productId)).thenReturn(List.of(dto));

        ResponseEntity<List<PublicProductRecommendationDTO>> response = controller.getRecommendations(productId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals(42L, response.getBody().get(0).productId());
    }

    @Test
    void getRecommendations_Empty_Returns200AndEmptyList() {
        Long productId = 999L;
        when(recommendationService.getRecommendations(productId)).thenReturn(Collections.emptyList());

        ResponseEntity<List<PublicProductRecommendationDTO>> response = controller.getRecommendations(productId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void getRecommendations_NotFound_Returns404() {
        Long productId = 1000L;
        when(recommendationService.getRecommendations(productId)).thenThrow(new EntityNotFoundException("Not found"));

        ResponseEntity<List<PublicProductRecommendationDTO>> response = controller.getRecommendations(productId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void getTrendingProducts_Success_ReturnsList() {
        com.security.dto.public_api.PublicTrendingProductDTO dto = new com.security.dto.public_api.PublicTrendingProductDTO(
                1L, "SKU", "Name", "Desc", "url", 1L, "Cat", BigDecimal.TEN, BigDecimal.TEN, null, false, "Label", "Reason"
        );
        when(publicDemandTrendService.getTrendingProducts(8)).thenReturn(List.of(dto));

        ResponseEntity<List<com.security.dto.public_api.PublicTrendingProductDTO>> response = controller.getTrendingProducts(8);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void getTrendStatus_Success_ReturnsStatus() {
        com.security.dto.public_api.ProductTrendStatusDTO dto = new com.security.dto.public_api.ProductTrendStatusDTO(
                1L, true, "Label", "Reason"
        );
        when(publicDemandTrendService.getProductTrendStatus(1L)).thenReturn(dto);

        ResponseEntity<com.security.dto.public_api.ProductTrendStatusDTO> response = controller.getTrendStatus(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().trending());
    }

    @Test
    void getTrendStatus_NotFound_Returns404() {
        when(publicDemandTrendService.getProductTrendStatus(99L)).thenThrow(new EntityNotFoundException());

        ResponseEntity<com.security.dto.public_api.ProductTrendStatusDTO> response = controller.getTrendStatus(99L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
