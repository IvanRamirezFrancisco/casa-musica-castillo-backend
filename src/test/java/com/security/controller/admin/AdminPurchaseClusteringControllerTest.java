package com.security.controller.admin;

import com.security.dto.admin.analytics.*;
import com.security.exception.BadRequestException;
import com.security.exception.ResourceNotFoundException;
import com.security.service.analytics.PurchaseClusteringAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Pruebas del controlador {@link AdminPurchaseClusteringController}.
 *
 * <p>
 * Se usa Mockito puro (sin contexto Spring) para:
 * <ul>
 *   <li>Verificar que cada endpoint delega correctamente al servicio.</li>
 *   <li>Verificar que los endpoints retornan 200 con el DTO correcto.</li>
 *   <li>Verificar que las excepciones del servicio se propagan sin ser suprimidas.</li>
 *   <li>Verificar que la anotación {@code @PreAuthorize("hasAuthority('REPORT_VIEW')")}
 *       está presente en la clase (seguridad declarativa).</li>
 * </ul>
 *
 * <p>
 * <strong>Nota sobre las pruebas de seguridad:</strong> La evaluación efectiva
 * de {@code @PreAuthorize} requiere el contexto de Spring Security en ejecución.
 * Se prueba la <em>presencia de la declaración</em> en la clase y se confía en
 * que {@code @EnableMethodSecurity(prePostEnabled = true)} en {@code SecurityConfig}
 * la evalúa correctamente en producción. Las pruebas de integración contra la API
 * real con Postman completan la verificación de seguridad en tiempo de ejecución.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminPurchaseClusteringController")
class AdminPurchaseClusteringControllerTest {

    @Mock
    private PurchaseClusteringAnalyticsService clusteringService;

    @InjectMocks
    private AdminPurchaseClusteringController controller;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PurchaseClusteringSummaryDTO buildSummary() {
        PurchaseClusteringIntegrityDTO integrity = new PurchaseClusteringIntegrityDTO(
                5, 5L, 1000, 1000L, 1000L, new BigDecimal("100.0000"), true);

        return new PurchaseClusteringSummaryDTO(
                1L, "v1.0", "KMEANS", (short) 5, 1000, (short) 10,
                "vw_clustering_tipos_compra_v2", "ACADEMIC_SYNTHETIC",
                LocalDateTime.now().minusMonths(1), LocalDateTime.now(),
                new BigDecimal("3408.20"), new BigDecimal("0.4367"),
                new BigDecimal("0.9584"), new BigDecimal("481.11"),
                List.of("total", "units"), Map.of("scaler", "standard"),
                "notebook_v1.ipynb", "COMPLETED", true, OffsetDateTime.now(),
                true, "Análisis académico...", integrity
        );
    }

    private PurchaseClusterProfileDTO buildProfile(short num) {
        return new PurchaseClusterProfileDTO(
                num, "Cluster " + num, "Desc", 200, new BigDecimal("20.0"),
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                BigDecimal.valueOf(2), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(2),
                BigDecimal.ZERO, BigDecimal.valueOf(2), BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("30.0"), "CARD", "SHIPPING", "Acción"
        );
    }

    private PurchaseClusterOrdersPageDTO buildOrdersPage() {
        PurchaseClusterOrderDTO order = new PurchaseClusterOrderDTO(
                101L, "ORD-101", LocalDateTime.now(),
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO, 2, 1, 1, BigDecimal.valueOf(2),
                0, 2, 2, 0, 0, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.valueOf(5), false, "CARD", "SHIPPING", (short) 2,
                "Instrumento individual", "Upsell"
        );
        return new PurchaseClusterOrdersPageDTO(
                List.of(order), 0, 20, 338L, 17, true, false, false
        );
    }

    // ── Tests de seguridad declarativa ────────────────────────────────────────

    @Nested
    @DisplayName("Seguridad")
    class SecurityTests {

        /**
         * 1. Verifica que la clase tiene @PreAuthorize("hasAuthority('REPORT_VIEW')").
         * Este es el contrato declarativo que Spring Security evalúa en runtime
         * gracias a @EnableMethodSecurity.
         */
        @Test
        @DisplayName("1. La clase declara @PreAuthorize con REPORT_VIEW")
        void classHasPreAuthorizeWithReportView() {
            PreAuthorize annotation = AdminPurchaseClusteringController.class
                    .getAnnotation(PreAuthorize.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).contains("REPORT_VIEW");
        }

        /**
         * 2. Usuario con autoridad REPORT_VIEW: el controlador delega al servicio
         * y retorna 200. La verificación de autoridad en sí se hace en runtime
         * por Spring Security.
         */
        @Test
        @DisplayName("2. Endpoint /active retorna 200 y delega al servicio")
        void activeSummaryDelegatesAndReturns200() {
            when(clusteringService.getActiveSummary()).thenReturn(buildSummary());

            ResponseEntity<PurchaseClusteringSummaryDTO> response = controller.getActiveSummary();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().runId()).isEqualTo(1L);
            verify(clusteringService, times(1)).getActiveSummary();
        }

        /**
         * 3. Excepciones del servicio se propagan al manejador global de excepciones
         * (no son capturadas en el controlador).
         */
        @Test
        @DisplayName("3. ResourceNotFoundException se propaga sin ser capturada en el controlador")
        void resourceNotFoundExceptionPropagates() {
            when(clusteringService.getActiveSummary())
                    .thenThrow(new ResourceNotFoundException("No existe ejecución activa."));

            assertThatThrownBy(() -> controller.getActiveSummary())
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── Tests de endpoints ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Endpoints")
    class EndpointTests {

        /**
         * 4. Parámetros válidos de paginación son delegados al servicio.
         */
        @Test
        @DisplayName("4. Paginación válida → delegado al servicio y 200")
        void validPaginationDelegatesAndReturns200() {
            when(clusteringService.getActiveClusterOrders((short) 2, 0, 20))
                    .thenReturn(buildOrdersPage());

            ResponseEntity<PurchaseClusterOrdersPageDTO> response =
                    controller.getActiveClusterOrders((short) 2, 0, 20);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().totalElements()).isEqualTo(338);
            verify(clusteringService).getActiveClusterOrders((short) 2, 0, 20);
        }

        /**
         * 5. Cluster inexistente: el servicio lanza ResourceNotFoundException
         * y el controlador la propaga al GlobalExceptionHandler.
         */
        @Test
        @DisplayName("5. Cluster inexistente → ResourceNotFoundException propagada")
        void nonExistentClusterPropagatesNotFoundException() {
            when(clusteringService.getActiveProfile((short) 9))
                    .thenThrow(new ResourceNotFoundException("El cluster solicitado no existe en la ejecución activa."));

            assertThatThrownBy(() -> controller.getActiveProfile((short) 9))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("cluster");
        }

        /**
         * 6. Tamaño de página inválido: el servicio lanza BadRequestException
         * y el controlador la propaga al GlobalExceptionHandler → HTTP 400.
         */
        @Test
        @DisplayName("6. size > 100 → BadRequestException propagada")
        void invalidSizePropagatesBadRequestException() {
            when(clusteringService.getActiveClusterOrders((short) 0, 0, 200))
                    .thenThrow(new BadRequestException("El tamaño de página debe estar entre 1 y 100."));

            assertThatThrownBy(() -> controller.getActiveClusterOrders((short) 0, 0, 200))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("tamaño");
        }

        @Test
        @DisplayName("GET /active/profiles retorna lista y delega al servicio")
        void profilesEndpointDelegatesAndReturns200() {
            when(clusteringService.getActiveProfiles())
                    .thenReturn(List.of(buildProfile((short) 0), buildProfile((short) 1)));

            ResponseEntity<List<PurchaseClusterProfileDTO>> response = controller.getActiveProfiles();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).hasSize(2);
        }

        @Test
        @DisplayName("GET /active/profiles/{n} retorna perfil y delega al servicio")
        void singleProfileDelegatesAndReturns200() {
            when(clusteringService.getActiveProfile((short) 2)).thenReturn(buildProfile((short) 2));

            ResponseEntity<PurchaseClusterProfileDTO> response = controller.getActiveProfile((short) 2);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().clusterNumber()).isEqualTo((short) 2);
        }
    }
    @Nested
    @DisplayName("Endpoint: GET /api/admin/analytics/purchase-clustering/orders/{orderId}/prediction")
    class PredictClusterForOrderEndpoint {

        @Test
        @DisplayName("14. El endpoint delega al servicio y retorna 200 con la predicción")
        void testPredictClusterForOrder() {
            PurchaseOrderPredictionDTO prediction = new PurchaseOrderPredictionDTO();
            prediction.setPredictedCluster((short) 1);
            
            when(clusteringService.predictClusterForOrder(1L)).thenReturn(prediction);

            ResponseEntity<PurchaseOrderPredictionDTO> response = controller.predictClusterForOrder(1L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isSameAs(prediction);
            verify(clusteringService, times(1)).predictClusterForOrder(1L);
        }
    }

    @Nested
    @DisplayName("Endpoint: GET /api/admin/analytics/purchase-clustering/model/status")
    class GetModelStatusEndpoint {

        @Test
        @DisplayName("Retorna el estado del modelo cargado")
        void testGetModelStatus() {
            PurchaseClusteringModelStatusDTO status = new PurchaseClusteringModelStatusDTO();
            status.setAlgoritmoNombre("KMeans");
            
            when(clusteringService.getModelStatus()).thenReturn(status);

            ResponseEntity<PurchaseClusteringModelStatusDTO> response = controller.getModelStatus();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isSameAs(status);
            verify(clusteringService, times(1)).getModelStatus();
        }
    }
}
