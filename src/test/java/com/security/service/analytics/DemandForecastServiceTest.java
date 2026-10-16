package com.security.service.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.dto.admin.analytics.forecast.DemandForecastDetailDTO;
import com.security.dto.admin.analytics.forecast.DemandForecastListDTO;
import com.security.dto.admin.analytics.forecast.ProductHistoryRecord;
import com.security.dto.admin.analytics.forecast.RawWeeklyData;
import com.security.enums.DemandForecastStatus;
import com.security.enums.DemandPriority;
import com.security.repository.analytics.DemandForecastRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemandForecastServiceTest {

    private DemandForecastService service;

    @Mock
    private DemandForecastRepository repository;
    
    @Mock
    private ObjectMapper objectMapper;

    private MovingAverageDemandCalculator calculator;
    private DemandPriorityAssigner priorityAssigner;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        calculator = new MovingAverageDemandCalculator();
        priorityAssigner = new DemandPriorityAssigner();
        
        service = new DemandForecastService(repository, calculator, priorityAssigner, objectMapper);
        
        // Simular carga exitosa usando reflexión o sobreescribiendo una variable.
        // Como las variables son privadas, para el test llamaremos a un constructor o método package-private.
        // Por simplicidad, seteamos el flag con reflection.
        try {
            java.lang.reflect.Field field = DemandForecastService.class.getDeclaredField("isConfigurationValid");
            field.setAccessible(true);
            field.set(service, true);
            
            java.lang.reflect.Field modelField = DemandForecastService.class.getDeclaredField("modelVersion");
            modelField.setAccessible(true);
            modelField.set(service, "MOVING_AVERAGE_DEMAND_V1");
            
            java.lang.reflect.Field methodField = DemandForecastService.class.getDeclaredField("method");
            methodField.setAccessible(true);
            methodField.set(service, "SMA");
            
            java.lang.reflect.Field windowField = DemandForecastService.class.getDeclaredField("windowWeeks");
            windowField.setAccessible(true);
            windowField.set(service, 12);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private ProductHistoryRecord createMockProduct(Long id, boolean hasSales) {
        List<RawWeeklyData> history = new ArrayList<>();
        if (hasSales) {
            LocalDate start = calculator.getGlobalLastCompleteWeekForTests(LocalDate.now()).minusDays(7L * 11);
            for (int i = 0; i < 12; i++) {
                history.add(new RawWeeklyData(start, start.plusDays(6), 5, 1));
                start = start.plusDays(7);
            }
        }
        return new ProductHistoryRecord(id, "SKU" + id, "Name " + id, 1L, "Cat", true, 10, history);
    }

    @Test
    void testProductsWithAndWithoutSales() {
        List<ProductHistoryRecord> dbRecords = new ArrayList<>();
        dbRecords.add(createMockProduct(1L, true)); // Con historial completo
        dbRecords.add(createMockProduct(2L, false)); // Sin historial

        when(repository.findAllProductsWithHistory()).thenReturn(dbRecords);

        Page<DemandForecastListDTO> page = service.getPaginatedForecasts(
                null, null, null, null, null, null, PageRequest.of(0, 10));

        assertEquals(2, page.getTotalElements());

        DemandForecastListDTO p1 = page.getContent().stream().filter(p -> p.productId().equals(1L)).findFirst().get();
        DemandForecastListDTO p2 = page.getContent().stream().filter(p -> p.productId().equals(2L)).findFirst().get();

        assertEquals(DemandForecastStatus.AVAILABLE, p1.forecastStatus());
        assertNotNull(p1.demandPriority());

        assertEquals(DemandForecastStatus.INSUFFICIENT_HISTORY, p2.forecastStatus());
        assertNull(p2.forecastUnits());
        assertNull(p2.demandPriority());
        assertEquals(0, p2.historyWeeksCount());
    }

    @Test
    void testFiltersDoNotChangeGlobalPriority() {
        List<ProductHistoryRecord> dbRecords = new ArrayList<>();
        // 3 Productos. Sus pronósticos (5*12/12 = 5) son idénticos.
        // ID desempate -> ID 1: BAJA, ID 2: MEDIA, ID 3: ALTA
        dbRecords.add(createMockProduct(3L, true));
        dbRecords.add(createMockProduct(2L, true));
        dbRecords.add(createMockProduct(1L, true));

        when(repository.findAllProductsWithHistory()).thenReturn(dbRecords);

        // Filtro para buscar solo el ID 1 (ej. por nombre)
        Page<DemandForecastListDTO> page = service.getPaginatedForecasts(
                null, null, null, null, null, "Name 1", PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements());
        DemandForecastListDTO p1 = page.getContent().get(0);
        
        // Aunque esté solo en la página, su prioridad se calculó globalmente con los 3.
        assertEquals(DemandPriority.BAJA, p1.demandPriority());
    }

    @Test
    void testDetailHasSamePriorityAsList() {
        List<ProductHistoryRecord> dbRecords = new ArrayList<>();
        dbRecords.add(createMockProduct(3L, true));
        dbRecords.add(createMockProduct(2L, true));
        dbRecords.add(createMockProduct(1L, true));

        when(repository.findAllProductsWithHistory()).thenReturn(dbRecords);

        DemandForecastDetailDTO detail = service.getForecastDetail(2L);
        assertEquals(DemandPriority.MEDIA, detail.demandPriority());
    }
    
    @Test
    void testProductNotFoundInDetail() {
        when(repository.findAllProductsWithHistory()).thenReturn(new ArrayList<>());
        
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            service.getForecastDetail(99L);
        });
        
        assertTrue(exception.getMessage().contains("no encontrado"));
    }

    @Test
    void testActiveFilterPreservesGlobalPriority() {
        // Producto A (ID 1) activo, pronóstico bajo
        // Producto B (ID 2) INACTIVO, pronóstico medio
        // Producto C (ID 3) activo, pronóstico alto
        
        List<ProductHistoryRecord> dbRecords = new ArrayList<>();
        dbRecords.add(createMockProductWithUnits(1L, true, 1)); // pronostico = 1
        dbRecords.add(createMockProductWithUnits(2L, false, 2)); // pronostico = 2
        dbRecords.add(createMockProductWithUnits(3L, true, 3)); // pronostico = 3
        
        when(repository.findAllProductsWithHistory()).thenReturn(dbRecords);
        
        // 1. Verificamos sin filtro
        Page<DemandForecastListDTO> pageSinFiltro = service.getPaginatedForecasts(
                null, null, null, null, null, null, PageRequest.of(0, 10));
                
        assertEquals(3, pageSinFiltro.getTotalElements());
        assertEquals(DemandPriority.BAJA, pageSinFiltro.getContent().stream().filter(p -> p.productId().equals(1L)).findFirst().get().demandPriority());
        assertEquals(DemandPriority.MEDIA, pageSinFiltro.getContent().stream().filter(p -> p.productId().equals(2L)).findFirst().get().demandPriority());
        assertEquals(DemandPriority.ALTA, pageSinFiltro.getContent().stream().filter(p -> p.productId().equals(3L)).findFirst().get().demandPriority());

        // 2. Verificamos CON filtro active=true
        Page<DemandForecastListDTO> pageConFiltro = service.getPaginatedForecasts(
                null, null, null, null, true, null, PageRequest.of(0, 10));
                
        assertEquals(2, pageConFiltro.getTotalElements()); // B fue ocultado
        
        assertEquals(DemandPriority.BAJA, pageConFiltro.getContent().stream().filter(p -> p.productId().equals(1L)).findFirst().get().demandPriority());
        assertEquals(DemandPriority.ALTA, pageConFiltro.getContent().stream().filter(p -> p.productId().equals(3L)).findFirst().get().demandPriority());
    }

    private ProductHistoryRecord createMockProductWithUnits(Long id, boolean active, int unitsPerWeek) {
        List<RawWeeklyData> history = new ArrayList<>();
        LocalDate start = calculator.getGlobalLastCompleteWeekForTests(LocalDate.now()).minusDays(7L * 11);
        for (int i = 0; i < 12; i++) {
            history.add(new RawWeeklyData(start, start.plusDays(6), unitsPerWeek, 1));
            start = start.plusDays(7);
        }
        return new ProductHistoryRecord(id, "SKU" + id, "Name " + id, 1L, "Cat", active, 10, history);
    }
}
