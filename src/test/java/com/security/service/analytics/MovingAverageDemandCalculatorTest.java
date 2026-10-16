package com.security.service.analytics;

import com.security.dto.admin.analytics.forecast.RawWeeklyData;
import com.security.enums.DemandTrend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MovingAverageDemandCalculatorTest {

    private MovingAverageDemandCalculator calculator;
    
    // Fijamos una fecha simulada como "ahora" para que the GlobalLastCompleteWeek sea determinista.
    // Supongamos que HOY es Martes 2026-08-04. 
    // El anterior lunes o mismo lunes es 2026-08-03.
    // Menos 7 días = 2026-07-27.
    // Ese lunes 2026-07-27 será el Global Last Complete Week.
    private final LocalDate simulatedNow = LocalDate.of(2026, 8, 4); 
    private final LocalDate expectedLastCompleteWeekStart = LocalDate.of(2026, 7, 27);

    @BeforeEach
    void setUp() {
        calculator = new MovingAverageDemandCalculator();
    }

    private List<RawWeeklyData> buildHistory(int count, int startUnits, boolean continuous) {
        List<RawWeeklyData> history = new ArrayList<>();
        // Trabajamos hacia atrás desde el last complete week esperado
        LocalDate currentStart = expectedLastCompleteWeekStart.minusDays(7L * (count - 1));
        
        for (int i = 0; i < count; i++) {
            history.add(new RawWeeklyData(currentStart, currentStart.plusDays(6), startUnits + i, 1));
            
            if (!continuous && i == count / 2) {
                currentStart = currentStart.plusDays(14); // Brecha
            } else {
                currentStart = currentStart.plusDays(7);
            }
        }
        return history;
    }

    @Test
    void testSMA_And_Rounding_Exact() {
        // forecastUnits = 1.25 -> RoundedUp = 2 -> Stock = 1 -> Replenishment = 1
        // Para que la suma de 12 meses sea 15 (15/12 = 1.25):
        // ponemos 12 meses de ventas, sumando 15
        List<RawWeeklyData> history = new ArrayList<>();
        LocalDate currentStart = expectedLastCompleteWeekStart.minusDays(7L * 11);
        for(int i=0; i<11; i++) {
            history.add(new RawWeeklyData(currentStart, currentStart.plusDays(6), 1, 1));
            currentStart = currentStart.plusDays(7);
        }
        history.add(new RawWeeklyData(currentStart, currentStart.plusDays(6), 4, 1)); // Total = 11*1 + 4 = 15
        
        MovingAverageDemandCalculator.CalculationResult res = calculator.calculateWithSimulatedNow(history, 1, simulatedNow);
        
        assertTrue(res.hasSufficientHistory);
        assertEquals(0, res.forecastUnits.compareTo(new BigDecimal("1.250000")));
        assertEquals(2, res.forecastUnitsRoundedUp);
        assertEquals(1, res.suggestedReplenishment);
    }
    
    @Test
    void testSMA_Fraction_Rounding() {
        // forecast = 0.083333 -> RoundedUp = 1 -> Stock = 0 -> Replenish = 1
        List<RawWeeklyData> history = new ArrayList<>();
        LocalDate currentStart = expectedLastCompleteWeekStart.minusDays(7L * 11);
        for(int i=0; i<11; i++) {
            history.add(new RawWeeklyData(currentStart, currentStart.plusDays(6), 0, 1));
            currentStart = currentStart.plusDays(7);
        }
        history.add(new RawWeeklyData(currentStart, currentStart.plusDays(6), 1, 1)); // Total = 1
        
        MovingAverageDemandCalculator.CalculationResult res = calculator.calculateWithSimulatedNow(history, 0, simulatedNow);
        
        assertEquals(0, res.forecastUnits.compareTo(new BigDecimal("0.083333")));
        assertEquals(1, res.forecastUnitsRoundedUp);
        assertEquals(1, res.suggestedReplenishment);
    }

    @Test
    void testAllZeros() {
        List<RawWeeklyData> history = buildHistory(12, 0, true);
        for (int i = 0; i < history.size(); i++) {
            history.set(i, new RawWeeklyData(history.get(i).weekStart(), history.get(i).weekEnd(), 0, 0));
        }

        MovingAverageDemandCalculator.CalculationResult res = calculator.calculateWithSimulatedNow(history, 0, simulatedNow);

        assertTrue(res.hasSufficientHistory);
        assertEquals(0, res.forecastUnits.compareTo(BigDecimal.ZERO));
        assertEquals(0, res.forecastUnitsRoundedUp);
        assertEquals(DemandTrend.ESTABLE, res.trend);
        assertEquals(0, res.suggestedReplenishment);
    }

    @Test
    void testReplenishmentLogic() {
        List<RawWeeklyData> history = new ArrayList<>();
        LocalDate currentStart = expectedLastCompleteWeekStart.minusDays(7L * 11);
        for(int i=0; i<12; i++) {
            history.add(new RawWeeklyData(currentStart, currentStart.plusDays(6), 5, 1));
            currentStart = currentStart.plusDays(7);
        }
        // SMA = 5, Rounded = 5
        
        assertEquals(2, calculator.calculateWithSimulatedNow(history, 3, simulatedNow).suggestedReplenishment);
        assertEquals(0, calculator.calculateWithSimulatedNow(history, 5, simulatedNow).suggestedReplenishment);
        assertEquals(0, calculator.calculateWithSimulatedNow(history, 10, simulatedNow).suggestedReplenishment);
    }

    @Test
    void testTrendCreciente() {
        List<RawWeeklyData> history = new ArrayList<>();
        LocalDate currentStart = expectedLastCompleteWeekStart.minusDays(7L * 11);
        for(int i=0; i<6; i++) {
            history.add(new RawWeeklyData(currentStart, currentStart.plusDays(6), 0, 1));
            currentStart = currentStart.plusDays(7);
        }
        for(int i=0; i<6; i++) {
            history.add(new RawWeeklyData(currentStart, currentStart.plusDays(6), 5, 1));
            currentStart = currentStart.plusDays(7);
        }
        
        MovingAverageDemandCalculator.CalculationResult res = calculator.calculateWithSimulatedNow(history, 0, simulatedNow);
        assertEquals(DemandTrend.CRECIENTE, res.trend); // 0 a >0
    }
    
    @Test
    void testTrendDecreciente() {
        List<RawWeeklyData> history = new ArrayList<>();
        LocalDate currentStart = expectedLastCompleteWeekStart.minusDays(7L * 11);
        for(int i=0; i<6; i++) {
            history.add(new RawWeeklyData(currentStart, currentStart.plusDays(6), 10, 1));
            currentStart = currentStart.plusDays(7);
        }
        for(int i=0; i<6; i++) {
            history.add(new RawWeeklyData(currentStart, currentStart.plusDays(6), 5, 1));
            currentStart = currentStart.plusDays(7);
        }
        
        MovingAverageDemandCalculator.CalculationResult res = calculator.calculateWithSimulatedNow(history, 0, simulatedNow);
        assertEquals(DemandTrend.DECRECIENTE, res.trend);
    }
    
    @Test
    void testTrendLimits() {
        List<RawWeeklyData> history10pct = new ArrayList<>();
        LocalDate currentStart = expectedLastCompleteWeekStart.minusDays(7L * 11);
        for(int i=0; i<6; i++) {
            history10pct.add(new RawWeeklyData(currentStart, currentStart.plusDays(6), 10, 1));
            currentStart = currentStart.plusDays(7);
        }
        for(int i=0; i<6; i++) {
            history10pct.add(new RawWeeklyData(currentStart, currentStart.plusDays(6), 11, 1)); // 10% change
            currentStart = currentStart.plusDays(7);
        }
        assertEquals(DemandTrend.ESTABLE, calculator.calculateWithSimulatedNow(history10pct, 0, simulatedNow).trend);
    }

    @Test
    void testHistoryGapsAndCounts() {
        // 11 semanas
        assertFalse(calculator.calculateWithSimulatedNow(buildHistory(11, 1, true), 0, simulatedNow).hasSufficientHistory);
        
        // Exact 12
        assertTrue(calculator.calculateWithSimulatedNow(buildHistory(12, 1, true), 0, simulatedNow).hasSufficientHistory);
        
        // Gap
        assertFalse(calculator.calculateWithSimulatedNow(buildHistory(12, 1, false), 0, simulatedNow).hasSufficientHistory);
    }
}
