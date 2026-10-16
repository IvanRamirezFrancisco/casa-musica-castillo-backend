package com.security.service.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.security.dto.admin.analytics.forecast.*;
import com.security.enums.DemandForecastStatus;
import com.security.enums.DemandPriority;
import com.security.repository.analytics.DemandForecastRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DemandForecastService {

    private static final Logger logger = LoggerFactory.getLogger(DemandForecastService.class);

    private final DemandForecastRepository repository;
    private final MovingAverageDemandCalculator calculator;
    private final DemandPriorityAssigner priorityAssigner;
    private final ObjectMapper objectMapper;

    // Loaded from JSON
    private String modelVersion;
    private String algorithmFamily;
    private String method;
    private Integer windowWeeks;
    private Integer forecastHorizonWeeks;
    private String generatedAtUtc;
    private Map<String, Object> evaluationMetrics;
    private String limitations;
    private boolean isConfigurationValid = false;

    public DemandForecastService(DemandForecastRepository repository,
                                 MovingAverageDemandCalculator calculator,
                                 DemandPriorityAssigner priorityAssigner,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.calculator = calculator;
        this.priorityAssigner = priorityAssigner;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("models/modelo_medias_moviles_demanda_v1.json");
            try (InputStream is = resource.getInputStream()) {
                JsonNode rootNode = objectMapper.readTree(is);

                this.modelVersion = rootNode.path("artifact_version").asText();
                
                JsonNode algoNode = rootNode.path("algorithm");
                this.algorithmFamily = algoNode.path("family").asText();
                this.method = algoNode.path("method").asText();
                this.windowWeeks = algoNode.path("window_weeks").asInt();
                this.forecastHorizonWeeks = algoNode.path("forecast_horizon_weeks").asInt();
                
                this.generatedAtUtc = rootNode.path("generated_at_utc").asText(null);
                
                JsonNode evalNode = rootNode.path("evaluation");
                if (evalNode != null && !evalNode.isMissingNode()) {
                    this.evaluationMetrics = objectMapper.convertValue(evalNode, new TypeReference<Map<String, Object>>() {});
                    this.limitations = evalNode.path("limitations").asText(null);
                }

                validateConfiguration();
                
                logger.info("Modelo de pronóstico de demanda cargado exitosamente. Versión: {}, Método: {}, Ventana: {}", 
                        modelVersion, method, windowWeeks);
            }
        } catch (Exception e) {
            logger.error("Error crítico al cargar el artefacto JSON de medias móviles: {}", e.getMessage());
            this.isConfigurationValid = false;
        }
    }

    private void validateConfiguration() {
        if ("MOVING_AVERAGE_DEMAND_V1".equals(modelVersion) &&
            "MOVING_AVERAGE".equals(algorithmFamily) &&
            "SMA".equals(method) &&
            Integer.valueOf(12).equals(windowWeeks) &&
            Integer.valueOf(1).equals(forecastHorizonWeeks)) {
            this.isConfigurationValid = true;
        } else {
            this.isConfigurationValid = false;
            logger.error("Configuración de modelo de demanda no soportada o inconsistente.");
        }
    }

    public DemandForecastModelStatusDTO getModelStatus() {
        List<String> errors = new ArrayList<>();
        if (!isConfigurationValid) {
            errors.add("El archivo de modelo no existe, es inválido o su configuración es incompatible con esta versión del sistema.");
        }

        // Just to get stats, we fetch the batch
        List<DemandForecastListDTO> globalBatch = new ArrayList<>();
        try {
            globalBatch = calculateGlobalBatch();
        } catch (Exception e) {
            logger.error("No se pudo calcular el estado global: {}", e.getMessage());
        }

        long eligible = globalBatch.stream().filter(d -> d.forecastStatus() == DemandForecastStatus.AVAILABLE).count();
        long insufficient = globalBatch.stream().filter(d -> d.forecastStatus() == DemandForecastStatus.INSUFFICIENT_HISTORY).count();

        String lastCompleteWeek = null;
        if (!globalBatch.isEmpty()) {
            lastCompleteWeek = globalBatch.stream()
                .filter(d -> d.lastCompleteWeek() != null)
                .map(d -> d.lastCompleteWeek().toString())
                .findFirst()
                .orElse(null);
        }

        return new DemandForecastModelStatusDTO(
                modelVersion,
                algorithmFamily,
                method,
                windowWeeks,
                forecastHorizonWeeks,
                "sales.orders, catalog.products, PostgreSQL",
                true,
                "America/Mexico_City",
                "Lunes a Domingo",
                12,
                generatedAtUtc,
                evaluationMetrics,
                limitations,
                lastCompleteWeek,
                (int) eligible,
                (int) insufficient,
                errors
        );
    }

    public Page<DemandForecastListDTO> getPaginatedForecasts(
            Long categoryId,
            String trendStr,
            String priorityStr,
            String forecastStatusStr,
            Boolean active,
            String search,
            Pageable pageable) {

        if (!isConfigurationValid) {
            throw new IllegalStateException("El servicio de pronóstico no está disponible debido a un error de configuración del modelo.");
        }

        List<DemandForecastListDTO> globalBatch = calculateGlobalBatch();

        // Aplicar filtros a la lista global ya procesada (y con prioridades asignadas)
        List<DemandForecastListDTO> filtered = globalBatch.stream()
                .filter(dto -> categoryId == null || categoryId.equals(dto.categoryId()))
                .filter(dto -> trendStr == null || (dto.trend() != null && dto.trend().name().equalsIgnoreCase(trendStr)))
                .filter(dto -> priorityStr == null || (dto.demandPriority() != null && dto.demandPriority().name().equalsIgnoreCase(priorityStr)))
                .filter(dto -> forecastStatusStr == null || dto.forecastStatus().name().equalsIgnoreCase(forecastStatusStr))
                .filter(dto -> active == null || dto.productActive().equals(active))
                .filter(dto -> search == null || 
                        dto.productName().toLowerCase().contains(search.toLowerCase()) || 
                        dto.productSku().toLowerCase().contains(search.toLowerCase()))
                .collect(Collectors.toList());

        // Ordenamiento dinámico en memoria
        if (pageable.getSort().isSorted()) {
            Comparator<DemandForecastListDTO> comparator = null;
            
            for (org.springframework.data.domain.Sort.Order order : pageable.getSort()) {
                Comparator<DemandForecastListDTO> currentComparator = getComparatorForProperty(order.getProperty());
                
                if (order.getDirection().isDescending()) {
                    currentComparator = currentComparator.reversed();
                }
                
                if (comparator == null) {
                    comparator = currentComparator;
                } else {
                    comparator = comparator.thenComparing(currentComparator);
                }
            }
            
            if (comparator != null) {
                // Ensure nulls are handled gracefully
                filtered.sort(comparator);
            }
        } else {
            // Default sort: demandPriority: ALTA, MEDIA, BAJA, null -> forecastUnits desc -> productId asc
            filtered.sort(Comparator
                    .comparing(DemandForecastListDTO::demandPriority, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(DemandForecastListDTO::forecastUnits, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(DemandForecastListDTO::productId));
        }

        // Paginación
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), filtered.size());
        
        List<DemandForecastListDTO> pageContent;
        if (start > filtered.size()) {
            pageContent = new ArrayList<>();
        } else {
            pageContent = filtered.subList(start, end);
        }

        return new PageImpl<>(pageContent, pageable, filtered.size());
    }

    public DemandForecastDetailDTO getForecastDetail(Long productId) {
        if (!isConfigurationValid) {
            throw new IllegalStateException("El servicio de pronóstico no está disponible debido a un error de configuración del modelo.");
        }

        // Recuperar TODO el lote para tener la misma prioridad que en el listado
        List<ProductHistoryRecord> allHistory = repository.findAllProductsWithHistory();
        
        ProductHistoryRecord targetProduct = allHistory.stream()
                .filter(p -> p.productId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Producto no encontrado.")); // Idealmente usar Exception personalizada

        // Calcular los ListDTO (para tener las prioridades globales correctas)
        List<DemandForecastListDTO> globalBatch = calculateGlobalBatchFromHistory(allHistory);
        
        // Encontrar nuestro producto en el lote
        DemandForecastListDTO targetListDto = globalBatch.stream()
                .filter(dto -> dto.productId().equals(productId))
                .findFirst()
                .orElseThrow();
                
        // Recalcular específicamente el detalle (ya que ListDTO no tiene las semanas internas y promedios)
        MovingAverageDemandCalculator.CalculationResult calcResult = 
                calculator.calculate(targetProduct.history(), targetProduct.currentStock());

        return new DemandForecastDetailDTO(
                targetProduct.productId(),
                targetProduct.productSku(),
                targetProduct.productName(),
                targetProduct.categoryId(),
                targetProduct.categoryName(),
                targetProduct.productActiveCurrent(),
                calcResult.hasSufficientHistory ? DemandForecastStatus.AVAILABLE : DemandForecastStatus.INSUFFICIENT_HISTORY,
                calcResult.forecastWeekStart,
                calcResult.forecastWeekEnd,
                modelVersion,
                method,
                windowWeeks,
                calcResult.forecastUnits,
                calcResult.forecastUnitsRoundedUp,
                calcResult.trend,
                targetListDto.demandPriority(), // Usar la prioridad calculada del lote GLOBAL
                targetProduct.currentStock(),
                calcResult.suggestedReplenishment,
                calcResult.historyWeeksCount,
                calcResult.lastCompleteWeek,
                calcResult.message,
                calcResult.historyTail,
                calcResult.previousHalfMean,
                calcResult.recentHalfMean,
                calcResult.relativeChange,
                null // El percentileRank interno no se exporta en detalle por requerimiento, o se podría calcular con el Assigner
        );
    }

    public List<DemandForecastListDTO> calculateGlobalBatch() {
        if (!isConfigurationValid) {
            throw new IllegalStateException("El servicio de pronóstico no está disponible debido a un error de configuración del modelo.");
        }
        List<ProductHistoryRecord> allHistory = repository.findAllProductsWithHistory();
        return calculateGlobalBatchFromHistory(allHistory);
    }

    private List<DemandForecastListDTO> calculateGlobalBatchFromHistory(List<ProductHistoryRecord> allHistory) {
        List<DemandForecastListDTO> batch = new ArrayList<>();
        
        for (ProductHistoryRecord product : allHistory) {
            MovingAverageDemandCalculator.CalculationResult calcResult = 
                    calculator.calculate(product.history(), product.currentStock());

            DemandForecastStatus status = calcResult.hasSufficientHistory 
                    ? DemandForecastStatus.AVAILABLE 
                    : DemandForecastStatus.INSUFFICIENT_HISTORY;

            batch.add(new DemandForecastListDTO(
                    product.productId(),
                    product.productSku(),
                    product.productName(),
                    product.categoryId(),
                    product.categoryName(),
                    product.productActiveCurrent(),
                    status,
                    calcResult.forecastWeekStart,
                    calcResult.forecastWeekEnd,
                    modelVersion,
                    method,
                    windowWeeks,
                    calcResult.forecastUnits,
                    calcResult.forecastUnitsRoundedUp,
                    calcResult.trend,
                    null, // Prioridad se asigna después para todos
                    product.currentStock(),
                    calcResult.suggestedReplenishment,
                    calcResult.historyWeeksCount,
                    calcResult.lastCompleteWeek,
                    calcResult.message
            ));
        }

        // Asignar prioridad globalmente considerando todo el lote
        priorityAssigner.assignPriorities(batch);
        
        return batch;
    }
    
    private Comparator<DemandForecastListDTO> getComparatorForProperty(String property) {
        return switch (property) {
            case "productId" -> Comparator.comparing(DemandForecastListDTO::productId, Comparator.nullsLast(Comparator.naturalOrder()));
            case "productName" -> Comparator.comparing(DemandForecastListDTO::productName, Comparator.nullsLast(Comparator.naturalOrder()));
            case "forecastUnits" -> Comparator.comparing(DemandForecastListDTO::forecastUnits, Comparator.nullsLast(Comparator.naturalOrder()));
            case "demandPriority" -> Comparator.comparing(DemandForecastListDTO::demandPriority, Comparator.nullsLast(Comparator.naturalOrder()));
            case "trend" -> Comparator.comparing(DemandForecastListDTO::trend, Comparator.nullsLast(Comparator.naturalOrder()));
            case "suggestedReplenishment" -> Comparator.comparing(DemandForecastListDTO::suggestedReplenishment, Comparator.nullsLast(Comparator.naturalOrder()));
            case "currentStock" -> Comparator.comparing(DemandForecastListDTO::currentStock, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> throw new IllegalArgumentException("Criterio de ordenamiento inválido: " + property);
        };
    }
}
