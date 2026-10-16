package com.security.dto.admin.analytics.forecast;

import com.security.enums.DemandForecastStatus;
import com.security.enums.DemandPriority;
import com.security.enums.DemandTrend;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DemandForecastListDTO(
        Long productId,
        String productSku,
        String productName,
        Long categoryId,
        String categoryName,
        Boolean productActive,
        DemandForecastStatus forecastStatus,
        LocalDate forecastWeekStart,
        LocalDate forecastWeekEnd,
        String modelVersion,
        String modelMethod,
        Integer windowWeeks,
        BigDecimal forecastUnits,
        Integer forecastUnitsRoundedUp,
        DemandTrend trend,
        DemandPriority demandPriority,
        Integer currentStock,
        Integer suggestedReplenishment,
        Integer historyWeeksCount,
        LocalDate lastCompleteWeek,
        String message
) {}
