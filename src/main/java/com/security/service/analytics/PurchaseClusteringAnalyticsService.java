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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ResourceLoader;
import jakarta.annotation.PostConstruct;
import com.security.dto.admin.analytics.clustering.ClusteringModelConfig;
import com.security.repository.projection.PurchaseOrderFeatureProjection;
import com.security.entity.PurchaseOrderCluster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Servicio de consulta para el módulo de clustering de tipos de compra (Propuesta 1).
 *
 * <p>
 * Este servicio concentra toda la lógica de negocio del módulo analítico.
 * El controlador solo valida parámetros básicos y delega a este servicio.
 * </p>
 *
 * <p>
 * Todas las operaciones son de <strong>solo lectura</strong>; ningún método
 * modifica datos en la base de datos.
 * </p>
 *
 * <p>
 * La ejecución activa se busca dinámicamente en cada llamada; no hay ningún
 * identificador o valor de configuración del modelo hardcodeado en el código Java.
 * </p>
 */
@Service
@Transactional(readOnly = true)
public class PurchaseClusteringAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseClusteringAnalyticsService.class);

    /** Estado que indica que el modelo terminó su ejecución correctamente. */
    private static final String STATUS_COMPLETED = "COMPLETED";

    /** Origen que identifica datos sintéticos académicos. */
    private static final String ORIGIN_ACADEMIC_SYNTHETIC = "ACADEMIC_SYNTHETIC";

    /**
     * Aviso de presentación derivado del origen de datos académicos.
     * No se almacena en la base de datos; es un mensaje estático de la capa de servicio.
     */
    private static final String ACADEMIC_DISCLAIMER =
            "Análisis académico generado con datos sintéticos. Los perfiles validan la metodología, "
            + "pero deberán recalcularse con ventas reales antes de utilizarse para decisiones "
            + "comerciales definitivas.";

    /**
     * Tolerancia decimal para validar que la suma de porcentajes sea 100.
     * Una diferencia menor a 0.01 se considera aceptable por redondeo.
     */
    private static final BigDecimal PERCENTAGE_TOLERANCE = new BigDecimal("0.01");

    private final PurchaseClusteringRunRepository runRepository;
    private final PurchaseClusterProfileRepository profileRepository;
    private final PurchaseOrderClusterRepository orderClusterRepository;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    private ClusteringModelConfig loadedModelConfig;

    public PurchaseClusteringAnalyticsService(
            PurchaseClusteringRunRepository runRepository,
            PurchaseClusterProfileRepository profileRepository,
            PurchaseOrderClusterRepository orderClusterRepository,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader) {
        this.runRepository = runRepository;
        this.profileRepository = profileRepository;
        this.orderClusterRepository = orderClusterRepository;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() {
        try {
            org.springframework.core.io.Resource resource = resourceLoader.getResource("classpath:analytics/clustering/modelo_clustering_patrones_compra_v3.json");
            if (!resource.exists()) {
                throw new IllegalStateException("El archivo del modelo de clustering no existe en classpath.");
            }
            loadedModelConfig = objectMapper.readValue(resource.getInputStream(), ClusteringModelConfig.class);
            validateModel(loadedModelConfig);
            log.info("[Clustering] Modelo V3 cargado exitosamente. Algoritmo: {}", loadedModelConfig.getAlgoritmo().getNombre());
        } catch (Exception e) {
            log.error("[Clustering] Error al cargar el modelo de predicción: {}", e.getMessage());
            throw new IllegalStateException("No se pudo cargar el modelo de clustering", e);
        }
    }

    private void validateModel(ClusteringModelConfig model) {
        if (model.getVersionEsquema() == null || model.getVersionEsquema() != 1) {
            throw new IllegalStateException("version_esquema no soportada");
        }
        if (model.getAlgoritmo() == null || !"KMeans".equals(model.getAlgoritmo().getNombre())) {
            throw new IllegalStateException("Algoritmo KMeans requerido");
        }
        int k = model.getAlgoritmo().getNumeroClusters();
        if (k <= 0) {
            throw new IllegalStateException("numero_clusters debe ser > 0");
        }
        if (model.getModelo() == null || model.getModelo().getCentroidesEscalados() == null || model.getModelo().getCentroidesEscalados().size() != k) {
            throw new IllegalStateException("numero_clusters no coincide con los centroides");
        }
        if (model.getEntrada() == null || model.getEntrada().getCaracteristicas() == null || model.getEntrada().getCaracteristicas().size() != 10) {
            throw new IllegalStateException("Debe tener exactamente 10 características");
        }
        long distinctFeatures = model.getEntrada().getCaracteristicas().stream().distinct().count();
        if (distinctFeatures != 10) {
            throw new IllegalStateException("Hay características duplicadas");
        }
        if (model.getEscalador() == null || model.getEscalador().getMedia() == null || model.getEscalador().getMedia().size() != 10) {
            throw new IllegalStateException("La media debe ser de tamaño 10");
        }
        if (model.getEscalador().getEscala() == null || model.getEscalador().getEscala().size() != 10) {
            throw new IllegalStateException("La escala debe ser de tamaño 10");
        }
        for (Double scale : model.getEscalador().getEscala()) {
            if (scale == null || !Double.isFinite(scale) || scale <= 0) {
                throw new IllegalStateException("Cada escala debe ser finita y > 0");
            }
        }
        for (List<Double> centroid : model.getModelo().getCentroidesEscalados()) {
            if (centroid == null || centroid.size() != 10) {
                throw new IllegalStateException("Cada centroide debe ser de tamaño 10");
            }
            for (Double val : centroid) {
                if (val == null || !Double.isFinite(val)) {
                    throw new IllegalStateException("Los números de los centroides deben ser finitos");
                }
            }
        }
        if (model.getPerfiles() == null || model.getPerfiles().size() != k) {
            throw new IllegalStateException("Perfiles para todos los clusters");
        }
        List<String> log1pCols = model.getEntrada().getColumnasLog1p();
        if (log1pCols != null) {
            for (String col : log1pCols) {
                if (!model.getEntrada().getCaracteristicas().contains(col)) {
                    throw new IllegalStateException("Columnas log1p deben estar contenidas dentro de las características");
                }
            }
        }
    }

    // ── Métodos públicos ──────────────────────────────────────────────────────

    /**
     * Retorna el resumen de la ejecución activa del modelo, incluyendo métricas,
     * configuración y verificación de integridad.
     *
     * @return DTO con todos los datos de la ejecución activa
     * @throws ResourceNotFoundException si no existe una ejecución activa y completada
     */
    public PurchaseClusteringSummaryDTO getActiveSummary() {
        log.info("[Clustering] Solicitud de resumen de ejecución activa");
        PurchaseClusteringRun run = getRequiredActiveRun();
        PurchaseClusteringIntegrityDTO integrity = buildIntegrity(run);
        return mapRunToSummary(run, integrity);
    }

    /**
     * Retorna todos los perfiles de la ejecución activa, ordenados por número de cluster.
     *
     * @return lista de perfiles; nunca {@code null}, puede ser vacía
     * @throws ResourceNotFoundException si no existe una ejecución activa
     */
    public List<PurchaseClusterProfileDTO> getActiveProfiles() {
        log.info("[Clustering] Solicitud de perfiles de ejecución activa");
        PurchaseClusteringRun run = getRequiredActiveRun();
        List<PurchaseClusterProfile> profiles =
                profileRepository.findByRunIdOrderByClusterNumberAsc(run.getId());
        log.info("[Clustering] {} perfiles encontrados para run_id={}", profiles.size(), run.getId());
        return profiles.stream().map(this::mapProfile).toList();
    }

    /**
     * Retorna el perfil de un cluster específico dentro de la ejecución activa.
     *
     * @param clusterNumber número del cluster (debe ser ≥ 0)
     * @return DTO del perfil
     * @throws BadRequestException       si {@code clusterNumber} es negativo
     * @throws ResourceNotFoundException si no existe la ejecución activa o el cluster
     */
    public PurchaseClusterProfileDTO getActiveProfile(short clusterNumber) {
        validateClusterNumber(clusterNumber);
        log.info("[Clustering] Solicitud del perfil cluster={}", clusterNumber);
        PurchaseClusteringRun run = getRequiredActiveRun();
        PurchaseClusterProfile profile = profileRepository
                .findByRunIdAndClusterNumber(run.getId(), clusterNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El cluster solicitado no existe en la ejecución activa."));
        return mapProfile(profile);
    }

    /**
     * Retorna una página de órdenes enriquecidas del cluster especificado.
     *
     * <p>
     * Las órdenes se consultan paginadas directamente desde PostgreSQL; nunca
     * se cargan todas en memoria.
     * </p>
     *
     * @param clusterNumber número del cluster (debe ser ≥ 0)
     * @param page          número de página (debe ser ≥ 0)
     * @param size          tamaño de página (entre 1 y 100 inclusive)
     * @return DTO paginado con las órdenes del cluster
     * @throws BadRequestException       si algún parámetro es inválido
     * @throws ResourceNotFoundException si no existe la ejecución activa o el cluster
     */
    public PurchaseClusterOrdersPageDTO getActiveClusterOrders(short clusterNumber, int page, int size) {
        validatePagination(clusterNumber, page, size);
        log.info("[Clustering] Solicitud de órdenes cluster={} page={} size={}", clusterNumber, page, size);

        PurchaseClusteringRun run = getRequiredActiveRun();

        // Verificar que el cluster existe en la ejecución activa
        profileRepository.findByRunIdAndClusterNumber(run.getId(), clusterNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El cluster solicitado no existe en la ejecución activa."));

        PageRequest pageRequest = PageRequest.of(page, size);
        Page<PurchaseClusterOrderProjection> resultPage =
                orderClusterRepository.findOrdersByRunIdAndClusterNumber(run.getId(), clusterNumber, pageRequest);

        log.info("[Clustering] {} órdenes en página {}/{} para cluster={}",
                resultPage.getNumberOfElements(), page + 1, resultPage.getTotalPages(), clusterNumber);

        List<PurchaseClusterOrderDTO> content = resultPage.getContent()
                .stream()
                .map(this::mapOrderProjection)
                .toList();

        return new PurchaseClusterOrdersPageDTO(
                content,
                resultPage.getNumber(),
                resultPage.getSize(),
                resultPage.getTotalElements(),
                resultPage.getTotalPages(),
                resultPage.isFirst(),
                resultPage.isLast(),
                resultPage.isEmpty()
        );
    }

    // ── Métodos privados ──────────────────────────────────────────────────────

    /**
     * Obtiene la ejecución activa y completada o lanza {@link ResourceNotFoundException}.
     *
     * @return la ejecución activa
     */
    private PurchaseClusteringRun getRequiredActiveRun() {
        return runRepository
                .findFirstByActiveTrueAndStatusOrderByExecutedAtDesc(STATUS_COMPLETED)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No existe una ejecución activa y completada del clustering de compras."));
    }

    /**
     * Construye el DTO de integridad comparando los valores de la ejecución
     * con los conteos reales de la base de datos.
     *
     * @param run la ejecución activa
     * @return DTO de integridad con {@code consistent = true} si todo coincide
     */
    private PurchaseClusteringIntegrityDTO buildIntegrity(PurchaseClusteringRun run) {
        int expectedClusters   = run.getSelectedK();
        long actualProfiles    = profileRepository.countByRunId(run.getId());
        int expectedOrders     = run.getOrdersAnalyzed();
        long assignedOrders    = orderClusterRepository.countByRunId(run.getId());
        long profileOrdersSum  = profileRepository.sumOrderCountByRunId(run.getId());
        BigDecimal percentSum  = profileRepository.sumPercentageByRunId(run.getId());

        boolean profilesMatch   = (expectedClusters == actualProfiles);
        boolean ordersMatch     = (expectedOrders == assignedOrders);
        boolean orderSumMatch   = (expectedOrders == profileOrdersSum);
        boolean percentNearHundred = percentSum != null
                && new BigDecimal("100").subtract(percentSum).abs().compareTo(PERCENTAGE_TOLERANCE) <= 0;

        boolean consistent = profilesMatch && ordersMatch && orderSumMatch && percentNearHundred;

        if (!consistent) {
            log.warn("[Clustering] ADVERTENCIA DE INTEGRIDAD run_id={} " +
                     "expectedClusters={} actualProfiles={} " +
                     "expectedOrders={} assignedOrders={} profileOrdersSum={} percentSum={}",
                    run.getId(), expectedClusters, actualProfiles,
                    expectedOrders, assignedOrders, profileOrdersSum, percentSum);
        }

        return new PurchaseClusteringIntegrityDTO(
                expectedClusters,
                actualProfiles,
                expectedOrders,
                assignedOrders,
                profileOrdersSum,
                percentSum != null ? percentSum : BigDecimal.ZERO,
                consistent
        );
    }

    /**
     * Convierte una entidad {@link PurchaseClusteringRun} en el DTO de resumen.
     *
     * @param run       la ejecución
     * @param integrity el resultado de la verificación de integridad
     * @return DTO de resumen
     */
    private PurchaseClusteringSummaryDTO mapRunToSummary(
            PurchaseClusteringRun run, PurchaseClusteringIntegrityDTO integrity) {

        boolean academic = ORIGIN_ACADEMIC_SYNTHETIC.equalsIgnoreCase(run.getDataOrigin());
        String disclaimer = academic ? ACADEMIC_DISCLAIMER : null;

        return new PurchaseClusteringSummaryDTO(
                run.getId(),
                run.getModelVersion(),
                run.getAlgorithm(),
                run.getSelectedK(),
                run.getOrdersAnalyzed(),
                run.getFeatureCount(),
                run.getDatasetView(),
                run.getDataOrigin(),
                run.getPeriodStart(),
                run.getPeriodEnd(),
                run.getInertia(),
                run.getSilhouetteScore(),
                run.getDaviesBouldinScore(),
                run.getCalinskiHarabaszScore(),
                run.getFeatureNames(),
                run.getPreprocessingConfig(),
                run.getNotebookName(),
                run.getStatus(),
                run.isActive(),
                run.getExecutedAt(),
                academic,
                disclaimer,
                integrity
        );
    }

    /**
     * Convierte una entidad {@link PurchaseClusterProfile} en su DTO.
     *
     * @param profile la entidad del perfil
     * @return DTO del perfil
     */
    private PurchaseClusterProfileDTO mapProfile(PurchaseClusterProfile profile) {
        return new PurchaseClusterProfileDTO(
                profile.getClusterNumber(),
                profile.getClusterName(),
                profile.getClusterDescription(),
                profile.getOrderCount(),
                profile.getPercentage(),
                profile.getAverageTotal(),
                profile.getAverageDiscountPct(),
                profile.getAverageShippingPct(),
                profile.getAverageUnitPrice(),
                profile.getAverageTotalUnits(),
                profile.getAverageUniqueProducts(),
                profile.getAverageUniqueCategories(),
                profile.getAverageUnitsPerProduct(),
                profile.getAverageAccessoryUnits(),
                profile.getAverageInstrumentUnits(),
                profile.getAverageViolinUnits(),
                profile.getAverageJaranaUnits(),
                profile.getAverageQuintaUnits(),
                profile.getAverageAccessoryValueShare(),
                profile.getAverageInstrumentValueShare(),
                profile.getAverageLargestProductValueShare(),
                profile.getWeekendPercentage(),
                profile.getDominantPaymentMethod(),
                profile.getDominantDeliveryType(),
                profile.getRecommendedAction()
        );
    }

    /**
     * Convierte una proyección de orden en su DTO de presentación.
     *
     * @param p la proyección de Spring Data
     * @return DTO de la orden
     */
    private PurchaseClusterOrderDTO mapOrderProjection(PurchaseClusterOrderProjection p) {
        return new PurchaseClusterOrderDTO(
                p.getOrderId(),
                p.getOrderNumber(),
                p.getCreatedAt(),
                p.getSubtotal(),
                p.getDiscount(),
                p.getShipping(),
                p.getTotal(),
                p.getDiscountPct(),
                p.getShippingPct(),
                p.getTotalUnits(),
                p.getUniqueProducts(),
                p.getUniqueCategories(),
                p.getUnitsPerProduct(),
                p.getAccessoryUnits(),
                p.getInstrumentUnits(),
                p.getViolinUnits(),
                p.getJaranaUnits(),
                p.getQuintaUnits(),
                p.getAccessoryValueShare(),
                p.getInstrumentValueShare(),
                p.getLargestProductValueShare(),
                p.getAverageUnitPrice(),
                p.getWeekend(),
                p.getPaymentMethod(),
                p.getDeliveryType(),
                p.getClusterNumber(),
                p.getClusterName(),
                p.getRecommendedAction()
        );
    }

    /**
     * Valida los parámetros de paginación y el número de cluster.
     *
     * @param clusterNumber número de cluster
     * @param page          número de página
     * @param size          tamaño de página
     * @throws BadRequestException si algún parámetro está fuera de rango
     */
    private void validatePagination(short clusterNumber, int page, int size) {
        validateClusterNumber(clusterNumber);
        if (page < 0) {
            throw new BadRequestException("El número de página no puede ser negativo.");
        }
        if (size < 1 || size > 100) {
            throw new BadRequestException("El tamaño de página debe estar entre 1 y 100.");
        }
    }

    /**
     * Valida que el número de cluster no sea negativo.
     *
     * @param clusterNumber número de cluster
     * @throws BadRequestException si es negativo
     */
    private void validateClusterNumber(short clusterNumber) {
        if (clusterNumber < 0) {
            throw new BadRequestException("El número de cluster no puede ser negativo.");
        }
    }

    public PurchaseClusteringModelStatusDTO getModelStatus() {
        PurchaseClusteringModelStatusDTO status = new PurchaseClusteringModelStatusDTO();
        status.setVersionEsquema(String.valueOf(loadedModelConfig.getVersionEsquema()));
        status.setVersionModelo(loadedModelConfig.getVersionModelo());
        status.setFechaGeneracionUtc(loadedModelConfig.getFechaGeneracionUtc());
        status.setAlgoritmoNombre(loadedModelConfig.getAlgoritmo().getNombre());
        status.setNumeroClusters(loadedModelConfig.getAlgoritmo().getNumeroClusters());
        status.setNumeroCaracteristicas(loadedModelConfig.getEntrada().getCaracteristicas().size());
        status.setUnidadAnalisis(loadedModelConfig.getEntrada().getUnidadAnalisis());
        return status;
    }

    public PurchaseOrderPredictionDTO predictClusterForOrder(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new BadRequestException("El orderId debe ser positivo");
        }

        PurchaseOrderFeatureProjection features = orderClusterRepository.findOrderFeaturesById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("La orden no existe o no tiene datos analíticos"));

        List<Double> rawFeatures = mapFeatures(features);
        List<Double> transformedFeatures = applyLog1p(rawFeatures);
        List<Double> scaledFeatures = applyScaling(transformedFeatures);

        List<Double> distances = new ArrayList<>();
        int bestCluster = -1;
        double minDistance = Double.MAX_VALUE;

        for (int i = 0; i < loadedModelConfig.getModelo().getCentroidesEscalados().size(); i++) {
            List<Double> centroid = loadedModelConfig.getModelo().getCentroidesEscalados().get(i);
            double dist = euclideanDistance(scaledFeatures, centroid);
            distances.add(dist);
            if (dist < minDistance) {
                minDistance = dist;
                bestCluster = i;
            }
        }

        Short storedCluster = null;
        Boolean matchesStored = null;
        try {
            PurchaseClusteringRun activeRun = getRequiredActiveRun();
            Optional<PurchaseOrderCluster> historical = orderClusterRepository.findByRunIdAndOrderId(activeRun.getId(), orderId);
            if (historical.isPresent()) {
                storedCluster = historical.get().getClusterNumber();
                matchesStored = storedCluster.intValue() == bestCluster;
            }
        } catch (ResourceNotFoundException e) {
            // Ignorar si no hay ejecución activa
        }

        PurchaseOrderPredictionDTO dto = new PurchaseOrderPredictionDTO();
        dto.setOrderId(features.getOrderId());
        dto.setOrderNumber(features.getOrderNumber());
        dto.setModelVersion(loadedModelConfig.getVersionModelo());
        dto.setAlgorithm(loadedModelConfig.getAlgoritmo().getNombre());
        dto.setPredictedCluster((short) bestCluster);

        final int finalBestCluster = bestCluster;
        var profile = loadedModelConfig.getPerfiles().get(String.valueOf(finalBestCluster));
        if (profile == null) {
            throw new IllegalStateException("Perfil no encontrado para el cluster " + finalBestCluster);
        }

        dto.setClusterName(profile.getNombre());
        dto.setClusterDescription(profile.getDescripcion());
        dto.setRecommendedAction(profile.getAccionComercial());
        dto.setDistanceToCentroid(minDistance);
        dto.setDistancesToAllCentroids(distances);
        dto.setFeatureValues(rawFeatures);
        dto.setStoredCluster(storedCluster);
        dto.setMatchesStoredAssignment(matchesStored);

        return dto;
    }

    private List<Double> mapFeatures(PurchaseOrderFeatureProjection f) {
        List<Double> list = new ArrayList<>();
        for (String featureName : loadedModelConfig.getEntrada().getCaracteristicas()) {
            list.add(extractFeatureValue(f, featureName));
        }
        return list;
    }

    private Double extractFeatureValue(PurchaseOrderFeatureProjection f, String featureName) {
        Double val = switch (featureName) {
            case "total" -> f.getTotal() != null ? f.getTotal().doubleValue() : null;
            case "porcentaje_descuento" -> f.getDiscountPct() != null ? f.getDiscountPct().doubleValue() : null;
            case "porcentaje_envio" -> f.getShippingPct() != null ? f.getShippingPct().doubleValue() : null;
            case "unidades_totales" -> f.getTotalUnits() != null ? f.getTotalUnits().doubleValue() : null;
            case "productos_distintos" -> f.getUniqueProducts() != null ? f.getUniqueProducts().doubleValue() : null;
            case "categorias_distintas" -> f.getUniqueCategories() != null ? f.getUniqueCategories().doubleValue() : null;
            case "unidades_por_producto" -> f.getUnitsPerProduct() != null ? f.getUnitsPerProduct().doubleValue() : null;
            case "proporcion_valor_accesorios" -> f.getAccessoryValueShare() != null ? f.getAccessoryValueShare().doubleValue() : null;
            case "proporcion_producto_mayor_valor" -> f.getLargestProductValueShare() != null ? f.getLargestProductValueShare().doubleValue() : null;
            case "precio_unitario_promedio" -> f.getAverageUnitPrice() != null ? f.getAverageUnitPrice().doubleValue() : null;
            default -> throw new IllegalStateException("Característica desconocida: " + featureName);
        };
        if (val == null || Double.isNaN(val) || Double.isInfinite(val)) {
            throw new IllegalArgumentException("Valor inválido para la característica " + featureName + ": " + val);
        }
        return val;
    }

    private List<Double> applyLog1p(List<Double> features) {
        List<Double> transformed = new ArrayList<>(features);
        List<String> featureNames = loadedModelConfig.getEntrada().getCaracteristicas();
        List<String> log1pCols = loadedModelConfig.getEntrada().getColumnasLog1p();

        if (log1pCols != null) {
            for (int i = 0; i < featureNames.size(); i++) {
                if (log1pCols.contains(featureNames.get(i))) {
                    double val = transformed.get(i);
                    if (val < 0) {
                        throw new IllegalArgumentException("Valor negativo no permitido para log1p en columna " + featureNames.get(i));
                    }
                    transformed.set(i, Math.log1p(val));
                }
            }
        }
        return transformed;
    }

    private List<Double> applyScaling(List<Double> features) {
        List<Double> scaled = new ArrayList<>();
        List<Double> mean = loadedModelConfig.getEscalador().getMedia();
        List<Double> scale = loadedModelConfig.getEscalador().getEscala();

        for (int i = 0; i < features.size(); i++) {
            scaled.add((features.get(i) - mean.get(i)) / scale.get(i));
        }
        return scaled;
    }

    private double euclideanDistance(List<Double> p1, List<Double> p2) {
        double sum = 0;
        for (int i = 0; i < p1.size(); i++) {
            double diff = p1.get(i) - p2.get(i);
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }
}
