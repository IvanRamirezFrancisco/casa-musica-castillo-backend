package com.security.service.analytics;

import com.security.dto.admin.analytics.forecast.DemandForecastListDTO;
import com.security.enums.DemandForecastStatus;
import com.security.enums.DemandPriority;
import com.security.enums.DemandTrend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DemandPriorityAssignerTest {

    private DemandPriorityAssigner assigner;

    @BeforeEach
    void setUp() {
        assigner = new DemandPriorityAssigner();
    }

    private DemandForecastListDTO createDTO(Long id, BigDecimal units, DemandForecastStatus status) {
        return new DemandForecastListDTO(
                id, "SKU", "Name", 1L, "Cat", true,
                status, LocalDate.now(), LocalDate.now(),
                "V1", "SMA", 12, units, 1,
                DemandTrend.ESTABLE, null, 10, 0, 12, LocalDate.now(), ""
        );
    }

    @Test
    void testSingleProduct() {
        List<DemandForecastListDTO> batch = new ArrayList<>();
        batch.add(createDTO(1L, BigDecimal.TEN, DemandForecastStatus.AVAILABLE));

        assigner.assignPriorities(batch);

        // 1/1 = 1.0 -> ALTA
        assertEquals(DemandPriority.ALTA, batch.get(0).demandPriority());
    }

    @Test
    void testTwoProducts() {
        List<DemandForecastListDTO> batch = new ArrayList<>();
        batch.add(createDTO(1L, BigDecimal.valueOf(20), DemandForecastStatus.AVAILABLE));
        batch.add(createDTO(2L, BigDecimal.valueOf(10), DemandForecastStatus.AVAILABLE));

        assigner.assignPriorities(batch);

        // id=2 is lower -> pos 1. 1/2 = 0.5 -> MEDIA
        // id=1 is higher -> pos 2. 2/2 = 1.0 -> ALTA
        assertEquals(DemandPriority.ALTA, batch.stream().filter(b -> b.productId().equals(1L)).findFirst().get().demandPriority());
        assertEquals(DemandPriority.MEDIA, batch.stream().filter(b -> b.productId().equals(2L)).findFirst().get().demandPriority());
    }

    @Test
    void testThreeProducts() {
        List<DemandForecastListDTO> batch = new ArrayList<>();
        batch.add(createDTO(1L, BigDecimal.valueOf(30), DemandForecastStatus.AVAILABLE));
        batch.add(createDTO(2L, BigDecimal.valueOf(10), DemandForecastStatus.AVAILABLE));
        batch.add(createDTO(3L, BigDecimal.valueOf(20), DemandForecastStatus.AVAILABLE));

        assigner.assignPriorities(batch);

        assertEquals(DemandPriority.ALTA, batch.stream().filter(b -> b.productId().equals(1L)).findFirst().get().demandPriority());
        assertEquals(DemandPriority.MEDIA, batch.stream().filter(b -> b.productId().equals(3L)).findFirst().get().demandPriority());
        assertEquals(DemandPriority.BAJA, batch.stream().filter(b -> b.productId().equals(2L)).findFirst().get().demandPriority());
    }

    @Test
    void testDivisibleByThree() {
        List<DemandForecastListDTO> batch = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            batch.add(createDTO((long) i, BigDecimal.valueOf(i * 10), DemandForecastStatus.AVAILABLE));
        }
        assigner.assignPriorities(batch);

        // 1, 2 -> BAJA
        // 3, 4 -> MEDIA
        // 5, 6 -> ALTA
        assertEquals(DemandPriority.BAJA, batch.get(0).demandPriority()); // id=1
        assertEquals(DemandPriority.BAJA, batch.get(1).demandPriority()); // id=2
        assertEquals(DemandPriority.MEDIA, batch.get(2).demandPriority());
        assertEquals(DemandPriority.MEDIA, batch.get(3).demandPriority());
        assertEquals(DemandPriority.ALTA, batch.get(4).demandPriority());
        assertEquals(DemandPriority.ALTA, batch.get(5).demandPriority());
    }

    @Test
    void testIdenticalForecastsAndIdTieBreaker() {
        List<DemandForecastListDTO> batch = new ArrayList<>();
        batch.add(createDTO(3L, BigDecimal.TEN, DemandForecastStatus.AVAILABLE));
        batch.add(createDTO(2L, BigDecimal.TEN, DemandForecastStatus.AVAILABLE));
        batch.add(createDTO(1L, BigDecimal.TEN, DemandForecastStatus.AVAILABLE));

        assigner.assignPriorities(batch);

        // Sorted by ID: 1, 2, 3
        assertEquals(DemandPriority.BAJA, batch.stream().filter(b -> b.productId().equals(1L)).findFirst().get().demandPriority());
        assertEquals(DemandPriority.MEDIA, batch.stream().filter(b -> b.productId().equals(2L)).findFirst().get().demandPriority());
        assertEquals(DemandPriority.ALTA, batch.stream().filter(b -> b.productId().equals(3L)).findFirst().get().demandPriority());
    }

    @Test
    void testExcludeInsufficientHistory() {
        List<DemandForecastListDTO> batch = new ArrayList<>();
        batch.add(createDTO(1L, BigDecimal.TEN, DemandForecastStatus.AVAILABLE));
        batch.add(createDTO(2L, null, DemandForecastStatus.INSUFFICIENT_HISTORY));

        assigner.assignPriorities(batch);

        assertEquals(DemandPriority.ALTA, batch.stream().filter(b -> b.productId().equals(1L)).findFirst().get().demandPriority());
        assertNull(batch.stream().filter(b -> b.productId().equals(2L)).findFirst().get().demandPriority());
    }
}
