package com.security.controller.admin;

import com.security.dto.admin.analytics.forecast.DemandForecastListDTO;
import com.security.enums.DemandForecastStatus;
import com.security.enums.DemandPriority;
import com.security.enums.DemandTrend;
import com.security.service.analytics.DemandForecastService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import com.security.security.CustomUserDetailsService;
import com.security.security.JwtAuthenticationEntryPoint;
import com.security.util.SecurityUtils;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.security.config.MercadoPagoConfiguration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"mercadopago.enabled=false", "mercadopago.access_token=test_token"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminDemandForecastControllerWebMvcTest extends com.security.support.LocalPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DemandForecastService forecastService;

    @MockBean
    private SecurityUtils securityUtils;

    @MockBean
    private MercadoPagoConfiguration mercadoPagoConfiguration;

    @Test
    @WithMockUser(authorities = "REPORT_VIEW")
    void getForecasts_WithPermission_Returns200() throws Exception {
        DemandForecastListDTO dto = new DemandForecastListDTO(
                1L, "SKU1", "Producto", 1L, "Cat", true,
                DemandForecastStatus.AVAILABLE, LocalDate.now(), LocalDate.now().plusDays(7),
                "V1", "SMA", 12, new BigDecimal("10.5"), 11,
                DemandTrend.CRECIENTE, DemandPriority.ALTA, 5, 6, 12, LocalDate.now().minusDays(1), "msg"
        );
        Page<DemandForecastListDTO> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 10), 1);

        Mockito.when(forecastService.getPaginatedForecasts(
                any(), any(), any(), any(), any(), any(), any(Pageable.class)
        )).thenReturn(page);

        mockMvc.perform(get("/api/admin/analytics/demand-forecast")
                .param("page", "0")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].productSku").value("SKU1"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    @WithMockUser(authorities = "OTHER_PERMISSION")
    void getForecasts_WithoutPermission_Returns403() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/demand-forecast")
                .param("page", "0")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
