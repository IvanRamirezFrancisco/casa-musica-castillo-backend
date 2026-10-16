package com.security.dto.admin.analytics.forecast;

import java.time.LocalDate;

public record WeeklySalesRecord(
        LocalDate weekStart,
        LocalDate weekEnd,
        Integer unitsSold,
        Integer ordersCount
) {}
