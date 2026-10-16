package com.security.service.analytics;

import com.security.dto.admin.analytics.forecast.DemandForecastDetailDTO;
import com.security.dto.admin.analytics.forecast.DemandForecastListDTO;
import com.security.enums.DemandPriority;
import com.security.enums.DemandForecastStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class DemandPriorityAssigner {

    public void assignPriorities(List<DemandForecastListDTO> batch) {
        // Step 1: Filter only AVAILABLE products
        List<DemandForecastListDTO> eligible = batch.stream()
                .filter(dto -> dto.forecastStatus() == DemandForecastStatus.AVAILABLE)
                .collect(Collectors.toList());

        if (eligible.isEmpty()) {
            return;
        }

        // Step 2 & 3: Sort by forecastUnits ASC, then productId ASC
        eligible.sort(Comparator
                .comparing(DemandForecastListDTO::forecastUnits)
                .thenComparing(DemandForecastListDTO::productId));

        int totalEligible = eligible.size();
        BigDecimal total = BigDecimal.valueOf(totalEligible);

        // Map to keep track of assigned priorities temporarily (for rebuilding the list)
        List<DemandForecastListDTO> updatedEligible = new ArrayList<>();

        for (int i = 0; i < totalEligible; i++) {
            DemandForecastListDTO current = eligible.get(i);
            
            // Step 4 & 5: Position is 1-indexed
            int position = i + 1;
            BigDecimal percentileRank = BigDecimal.valueOf(position)
                    .divide(total, 6, RoundingMode.HALF_UP);

            // Step 6: Classify
            DemandPriority priority;
            if (percentileRank.compareTo(BigDecimal.ZERO) > 0 && percentileRank.compareTo(new BigDecimal("0.333333")) <= 0) {
                priority = DemandPriority.BAJA;
            } else if (percentileRank.compareTo(new BigDecimal("0.333333")) > 0 && percentileRank.compareTo(new BigDecimal("0.666667")) <= 0) {
                priority = DemandPriority.MEDIA;
            } else {
                priority = DemandPriority.ALTA;
            }

            DemandForecastListDTO updatedDto = new DemandForecastListDTO(
                    current.productId(),
                    current.productSku(),
                    current.productName(),
                    current.categoryId(),
                    current.categoryName(),
                    current.productActive(),
                    current.forecastStatus(),
                    current.forecastWeekStart(),
                    current.forecastWeekEnd(),
                    current.modelVersion(),
                    current.modelMethod(),
                    current.windowWeeks(),
                    current.forecastUnits(),
                    current.forecastUnitsRoundedUp(),
                    current.trend(),
                    priority,
                    current.currentStock(),
                    current.suggestedReplenishment(),
                    current.historyWeeksCount(),
                    current.lastCompleteWeek(),
                    current.message()
            );
            
            updatedEligible.add(updatedDto);
        }

        // Update the original batch with the new objects that contain the priority
        for (int i = 0; i < batch.size(); i++) {
            DemandForecastListDTO original = batch.get(i);
            if (original.forecastStatus() == DemandForecastStatus.AVAILABLE) {
                for (DemandForecastListDTO updated : updatedEligible) {
                    if (original.productId().equals(updated.productId())) {
                        batch.set(i, updated);
                        break;
                    }
                }
            }
        }
    }
    
    // Variant for DetailDTO list if needed, or simply assign it singlely if we have the global list
    public void assignPriorityToDetail(DemandForecastDetailDTO detail, List<DemandForecastListDTO> globalBatch) {
        if (detail.forecastStatus() != DemandForecastStatus.AVAILABLE) return;
        
        assignPriorities(globalBatch);
        
        DemandPriority assignedPriority = globalBatch.stream()
                .filter(b -> b.productId().equals(detail.productId()))
                .map(DemandForecastListDTO::demandPriority)
                .findFirst()
                .orElse(null);
                
        // In a real scenario we'd recreate the DetailDTO to return it, 
        // this logic will be applied in the Service directly to avoid mutability issues.
    }
    
    public BigDecimal calculatePercentileRank(int position, int totalEligible) {
        return BigDecimal.valueOf(position).divide(BigDecimal.valueOf(totalEligible), 6, RoundingMode.HALF_UP);
    }
}
