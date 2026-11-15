package com.security.controller;

import com.security.dto.public_api.PublicProductRecommendationDTO;
import com.security.service.PublicProductRecommendationService;
import com.security.service.PublicProductService;
import jakarta.persistence.EntityNotFoundException;
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

}
