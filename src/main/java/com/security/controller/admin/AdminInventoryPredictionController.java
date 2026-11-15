package com.security.controller.admin;

import com.security.dto.admin.InventoryPredictionDTO;
import com.security.service.InventoryPredictionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controlador REST para la predicción de agotamiento de inventario.
 *
 * <p>
 * Expone el modelo de decaimiento exponencial {@code I(t) = I₀ · e^(−k·t)}
 * calculado sobre el historial de stock de PostgreSQL, para las cuatro
 * secciones principales del catálogo musical.
 * </p>
 *
 * <ul>
 * <li>{@code GET /api/admin/inventory/prediction} — Lista de predicciones</li>
 * </ul>
 *
 * <p>
 * Acceso permitido a {@code ROLE_ADMIN} y {@code ROLE_SUPER_ADMIN}.
 * </p>
 */
@RestController
@RequestMapping("/api/admin/inventory")
@PreAuthorize("hasAuthority('REPORT_VIEW')")
public class AdminInventoryPredictionController {

    private static final Logger log = LoggerFactory.getLogger(AdminInventoryPredictionController.class);

    private final InventoryPredictionService predictionService;

    public AdminInventoryPredictionController(InventoryPredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /**
     * Retorna las predicciones de agotamiento de inventario para cada sección.
     *
     * <p>
     * Cada elemento incluye la constante {@code k}, los días proyectados
     * hasta nivel crítico y el estado semáforo (CRITICAL / WARNING / STABLE).
     * </p>
     *
     * @return 200 OK con lista de {@link InventoryPredictionDTO}
     */
    @GetMapping("/prediction")
    public ResponseEntity<List<InventoryPredictionDTO>> getPredictions() {
        log.info("[Admin] Solicitud de predicción de agotamiento de inventario");
        List<InventoryPredictionDTO> predictions = predictionService.getPredictions();
        return ResponseEntity.ok(predictions);
    }
}
