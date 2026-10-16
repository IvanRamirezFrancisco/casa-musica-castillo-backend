package com.security.service.analytics;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.dto.admin.analytics.PurchaseOrderPredictionDTO;
import com.security.dto.admin.analytics.clustering.*;
import com.security.entity.PurchaseClusteringRun;
import com.security.entity.PurchaseOrderCluster;
import com.security.exception.ResourceNotFoundException;
import com.security.repository.PurchaseClusterProfileRepository;
import com.security.repository.PurchaseClusteringRunRepository;
import com.security.repository.PurchaseOrderClusterRepository;
import com.security.repository.projection.PurchaseOrderFeatureProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseClusteringPredictionTest {

    @Mock
    private PurchaseClusteringRunRepository runRepository;
    @Mock
    private PurchaseClusterProfileRepository profileRepository;
    @Mock
    private PurchaseOrderClusterRepository orderClusterRepository;

    private ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // 1. El artefacto carga correctamente (usa el real)
    @Test
    void testInit_WithRealArtifact_LoadsCorrectly() {
        PurchaseClusteringAnalyticsService service = new PurchaseClusteringAnalyticsService(
                runRepository, profileRepository, orderClusterRepository,
                objectMapper, new DefaultResourceLoader()
        );
        service.init();
        assertThat(service.getModelStatus().getAlgoritmoNombre()).isEqualTo("KMeans");
        assertThat(service.getModelStatus().getNumeroClusters()).isEqualTo(5);
    }

    private PurchaseClusteringAnalyticsService createServiceWithJson(String json) {
        ResourceLoader mockResourceLoader = new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
            }
            @Override
            public ClassLoader getClassLoader() {
                return null;
            }
        };
        return new PurchaseClusteringAnalyticsService(
                runRepository, profileRepository, orderClusterRepository,
                objectMapper, mockResourceLoader
        );
    }

    private String getValidJson() {
        return """
        {
          "version_esquema": 1,
          "version_modelo": "3.0",
          "fecha_generacion_utc": "2026-08-02",
          "algoritmo": { "nombre": "KMeans", "numero_clusters": 5 },
          "entrada": {
            "unidad_analisis": "Orden",
            "caracteristicas": [ "total", "porcentaje_descuento", "porcentaje_envio", "unidades_totales", "productos_distintos", "categorias_distintas", "unidades_por_producto", "proporcion_valor_accesorios", "proporcion_producto_mayor_valor", "precio_unitario_promedio" ],
            "columnas_log1p": [ "total", "precio_unitario_promedio", "porcentaje_envio", "unidades_por_producto" ]
          },
          "escalador": {
            "tipo": "StandardScaler",
            "media": [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0],
            "escala": [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0]
          },
          "modelo": {
            "centroides_escalados": [
              [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
              [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0],
              [2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0],
              [3.0, 3.0, 3.0, 3.0, 3.0, 3.0, 3.0, 3.0, 3.0, 3.0],
              [4.0, 4.0, 4.0, 4.0, 4.0, 4.0, 4.0, 4.0, 4.0, 4.0]
            ]
          },
          "perfiles_clusters": {
            "0": { "nombre": "C0", "descripcion": "Desc0", "accion_sugerida": "Acc0" },
            "1": { "nombre": "C1", "descripcion": "Desc1", "accion_sugerida": "Acc1" },
            "2": { "nombre": "C2", "descripcion": "Desc2", "accion_sugerida": "Acc2" },
            "3": { "nombre": "C3", "descripcion": "Desc3", "accion_sugerida": "Acc3" },
            "4": { "nombre": "C4", "descripcion": "Desc4", "accion_sugerida": "Acc4" }
          }
        }
        """;
    }

    // 2. Falla si no tiene diez medias
    @Test
    void testInit_FailsIfNot10Means() {
        String json = getValidJson().replace("\"media\": [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0]", "\"media\": [1.0]");
        PurchaseClusteringAnalyticsService service = createServiceWithJson(json);
        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .cause().hasMessageContaining("La media debe ser de tamaño 10");
    }

    // 3. Falla si una escala es cero
    @Test
    void testInit_FailsIfScaleIsZero() {
        String json = getValidJson().replace("\"escala\": [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0]", "\"escala\": [0.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0]");
        PurchaseClusteringAnalyticsService service = createServiceWithJson(json);
        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .cause().hasMessageContaining("finita y > 0");
    }

    // 4. Falla si un centroide no tiene diez dimensiones
    @Test
    void testInit_FailsIfCentroidNot10Dimensions() {
        String json = getValidJson().replace("[0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]", "[0.0]");
        PurchaseClusteringAnalyticsService service = createServiceWithJson(json);
        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .cause().hasMessageContaining("Cada centroide debe ser de tamaño 10");
    }

    // 5. Falla si falta un perfil
    @Test
    void testInit_FailsIfMissingProfile() {
        String json = getValidJson().replace("\"4\": { \"nombre\": \"C4\", \"descripcion\": \"Desc4\", \"accion_sugerida\": \"Acc4\" }", "\"4\": { \"nombre\": \"C4\", \"descripcion\": \"Desc4\", \"accion_sugerida\": \"Acc4\" }").replace("\"perfiles\": {", "\"perfiles\": { \"999\": { \"nombre\": \"X\", \"descripcion\": \"X\", \"accion_sugerida\": \"X\" }").replace("\"4\": {", "\"X\": {"); // Wait, better to replace "0": { ... }, with nothing, but it leaves trailing comma.
        // Actually, let's just create a new JSON without one profile for this test:
        json = getValidJson().replace("\"4\": { \"nombre\": \"C4\", \"descripcion\": \"Desc4\", \"accion_sugerida\": \"Acc4\" }", "");
        json = json.replace("\"3\": { \"nombre\": \"C3\", \"descripcion\": \"Desc3\", \"accion_sugerida\": \"Acc3\" },", "\"3\": { \"nombre\": \"C3\", \"descripcion\": \"Desc3\", \"accion_sugerida\": \"Acc3\" }");
        PurchaseClusteringAnalyticsService service = createServiceWithJson(json);
        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .cause().hasMessageContaining("Perfiles para todos los clusters");
    }

    private PurchaseOrderFeatureProjection mockProjection(double val, double logVal) {
        return new PurchaseOrderFeatureProjection() {
            @Override public Long getOrderId() { return 1L; }
            @Override public String getOrderNumber() { return "ORD-1"; }
            @Override public BigDecimal getTotal() { return Double.isNaN(val) ? null : BigDecimal.valueOf(logVal); }
            @Override public BigDecimal getDiscountPct() { return Double.isNaN(val) ? null : BigDecimal.valueOf(val); }
            @Override public BigDecimal getShippingPct() { return Double.isNaN(val) ? null : BigDecimal.valueOf(logVal); }
            @Override public Integer getTotalUnits() { return Double.isNaN(val) ? null : (int)val; }
            @Override public Integer getUniqueProducts() { return Double.isNaN(val) ? null : (int)val; }
            @Override public Integer getUniqueCategories() { return Double.isNaN(val) ? null : (int)val; }
            @Override public BigDecimal getUnitsPerProduct() { return Double.isNaN(val) ? null : BigDecimal.valueOf(logVal); }
            @Override public BigDecimal getAccessoryValueShare() { return Double.isNaN(val) ? null : BigDecimal.valueOf(val); }
            @Override public BigDecimal getLargestProductValueShare() { return Double.isNaN(val) ? null : BigDecimal.valueOf(val); }
            @Override public BigDecimal getAverageUnitPrice() { return Double.isNaN(val) ? null : BigDecimal.valueOf(logVal); }
        };
    }

    // 6, 7, 8, 9. Calcula distancia, aplica log1p, estandariza, clasifica exactamente igual
    @Test
    void testPredict_CalculatesCorrectlyAndMatchesCentroid() {
        PurchaseClusteringAnalyticsService service = createServiceWithJson(getValidJson());
        service.init();

        // Para que coincida perfectamente con el cluster 0 (centroide todo 0.0), el scaled_features debe ser 0.
        // Formula: (x - media) / scale = 0 => x = media = 1.0
        // Para variables normales, val = 1.0. Para log1p, log1p(val) = 1.0 => val = e - 1 = 1.71828
        
        when(orderClusterRepository.findOrderFeaturesById(1L)).thenReturn(Optional.of(mockProjection(1.0, Math.E - 1.0)));
        
        PurchaseOrderPredictionDTO pred = service.predictClusterForOrder(1L);
        assertThat(pred.getPredictedCluster()).isEqualTo((short)0);
        assertThat(pred.getDistanceToCentroid()).isLessThan(0.0001); // Distancia casi 0
        assertThat(pred.getClusterName()).isEqualTo("C0");
    }

    // 10. Rechaza NaN e infinito
    @Test
    void testPredict_RejectsNaNAndInfinity() {
        PurchaseClusteringAnalyticsService service = createServiceWithJson(getValidJson());
        service.init();
        
        PurchaseOrderFeatureProjection proj = mockProjection(Double.NaN, 1.0);
        when(orderClusterRepository.findOrderFeaturesById(1L)).thenReturn(Optional.of(proj));
        
        assertThatThrownBy(() -> service.predictClusterForOrder(1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Valor inválido");
    }

    // 11. Rechaza negativos en variables log1p
    @Test
    void testPredict_RejectsNegativeInLog1p() {
        PurchaseClusteringAnalyticsService service = createServiceWithJson(getValidJson());
        service.init();
        
        PurchaseOrderFeatureProjection proj = mockProjection(1.0, -0.5);
        when(orderClusterRepository.findOrderFeaturesById(1L)).thenReturn(Optional.of(proj));
        
        assertThatThrownBy(() -> service.predictClusterForOrder(1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Valor negativo no permitido");
    }

    // 12. orderId inexistente produce ResourceNotFoundException
    @Test
    void testPredict_ThrowsNotFoundForMissingOrder() {
        PurchaseClusteringAnalyticsService service = createServiceWithJson(getValidJson());
        service.init();
        
        when(orderClusterRepository.findOrderFeaturesById(999L)).thenReturn(Optional.empty());
        
        assertThatThrownBy(() -> service.predictClusterForOrder(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // 15. La comparación storedCluster/matchesStoredAssignment funciona
    @Test
    void testPredict_HistoricalAssignmentMatching() {
        PurchaseClusteringAnalyticsService service = createServiceWithJson(getValidJson());
        service.init();

        PurchaseClusteringRun run = new PurchaseClusteringRun();
        org.springframework.test.util.ReflectionTestUtils.setField(run, "id", 100L);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "status", "COMPLETED");
        org.springframework.test.util.ReflectionTestUtils.setField(run, "active", true);
        
        when(runRepository.findFirstByActiveTrueAndStatusOrderByExecutedAtDesc("COMPLETED")).thenReturn(Optional.of(run));
        when(orderClusterRepository.findOrderFeaturesById(1L)).thenReturn(Optional.of(mockProjection(1.0, Math.E - 1.0)));
        
        PurchaseOrderCluster historical = new PurchaseOrderCluster();
        org.springframework.test.util.ReflectionTestUtils.setField(historical, "clusterNumber", (short)0);
        when(orderClusterRepository.findByRunIdAndOrderId(100L, 1L)).thenReturn(Optional.of(historical));

        PurchaseOrderPredictionDTO pred = service.predictClusterForOrder(1L);
        
        assertThat(pred.getStoredCluster()).isEqualTo((short)0);
        assertThat(pred.getMatchesStoredAssignment()).isTrue();
    }

    // --- NUEVAS PRUEBAS FASE E OPERATIVA ---

    @Test
    void testOrderClusterRepository_UsesCorrectLiveViewAndAliases() throws NoSuchMethodException {
        // Verifica mediante reflexión que la consulta de base de datos apunte a la nueva vista live y conserve las 10 características
        org.springframework.data.jpa.repository.Query queryAnnotation = 
            PurchaseOrderClusterRepository.class.getMethod("findOrderFeaturesById", Long.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class);
        
        assertThat(queryAnnotation).isNotNull();
        String sql = queryAnnotation.value();
        
        // 1. La consulta de predicción utiliza la vista live
        assertThat(sql).contains("analytics.vw_clustering_order_features_live_v1");
        
        // 10. No se alteran las diez características y 11. los aliases son compatibles con PurchaseOrderFeatureProjection
        assertThat(sql).contains("AS total");
        assertThat(sql).contains("AS discountPct");
        assertThat(sql).contains("AS shippingPct");
        assertThat(sql).contains("AS totalUnits");
        assertThat(sql).contains("AS uniqueProducts");
        assertThat(sql).contains("AS uniqueCategories");
        assertThat(sql).contains("AS unitsPerProduct");
        assertThat(sql).contains("AS accessoryValueShare");
        assertThat(sql).contains("AS largestProductValueShare");
        assertThat(sql).contains("AS averageUnitPrice");
    }

    @Test
    void testPredict_ExcludedOrder_ThrowsNotFound() {
        // Mockito no ejecuta PostgreSQL nativo, por lo que asume que una orden excluida (por ejemplo, categoría de prueba, no pagada, etc.) 
        // devuelve un Optional vacío desde la base de datos (comportamiento del NOT EXISTS de la vista en producción).
        PurchaseClusteringAnalyticsService service = createServiceWithJson(getValidJson());
        service.init();
        
        when(orderClusterRepository.findOrderFeaturesById(16L)).thenReturn(Optional.empty()); // Orden simulada como excluida
        
        assertThatThrownBy(() -> service.predictClusterForOrder(16L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("La orden no existe o no tiene datos analíticos");
    }

    @Test
    void testPredict_ValidLiveOrder_CalculatesSuccessfully() {
        // Verifica que si la orden pasa los filtros de la vista, se procesa exitosamente independientemente de ser MLSEED o real
        PurchaseClusteringAnalyticsService service = createServiceWithJson(getValidJson());
        service.init();
        
        PurchaseOrderFeatureProjection validProjection = mockProjection(1.0, Math.E - 1.0);
        when(orderClusterRepository.findOrderFeaturesById(9L)).thenReturn(Optional.of(validProjection));
        
        PurchaseOrderPredictionDTO pred = service.predictClusterForOrder(9L);
        assertThat(pred).isNotNull();
        assertThat(pred.getPredictedCluster()).isEqualTo((short)0); // Mismo mock que en testPredict_CalculatesCorrectlyAndMatchesCentroid
    }
}
