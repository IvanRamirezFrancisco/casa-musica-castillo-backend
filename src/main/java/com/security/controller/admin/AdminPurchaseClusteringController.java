package com.security.controller.admin;

import com.security.dto.admin.analytics.*;
import com.security.service.analytics.PurchaseClusteringAnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controlador REST administrativo para el módulo de clustering de tipos de compra
 * (Propuesta 1 — Análisis no supervisado K-Means).
 *
 * <p>
 * Todos los endpoints son de <strong>solo lectura</strong> (GET). Ningún endpoint
 * modifica datos, ejecuta modelos ni sube archivos.
 * </p>
 *
 * <p>
 * La protección principal es {@code hasAuthority('REPORT_VIEW')}, que corresponde
 * al permiso granular ya existente en el RBAC del sistema. El filtro de seguridad
 * global ya exige autenticación para {@code /api/admin/**}; este controlador agrega
 * la capa de autorización específica por permiso.
 * </p>
 *
 * <ul>
 *   <li>{@code GET /api/admin/analytics/purchase-clustering/active}
 *       — Resumen de la ejecución activa</li>
 *   <li>{@code GET /api/admin/analytics/purchase-clustering/active/profiles}
 *       — Todos los perfiles de la ejecución activa</li>
 *   <li>{@code GET /api/admin/analytics/purchase-clustering/active/profiles/{clusterNumber}}
 *       — Detalle de un perfil concreto</li>
 *   <li>{@code GET /api/admin/analytics/purchase-clustering/active/profiles/{clusterNumber}/orders}
 *       — Órdenes paginadas de un cluster</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/analytics/purchase-clustering")
@PreAuthorize("hasAuthority('REPORT_VIEW')")
public class AdminPurchaseClusteringController {

    private static final Logger log = LoggerFactory.getLogger(AdminPurchaseClusteringController.class);

    private final PurchaseClusteringAnalyticsService clusteringService;

    public AdminPurchaseClusteringController(PurchaseClusteringAnalyticsService clusteringService) {
        this.clusteringService = clusteringService;
    }

    /**
     * Retorna el resumen de la ejecución activa del modelo K-Means, incluyendo
     * métricas de calidad, configuración y verificación de integridad.
     *
     * @return 200 OK con {@link PurchaseClusteringSummaryDTO},
     *         o 404 si no existe ejecución activa
     */
    @GetMapping("/active")
    public ResponseEntity<PurchaseClusteringSummaryDTO> getActiveSummary() {
        log.info("[Admin][Clustering] GET /active — resumen solicitado");
        return ResponseEntity.ok(clusteringService.getActiveSummary());
    }

    /**
     * Retorna todos los perfiles de la ejecución activa ordenados por número de cluster.
     *
     * @return 200 OK con lista de {@link PurchaseClusterProfileDTO}
     */
    @GetMapping("/active/profiles")
    public ResponseEntity<List<PurchaseClusterProfileDTO>> getActiveProfiles() {
        log.info("[Admin][Clustering] GET /active/profiles — perfiles solicitados");
        return ResponseEntity.ok(clusteringService.getActiveProfiles());
    }

    /**
     * Retorna el detalle de un cluster específico de la ejecución activa.
     *
     * @param clusterNumber número del cluster (debe ser ≥ 0)
     * @return 200 OK con {@link PurchaseClusterProfileDTO},
     *         404 si el cluster no existe, 400 si el número es negativo
     */
    @GetMapping("/active/profiles/{clusterNumber}")
    public ResponseEntity<PurchaseClusterProfileDTO> getActiveProfile(
            @PathVariable short clusterNumber) {
        log.info("[Admin][Clustering] GET /active/profiles/{} — perfil solicitado", clusterNumber);
        return ResponseEntity.ok(clusteringService.getActiveProfile(clusterNumber));
    }

    /**
     * Retorna las órdenes de un cluster de forma paginada.
     *
     * <p>
     * Los parámetros {@code page} y {@code size} son opcionales con valores
     * por defecto de 0 y 20 respectivamente. El tamaño máximo permitido es 100.
     * El ordenamiento es fijo por fecha de creación descendente.
     * </p>
     *
     * @param clusterNumber número del cluster (debe ser ≥ 0)
     * @param page          número de página, 0-based (por defecto: 0)
     * @param size          tamaño de página (por defecto: 20, máximo: 100)
     * @return 200 OK con {@link PurchaseClusterOrdersPageDTO},
     *         404 si el cluster no existe, 400 si los parámetros son inválidos
     */
    @GetMapping("/active/profiles/{clusterNumber}/orders")
    public ResponseEntity<PurchaseClusterOrdersPageDTO> getActiveClusterOrders(
            @PathVariable short clusterNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("[API] GET /active/profiles/{}/orders?page={}&size={}", clusterNumber, page, size);
        return ResponseEntity.ok(clusteringService.getActiveClusterOrders(clusterNumber, page, size));
    }

    /**
     * Predice el cluster de una orden calculando la distancia euclidiana
     * a los centroides del modelo JSON cargado en memoria.
     *
     * @param orderId Identificador de la orden
     * @return 200 OK con {@link PurchaseOrderPredictionDTO}, o 404 si la orden no existe
     */
    @GetMapping("/orders/{orderId}/prediction")
    public ResponseEntity<PurchaseOrderPredictionDTO> predictClusterForOrder(@PathVariable Long orderId) {
        log.info("[API] GET /orders/{}/prediction", orderId);
        return ResponseEntity.ok(clusteringService.predictClusterForOrder(orderId));
    }

    /**
     * Retorna el estado y los metadatos del modelo cargado en memoria,
     * sin exponer los centroides ni las medias/escalas por seguridad.
     *
     * @return 200 OK con {@link PurchaseClusteringModelStatusDTO}
     */
    @GetMapping("/model/status")
    public ResponseEntity<PurchaseClusteringModelStatusDTO> getModelStatus() {
        log.info("[API] GET /model/status");
        return ResponseEntity.ok(clusteringService.getModelStatus());
    }
}
