package com.security.dto.admin.analytics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Resumen de la ejecución activa del modelo K-Means de clustering de compras.
 *
 * <p>
 * Incluye métricas de calidad del modelo, configuración de preprocesamiento,
 * verificación de integridad y advertencia de datos académicos cuando
 * {@code dataOrigin == ACADEMIC_SYNTHETIC}.
 * </p>
 *
 * @param runId                  Identificador único de la ejecución.
 * @param modelVersion           Versión del modelo entrenado.
 * @param algorithm              Algoritmo usado (ej. KMEANS).
 * @param selectedK              Número de clusters del modelo.
 * @param ordersAnalyzed         Total de órdenes analizadas en el entrenamiento.
 * @param featureCount           Número de características usadas.
 * @param datasetView            Vista de base de datos utilizada como fuente.
 * @param dataOrigin             Origen de los datos (ej. ACADEMIC_SYNTHETIC, PRODUCTION).
 * @param periodStart            Inicio del período analizado.
 * @param periodEnd              Fin del período analizado.
 * @param inertia                Métrica de inercia (suma de distancias cuadradas al centroide).
 * @param silhouetteScore        Silhouette score del modelo.
 * @param daviesBouldinScore     Davies-Bouldin score (menor es mejor).
 * @param calinskiHarabaszScore  Calinski-Harabasz score (mayor es mejor).
 * @param featureNames           Nombres de las características usadas en el modelo.
 * @param preprocessingConfig    Configuración de preprocesamiento (escalado, imputación, etc.).
 * @param notebookName           Nombre del notebook Jupyter que generó el modelo.
 * @param status                 Estado de la ejecución (ej. COMPLETED).
 * @param active                 Indica si esta es la ejecución activa.
 * @param executedAt             Fecha y hora en que se ejecutó el modelo.
 * @param academicData           {@code true} cuando los datos son sintéticos académicos.
 * @param dataDisclaimer         Aviso de advertencia sobre el origen académico.
 * @param integrity              Verificación de consistencia entre la ejecución y los datos.
 */
public record PurchaseClusteringSummaryDTO(
        Long runId,
        String modelVersion,
        String algorithm,
        Short selectedK,
        Integer ordersAnalyzed,
        Short featureCount,
        String datasetView,
        String dataOrigin,
        LocalDateTime periodStart,
        LocalDateTime periodEnd,
        BigDecimal inertia,
        BigDecimal silhouetteScore,
        BigDecimal daviesBouldinScore,
        BigDecimal calinskiHarabaszScore,
        List<String> featureNames,
        Map<String, Object> preprocessingConfig,
        String notebookName,
        String status,
        boolean active,
        OffsetDateTime executedAt,
        boolean academicData,
        String dataDisclaimer,
        PurchaseClusteringIntegrityDTO integrity
) {
}
