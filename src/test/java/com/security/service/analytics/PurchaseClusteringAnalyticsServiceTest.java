package com.security.service.analytics;

import com.security.dto.admin.analytics.*;
import com.security.entity.PurchaseClusterProfile;
import com.security.entity.PurchaseClusteringRun;
import com.security.exception.BadRequestException;
import com.security.exception.ResourceNotFoundException;
import com.security.repository.PurchaseClusterProfileRepository;
import com.security.repository.PurchaseClusteringRunRepository;
import com.security.repository.PurchaseOrderClusterRepository;
import com.security.repository.projection.PurchaseClusterOrderProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pruebas unitarias de {@link PurchaseClusteringAnalyticsService}.
 *
 * <p>
 * Se usa Mockito para aislar el servicio de los repositorios.
 * No se conecta a base de datos real ni a H2.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseClusteringAnalyticsService")
class PurchaseClusteringAnalyticsServiceTest {

    @Mock
    private PurchaseClusteringRunRepository runRepository;

    @Mock
    private PurchaseClusterProfileRepository profileRepository;

    @Mock
    private PurchaseOrderClusterRepository orderClusterRepository;

    @InjectMocks
    private PurchaseClusteringAnalyticsService service;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PurchaseClusteringRun buildRun(Long id, int k, int orders, String origin) {
        PurchaseClusteringRun run = new PurchaseClusteringRun();
        ReflectionTestUtils.setField(run, "id", id);
        ReflectionTestUtils.setField(run, "modelVersion", "v1.0");
        ReflectionTestUtils.setField(run, "algorithm", "KMEANS");
        ReflectionTestUtils.setField(run, "selectedK", (short) k);
        ReflectionTestUtils.setField(run, "ordersAnalyzed", orders);
        ReflectionTestUtils.setField(run, "featureCount", (short) 10);
        ReflectionTestUtils.setField(run, "datasetView", "vw_clustering_tipos_compra_v2");
        ReflectionTestUtils.setField(run, "dataOrigin", origin);
        ReflectionTestUtils.setField(run, "periodStart", LocalDateTime.now().minusMonths(1));
        ReflectionTestUtils.setField(run, "periodEnd", LocalDateTime.now());
        ReflectionTestUtils.setField(run, "status", "COMPLETED");
        ReflectionTestUtils.setField(run, "active", true);
        ReflectionTestUtils.setField(run, "executedAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(run, "createdAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(run, "featureNames", List.of("total", "units"));
        ReflectionTestUtils.setField(run, "preprocessingConfig", java.util.Map.of("scaler", "standard"));
        ReflectionTestUtils.setField(run, "inertia", new BigDecimal("3408.20170415"));
        ReflectionTestUtils.setField(run, "silhouetteScore", new BigDecimal("0.43673264"));
        ReflectionTestUtils.setField(run, "daviesBouldinScore", new BigDecimal("0.95838575"));
        ReflectionTestUtils.setField(run, "calinskiHarabaszScore", new BigDecimal("481.10703780"));
        return run;
    }

    private PurchaseClusterProfile buildProfile(Long id, Short clusterNumber, int orderCount, BigDecimal pct) {
        PurchaseClusterProfile p = new PurchaseClusterProfile();
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "clusterNumber", clusterNumber);
        ReflectionTestUtils.setField(p, "clusterName", "Cluster " + clusterNumber);
        ReflectionTestUtils.setField(p, "clusterDescription", "Desc " + clusterNumber);
        ReflectionTestUtils.setField(p, "orderCount", orderCount);
        ReflectionTestUtils.setField(p, "percentage", pct);
        ReflectionTestUtils.setField(p, "recommendedAction", "Acción " + clusterNumber);
        ReflectionTestUtils.setField(p, "averageTotal", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageDiscountPct", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageShippingPct", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageUnitPrice", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageTotalUnits", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageUniqueProducts", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageUniqueCategories", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageUnitsPerProduct", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageAccessoryUnits", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageInstrumentUnits", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageViolinUnits", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageJaranaUnits", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageQuintaUnits", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageAccessoryValueShare", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageInstrumentValueShare", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "averageLargestProductValueShare", BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "weekendPercentage", BigDecimal.ZERO);
        return p;
    }

    private PurchaseClusterOrderProjection buildProjection(long orderId) {
        return new PurchaseClusterOrderProjection() {
            public Long getOrderId()               { return orderId; }
            public String getOrderNumber()          { return "ORD-" + orderId; }
            public LocalDateTime getCreatedAt()     { return LocalDateTime.now(); }
            public BigDecimal getSubtotal()         { return BigDecimal.TEN; }
            public BigDecimal getDiscount()         { return BigDecimal.ZERO; }
            public BigDecimal getShipping()         { return BigDecimal.ZERO; }
            public BigDecimal getTotal()            { return BigDecimal.TEN; }
            public BigDecimal getDiscountPct()      { return BigDecimal.ZERO; }
            public BigDecimal getShippingPct()      { return BigDecimal.ZERO; }
            public Integer getTotalUnits()          { return 2; }
            public Integer getUniqueProducts()      { return 1; }
            public Integer getUniqueCategories()    { return 1; }
            public BigDecimal getUnitsPerProduct()  { return BigDecimal.valueOf(2); }
            public Integer getAccessoryUnits()      { return 0; }
            public Integer getInstrumentUnits()     { return 2; }
            public Integer getViolinUnits()         { return 2; }
            public Integer getJaranaUnits()         { return 0; }
            public Integer getQuintaUnits()         { return 0; }
            public BigDecimal getAccessoryValueShare()         { return BigDecimal.ZERO; }
            public BigDecimal getInstrumentValueShare()        { return BigDecimal.ONE; }
            public BigDecimal getLargestProductValueShare()    { return BigDecimal.ONE; }
            public BigDecimal getAverageUnitPrice() { return BigDecimal.valueOf(5); }
            public Boolean getWeekend()             { return false; }
            public String getPaymentMethod()        { return "CARD"; }
            public String getDeliveryType()         { return "SHIPPING"; }
            public Short getClusterNumber()         { return 2; }
            public String getClusterName()          { return "Instrumento individual"; }
            public String getRecommendedAction()    { return "Upsell accesorios"; }
        };
    }

    // ── Tests de getActiveSummary ─────────────────────────────────────────────

    @Nested
    @DisplayName("getActiveSummary()")
    class GetActiveSummaryTests {

        @Test
        @DisplayName("1. Retorna el resumen cuando existe ejecución activa y completada")
        void returnsCorrectSummaryForActiveRun() {
            PurchaseClusteringRun run = buildRun(42L, 5, 1000, "ACADEMIC_SYNTHETIC");
            when(runRepository.findFirstByActiveTrueAndStatusOrderByExecutedAtDesc("COMPLETED"))
                    .thenReturn(Optional.of(run));
            when(profileRepository.countByRunId(42L)).thenReturn(5L);
            when(orderClusterRepository.countByRunId(42L)).thenReturn(1000L);
            when(profileRepository.sumOrderCountByRunId(42L)).thenReturn(1000L);
            when(profileRepository.sumPercentageByRunId(42L)).thenReturn(new BigDecimal("100.0000"));

            PurchaseClusteringSummaryDTO dto = service.getActiveSummary();

            assertThat(dto.runId()).isEqualTo(42L);
            assertThat(dto.selectedK()).isEqualTo((short) 5);
            assertThat(dto.academicData()).isTrue();
            assertThat(dto.dataDisclaimer()).isNotBlank();
            assertThat(dto.integrity().consistent()).isTrue();
            assertThat(dto.integrity().expectedClusters()).isEqualTo(5);
            assertThat(dto.integrity().actualProfiles()).isEqualTo(5L);
        }

        @Test
        @DisplayName("2. Lanza ResourceNotFoundException cuando no existe ejecución activa")
        void throwsWhenNoActiveRun() {
            when(runRepository.findFirstByActiveTrueAndStatusOrderByExecutedAtDesc("COMPLETED"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getActiveSummary())
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("clustering");
        }
    }

    // ── Tests de getActiveProfiles ────────────────────────────────────────────

    @Nested
    @DisplayName("getActiveProfiles()")
    class GetActiveProfilesTests {

        @Test
        @DisplayName("3. Retorna perfiles ordenados por clusterNumber")
        void returnsProfilesOrdered() {
            PurchaseClusteringRun run = buildRun(1L, 2, 100, "PRODUCTION");
            when(runRepository.findFirstByActiveTrueAndStatusOrderByExecutedAtDesc("COMPLETED"))
                    .thenReturn(Optional.of(run));

            PurchaseClusterProfile p0 = buildProfile(1L, (short) 0, 40, new BigDecimal("40.0"));
            PurchaseClusterProfile p1 = buildProfile(2L, (short) 1, 60, new BigDecimal("60.0"));
            when(profileRepository.findByRunIdOrderByClusterNumberAsc(1L))
                    .thenReturn(List.of(p0, p1));

            List<PurchaseClusterProfileDTO> result = service.getActiveProfiles();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).clusterNumber()).isEqualTo((short) 0);
            assertThat(result.get(1).clusterNumber()).isEqualTo((short) 1);
        }
    }

    // ── Tests de getActiveProfile ─────────────────────────────────────────────

    @Nested
    @DisplayName("getActiveProfile(clusterNumber)")
    class GetActiveProfileTests {

        @Test
        @DisplayName("4. Retorna el perfil de un cluster existente")
        void returnsExistingProfile() {
            PurchaseClusteringRun run = buildRun(1L, 5, 1000, "ACADEMIC_SYNTHETIC");
            when(runRepository.findFirstByActiveTrueAndStatusOrderByExecutedAtDesc("COMPLETED"))
                    .thenReturn(Optional.of(run));

            PurchaseClusterProfile profile = buildProfile(3L, (short) 2, 338, new BigDecimal("33.8"));
            when(profileRepository.findByRunIdAndClusterNumber(1L, (short) 2))
                    .thenReturn(Optional.of(profile));

            PurchaseClusterProfileDTO dto = service.getActiveProfile((short) 2);

            assertThat(dto.clusterNumber()).isEqualTo((short) 2);
            assertThat(dto.orderCount()).isEqualTo(338);
        }

        @Test
        @DisplayName("5. Lanza ResourceNotFoundException para cluster inexistente")
        void throwsForNonExistentCluster() {
            PurchaseClusteringRun run = buildRun(1L, 5, 1000, "ACADEMIC_SYNTHETIC");
            when(runRepository.findFirstByActiveTrueAndStatusOrderByExecutedAtDesc("COMPLETED"))
                    .thenReturn(Optional.of(run));
            when(profileRepository.findByRunIdAndClusterNumber(1L, (short) 9))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getActiveProfile((short) 9))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("cluster");
        }

        @Test
        @DisplayName("6. Rechaza clusterNumber negativo")
        void rejectsNegativeClusterNumber() {
            assertThatThrownBy(() -> service.getActiveProfile((short) -1))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("negativo");
        }
    }

    // ── Tests de validación de paginación ────────────────────────────────────

    @Nested
    @DisplayName("Validación de paginación en getActiveClusterOrders()")
    class PaginationValidationTests {

        @Test
        @DisplayName("7. Rechaza page negativo")
        void rejectsNegativePage() {
            assertThatThrownBy(() -> service.getActiveClusterOrders((short) 0, -1, 20))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("página");
        }

        @Test
        @DisplayName("8. Rechaza size = 0")
        void rejectsSizeZero() {
            assertThatThrownBy(() -> service.getActiveClusterOrders((short) 0, 0, 0))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("tamaño");
        }

        @Test
        @DisplayName("9. Rechaza size > 100")
        void rejectsSizeOver100() {
            assertThatThrownBy(() -> service.getActiveClusterOrders((short) 0, 0, 101))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("tamaño");
        }
    }

    // ── Tests de mapeo y paginación ───────────────────────────────────────────

    @Nested
    @DisplayName("getActiveClusterOrders() — mapeo y paginación")
    class GetClusterOrdersTests {

        @Test
        @DisplayName("10. Mapea correctamente una página de proyecciones")
        void mapsProjectionsCorrectly() {
            PurchaseClusteringRun run = buildRun(1L, 5, 1000, "ACADEMIC_SYNTHETIC");
            when(runRepository.findFirstByActiveTrueAndStatusOrderByExecutedAtDesc("COMPLETED"))
                    .thenReturn(Optional.of(run));

            PurchaseClusterProfile profile = buildProfile(3L, (short) 2, 338, new BigDecimal("33.8"));
            when(profileRepository.findByRunIdAndClusterNumber(1L, (short) 2))
                    .thenReturn(Optional.of(profile));

            PurchaseClusterOrderProjection proj = buildProjection(501L);
            Page<PurchaseClusterOrderProjection> page = new PageImpl<>(
                    List.of(proj), PageRequest.of(0, 20), 338);
            when(orderClusterRepository.findOrdersByRunIdAndClusterNumber(eq(1L), eq((short) 2), any()))
                    .thenReturn(page);

            PurchaseClusterOrdersPageDTO result = service.getActiveClusterOrders((short) 2, 0, 20);

            assertThat(result.content()).hasSize(1);
            assertThat(result.totalElements()).isEqualTo(338);
            assertThat(result.page()).isEqualTo(0);
            assertThat(result.size()).isEqualTo(20);

            PurchaseClusterOrderDTO order = result.content().get(0);
            assertThat(order.orderId()).isEqualTo(501L);
            assertThat(order.orderNumber()).isEqualTo("ORD-501");
            assertThat(order.clusterName()).isEqualTo("Instrumento individual");
            assertThat(order.weekend()).isFalse();
        }
    }

    // ── Tests de integridad ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Integridad (buildIntegrity)")
    class IntegrityTests {

        private void stubIntegrity(Long runId, long profiles, long assigned,
                                   long orderSum, BigDecimal pctSum) {
            when(profileRepository.countByRunId(runId)).thenReturn(profiles);
            when(orderClusterRepository.countByRunId(runId)).thenReturn(assigned);
            when(profileRepository.sumOrderCountByRunId(runId)).thenReturn(orderSum);
            when(profileRepository.sumPercentageByRunId(runId)).thenReturn(pctSum);
        }

        @Test
        @DisplayName("11. consistent = true cuando todos los conteos coinciden")
        void consistentTrueWhenAllMatch() {
            PurchaseClusteringRun run = buildRun(1L, 5, 1000, "ACADEMIC_SYNTHETIC");
            when(runRepository.findFirstByActiveTrueAndStatusOrderByExecutedAtDesc("COMPLETED"))
                    .thenReturn(Optional.of(run));
            stubIntegrity(1L, 5L, 1000L, 1000L, new BigDecimal("100.0000"));

            PurchaseClusteringSummaryDTO dto = service.getActiveSummary();

            assertThat(dto.integrity().consistent()).isTrue();
        }

        @Test
        @DisplayName("12. consistent = false cuando existen diferencias en conteos")
        void consistentFalseWhenMismatch() {
            PurchaseClusteringRun run = buildRun(1L, 5, 1000, "ACADEMIC_SYNTHETIC");
            when(runRepository.findFirstByActiveTrueAndStatusOrderByExecutedAtDesc("COMPLETED"))
                    .thenReturn(Optional.of(run));
            // Solo hay 4 perfiles pero se esperan 5
            stubIntegrity(1L, 4L, 1000L, 1000L, new BigDecimal("100.0000"));

            PurchaseClusteringSummaryDTO dto = service.getActiveSummary();

            assertThat(dto.integrity().consistent()).isFalse();
        }
    }
}
