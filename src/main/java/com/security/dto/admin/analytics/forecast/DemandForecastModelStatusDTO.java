package com.security.dto.admin.analytics.forecast;

import java.util.Map;
import java.util.List;

public record DemandForecastModelStatusDTO(
        String modelVersion,
        String algorithmFamily,
        String method,
        Integer windowWeeks,
        Integer forecastHorizonWeeks,
        String dataSource,
        Boolean dynamicCalculation,
        String timezone,
        String weekDefinition,
        Integer minimumHistoryWeeks,
        String artifactGeneratedAt,
        Map<String, Object> evaluationMetrics,
        String limitations,
        String currentDataLastCompleteWeek,
        Integer eligibleProducts,
        Integer insufficientHistoryProducts,
        List<String> configurationErrors
) {}
