package com.security.controller.admin;

import com.security.dto.admin.analytics.forecast.DemandForecastDetailDTO;
import com.security.dto.admin.analytics.forecast.DemandForecastListDTO;
import com.security.dto.admin.analytics.forecast.DemandForecastModelStatusDTO;
import com.security.enums.DemandForecastStatus;
import com.security.service.analytics.DemandForecastService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class AdminDemandForecastControllerTest {

    @Mock
    private DemandForecastService forecastService;

    @InjectMocks
    private AdminDemandForecastController controller;

    @Test
    void testGetForecasts_Returns200() {
        DemandForecastListDTO dto = new DemandForecastListDTO(
                1L, "SKU", "Name", 1L, "Cat", true,
                DemandForecastStatus.INSUFFICIENT_HISTORY, null, null,
                "V1", "SMA", 12, null, null,
                null, null, 10, null, 0, null, "Sin ventas"
        );
        Page<DemandForecastListDTO> page = new PageImpl<>(List.of(dto));
        
        Mockito.when(forecastService.getPaginatedForecasts(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
               .thenReturn(page);
               
        ResponseEntity<Page<DemandForecastListDTO>> response = controller.getForecasts(null, null, null, null, null, null, Pageable.unpaged());
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Objects.requireNonNull(response.getBody()).getContent()).hasSize(1);
    }

    @Test
    void testGetForecastDetail_NotFound() {
        Mockito.when(forecastService.getForecastDetail(eq(99L))).thenThrow(new RuntimeException("Producto no encontrado."));
        
        ResponseEntity<DemandForecastDetailDTO> response = controller.getForecastDetail(99L);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    
    @Test
    void testHasPreAuthorizeAnnotation() {
        PreAuthorize annotation = AdminDemandForecastController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasAuthority('REPORT_VIEW') or hasRole('SUPER_ADMIN')");
    }
}
