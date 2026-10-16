package com.security.dto.admin.analytics.forecast;

import java.time.LocalDate;

public record RawWeeklyData(
        LocalDate weekStart,
        LocalDate weekEnd,
        Integer unitsSold,
        Integer ordersCount
) {}
