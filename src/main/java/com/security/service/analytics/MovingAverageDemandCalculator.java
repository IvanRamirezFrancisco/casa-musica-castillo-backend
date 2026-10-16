package com.security.service.analytics;

import com.security.dto.admin.analytics.forecast.RawWeeklyData;
import com.security.dto.admin.analytics.forecast.WeeklySalesRecord;
import com.security.enums.DemandTrend;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class MovingAverageDemandCalculator {

    private static final int REQUIRED_WEEKS = 12;

    public static class CalculationResult {
        public boolean hasSufficientHistory;
        public BigDecimal forecastUnits;
        public Integer forecastUnitsRoundedUp;
        public DemandTrend trend;
        public Integer suggestedReplenishment;
        public List<WeeklySalesRecord> historyTail;
        public BigDecimal previousHalfMean;
        public BigDecimal recentHalfMean;
        public BigDecimal relativeChange;
        public int historyWeeksCount;
        public LocalDate lastCompleteWeek;
        public LocalDate forecastWeekStart;
        public LocalDate forecastWeekEnd;
        public String message;
    }

    public CalculationResult calculate(List<RawWeeklyData> history, int currentStock) {
        CalculationResult result = new CalculationResult();
        
        if (history == null || history.isEmpty()) {
            result.hasSufficientHistory = false;
            result.historyWeeksCount = 0;
            result.message = "Producto sin ventas registradas.";
            return result;
        }

        result.historyWeeksCount = history.size();
        
        LocalDate globalLastCompleteWeek = getGlobalLastCompleteWeek();
        
        // Find if history is contiguous up to the global last complete week
        List<RawWeeklyData> tail = extractTail(history, globalLastCompleteWeek);

        if (tail == null || tail.size() < REQUIRED_WEEKS) {
            result.hasSufficientHistory = false;
            result.message = "Historial insuficiente o brecha detectada en las últimas 12 semanas.";
            return result;
        }

        result.hasSufficientHistory = true;
        result.historyTail = tail.stream()
                .map(r -> new WeeklySalesRecord(r.weekStart(), r.weekEnd(), r.unitsSold(), r.ordersCount()))
                .collect(Collectors.toList());

        result.lastCompleteWeek = tail.get(tail.size() - 1).weekStart();
        result.forecastWeekStart = result.lastCompleteWeek.plusDays(7);
        result.forecastWeekEnd = result.forecastWeekStart.plusDays(6);

        // Calculate SMA
        int totalUnits = tail.stream().mapToInt(RawWeeklyData::unitsSold).sum();
        result.forecastUnits = BigDecimal.valueOf(totalUnits)
                .divide(BigDecimal.valueOf(REQUIRED_WEEKS), 6, RoundingMode.HALF_UP);

        result.forecastUnitsRoundedUp = result.forecastUnits.max(BigDecimal.ZERO)
                .setScale(0, RoundingMode.CEILING).intValue();

        result.suggestedReplenishment = Math.max(0, result.forecastUnitsRoundedUp - currentStock);

        // Trend
        List<RawWeeklyData> previousHalf = tail.subList(0, 6);
        List<RawWeeklyData> recentHalf = tail.subList(6, 12);

        int prevSum = previousHalf.stream().mapToInt(RawWeeklyData::unitsSold).sum();
        int recSum = recentHalf.stream().mapToInt(RawWeeklyData::unitsSold).sum();

        result.previousHalfMean = BigDecimal.valueOf(prevSum).divide(BigDecimal.valueOf(6), 6, RoundingMode.HALF_UP);
        result.recentHalfMean = BigDecimal.valueOf(recSum).divide(BigDecimal.valueOf(6), 6, RoundingMode.HALF_UP);

        if (result.previousHalfMean.compareTo(BigDecimal.ZERO) == 0 && result.recentHalfMean.compareTo(BigDecimal.ZERO) == 0) {
            result.trend = DemandTrend.ESTABLE;
            result.relativeChange = BigDecimal.ZERO;
        } else if (result.previousHalfMean.compareTo(BigDecimal.ZERO) == 0 && result.recentHalfMean.compareTo(BigDecimal.ZERO) > 0) {
            result.trend = DemandTrend.CRECIENTE;
            result.relativeChange = null; 
        } else {
            result.relativeChange = result.recentHalfMean.subtract(result.previousHalfMean)
                    .divide(result.previousHalfMean, 6, RoundingMode.HALF_UP);
                    
            if (result.relativeChange.compareTo(new BigDecimal("0.10")) > 0) {
                result.trend = DemandTrend.CRECIENTE;
            } else if (result.relativeChange.compareTo(new BigDecimal("-0.10")) < 0) {
                result.trend = DemandTrend.DECRECIENTE;
            } else {
                result.trend = DemandTrend.ESTABLE;
            }
        }

        return result;
    }

    private List<RawWeeklyData> extractTail(List<RawWeeklyData> history, LocalDate expectedLastWeekStart) {
        if (history.size() < REQUIRED_WEEKS) {
            return null;
        }

        List<RawWeeklyData> last12 = history.subList(history.size() - REQUIRED_WEEKS, history.size());

        if (!last12.get(last12.size() - 1).weekStart().equals(expectedLastWeekStart)) {
            return null; // The history doesn't reach the global last complete week
        }

        // Verify continuity
        for (int i = 0; i < last12.size() - 1; i++) {
            LocalDate current = last12.get(i).weekStart();
            LocalDate next = last12.get(i + 1).weekStart();
            if (!current.plusDays(7).equals(next)) {
                return null;
            }
        }

        return last12;
    }
    
    private LocalDate getGlobalLastCompleteWeek() {
        ZonedDateTime nowMexico = ZonedDateTime.now(ZoneId.of("America/Mexico_City"));
        return nowMexico.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(7).toLocalDate();
    }
    
    // Test utility method
    public LocalDate getGlobalLastCompleteWeekForTests(LocalDate simulatedNow) {
        return simulatedNow.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(7);
    }
    
    // Added for tests
    public CalculationResult calculateWithSimulatedNow(List<RawWeeklyData> history, int currentStock, LocalDate simulatedNow) {
        // A hack for tests without mocking static/time
        LocalDate globalLastCompleteWeek = getGlobalLastCompleteWeekForTests(simulatedNow);
        
        CalculationResult result = new CalculationResult();
        
        if (history == null || history.isEmpty()) {
            result.hasSufficientHistory = false;
            result.historyWeeksCount = 0;
            result.message = "Producto sin ventas registradas.";
            return result;
        }

        result.historyWeeksCount = history.size();
        
        List<RawWeeklyData> tail = extractTail(history, globalLastCompleteWeek);

        if (tail == null || tail.size() < REQUIRED_WEEKS) {
            result.hasSufficientHistory = false;
            result.message = "Historial insuficiente o brecha detectada en las últimas 12 semanas.";
            return result;
        }

        result.hasSufficientHistory = true;
        result.historyTail = tail.stream()
                .map(r -> new WeeklySalesRecord(r.weekStart(), r.weekEnd(), r.unitsSold(), r.ordersCount()))
                .collect(Collectors.toList());

        result.lastCompleteWeek = tail.get(tail.size() - 1).weekStart();
        result.forecastWeekStart = result.lastCompleteWeek.plusDays(7);
        result.forecastWeekEnd = result.forecastWeekStart.plusDays(6);

        int totalUnits = tail.stream().mapToInt(RawWeeklyData::unitsSold).sum();
        result.forecastUnits = BigDecimal.valueOf(totalUnits)
                .divide(BigDecimal.valueOf(REQUIRED_WEEKS), 6, RoundingMode.HALF_UP);

        result.forecastUnitsRoundedUp = result.forecastUnits.max(BigDecimal.ZERO)
                .setScale(0, RoundingMode.CEILING).intValue();

        result.suggestedReplenishment = Math.max(0, result.forecastUnitsRoundedUp - currentStock);

        List<RawWeeklyData> previousHalf = tail.subList(0, 6);
        List<RawWeeklyData> recentHalf = tail.subList(6, 12);

        int prevSum = previousHalf.stream().mapToInt(RawWeeklyData::unitsSold).sum();
        int recSum = recentHalf.stream().mapToInt(RawWeeklyData::unitsSold).sum();

        result.previousHalfMean = BigDecimal.valueOf(prevSum).divide(BigDecimal.valueOf(6), 6, RoundingMode.HALF_UP);
        result.recentHalfMean = BigDecimal.valueOf(recSum).divide(BigDecimal.valueOf(6), 6, RoundingMode.HALF_UP);

        if (result.previousHalfMean.compareTo(BigDecimal.ZERO) == 0 && result.recentHalfMean.compareTo(BigDecimal.ZERO) == 0) {
            result.trend = DemandTrend.ESTABLE;
            result.relativeChange = BigDecimal.ZERO;
        } else if (result.previousHalfMean.compareTo(BigDecimal.ZERO) == 0 && result.recentHalfMean.compareTo(BigDecimal.ZERO) > 0) {
            result.trend = DemandTrend.CRECIENTE;
            result.relativeChange = null; 
        } else {
            result.relativeChange = result.recentHalfMean.subtract(result.previousHalfMean)
                    .divide(result.previousHalfMean, 6, RoundingMode.HALF_UP);
                    
            if (result.relativeChange.compareTo(new BigDecimal("0.10")) > 0) {
                result.trend = DemandTrend.CRECIENTE;
            } else if (result.relativeChange.compareTo(new BigDecimal("-0.10")) < 0) {
                result.trend = DemandTrend.DECRECIENTE;
            } else {
                result.trend = DemandTrend.ESTABLE;
            }
        }

        return result;
    }
}
