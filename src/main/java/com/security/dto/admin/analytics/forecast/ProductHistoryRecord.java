package com.security.dto.admin.analytics.forecast;

import java.util.List;

public record ProductHistoryRecord(
        Long productId,
        String productSku,
        String productName,
        Long categoryId,
        String categoryName,
        Boolean productActiveCurrent,
        Integer currentStock,
        List<RawWeeklyData> history
) {}
