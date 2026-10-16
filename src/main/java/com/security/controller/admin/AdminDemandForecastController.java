package com.security.controller.admin;

import com.security.dto.admin.analytics.forecast.DemandForecastDetailDTO;
import com.security.dto.admin.analytics.forecast.DemandForecastListDTO;
import com.security.dto.admin.analytics.forecast.DemandForecastModelStatusDTO;
import com.security.service.analytics.DemandForecastService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/analytics/demand-forecast")
@PreAuthorize("hasAuthority('REPORT_VIEW') or hasRole('SUPER_ADMIN')")
public class AdminDemandForecastController {

    private final DemandForecastService forecastService;

    public AdminDemandForecastController(DemandForecastService forecastService) {
        this.forecastService = forecastService;
    }

    @GetMapping({"", "/products"})
    public ResponseEntity<Page<DemandForecastListDTO>> getForecasts(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String trend,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String forecastStatus,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String search,
            Pageable pageable) {

        Page<DemandForecastListDTO> page = forecastService.getPaginatedForecasts(
                categoryId, trend, priority, forecastStatus, active, search, pageable);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/products/{productId}")
    public ResponseEntity<DemandForecastDetailDTO> getForecastDetail(@PathVariable Long productId) {
        try {
            DemandForecastDetailDTO detail = forecastService.getForecastDetail(productId);
            return ResponseEntity.ok(detail);
        } catch (RuntimeException ex) {
            if (ex.getMessage().contains("no encontrado")) {
                return ResponseEntity.notFound().build();
            }
            throw ex;
        }
    }

    @GetMapping("/model/status")
    public ResponseEntity<DemandForecastModelStatusDTO> getModelStatus() {
        return ResponseEntity.ok(forecastService.getModelStatus());
    }
}
