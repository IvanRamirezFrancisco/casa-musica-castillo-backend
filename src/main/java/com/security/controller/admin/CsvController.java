package com.security.controller.admin;

import com.security.dto.admin.*;
import com.security.enums.CollisionRule;
import com.security.service.CsvExportService;
import com.security.service.CsvImportService;
import com.security.service.CsvSecurityValidator;
import com.security.util.LogSanitizer;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Controlador REST para importación y exportación de datos en formato CSV.
 *
 * <p>
 * Base URL: {@code /api/admin/csv}
 * </p>
 *
 * <table>
 * <tr>
 * <th>Método</th>
 * <th>Ruta</th>
 * <th>Descripción</th>
 * </tr>
 * <tr>
 * <td>GET</td>
 * <td>/export/products</td>
 * <td>Descarga CSV de productos</td>
 * </tr>
 * <tr>
 * <td>GET</td>
 * <td>/export/users</td>
 * <td>Descarga CSV de usuarios</td>
 * </tr>
 * <tr>
 * <td>POST</td>
 * <td>/import/products</td>
 * <td>Importa productos desde CSV</td>
 * </tr>
 * <tr>
 * <td>POST</td>
 * <td>/import/users</td>
 * <td>Importa usuarios desde CSV</td>
 * </tr>
 * </table>
 */
@RestController
@RequestMapping("/api/admin/csv")
@PreAuthorize("hasAuthority('REPORT_EXPORT')")
public class CsvController {

    private static final Logger log = LoggerFactory.getLogger(CsvController.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final long MAX_CSV_BYTES = 10L * 1024 * 1024; // 10 MB — defensa DoS

    private final CsvExportService csvExportService;
    private final CsvImportService csvImportService;
    private final CsvSecurityValidator csvSecurityValidator;

    public CsvController(CsvExportService csvExportService,
            CsvImportService csvImportService,
            CsvSecurityValidator csvSecurityValidator) {
        this.csvExportService = csvExportService;
        this.csvImportService = csvImportService;
        this.csvSecurityValidator = csvSecurityValidator;
    }

    // ── Exportaciones ─────────────────────────────────────────────────────────

    /**
     * Exporta todos los productos a un archivo CSV descargable.
     *
     * @return archivo CSV con cabecera y una fila por producto
     */
    @GetMapping("/export/products")
    public ResponseEntity<byte[]> exportProducts() throws IOException {
        log.info("[CsvController] Exportando productos a CSV");
        byte[] csv = csvExportService.exportProducts();
        return downloadResponse(csv, "productos_" + today() + ".csv");
    }

    /**
     * Exporta todos los usuarios (clientes + staff) a un archivo CSV descargable.
     *
     * @return archivo CSV con cabecera y una fila por usuario
     */
    @GetMapping("/export/users")
    public ResponseEntity<byte[]> exportUsers() throws IOException {
        log.info("[CsvController] Exportando usuarios a CSV");
        byte[] csv = csvExportService.exportUsers();
        return downloadResponse(csv, "usuarios_" + today() + ".csv");
    }

    // ── Importaciones ─────────────────────────────────────────────────────────

    /**
     * Importa productos desde un archivo CSV con regla de colisión configurable.
     *
     * <p>
     * Defensas de seguridad aplicadas aquí (antes de llegar al servicio):
     * <ul>
     * <li><b>DoS</b>: rechaza archivos mayores a 5 MB.</li>
     * <li><b>Extensión</b>: solo acepta {@code .csv}.</li>
     * </ul>
     *
     * @param file archivo CSV (multipart/form-data, campo {@code file})
     * @param rule {@code UPDATE} (default) o {@code SKIP}
     */
    @PostMapping(value = "/import/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CsvImportResultDto> importProducts(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "rule", defaultValue = "UPDATE") CollisionRule rule) throws IOException {

        validateCsvFile(file);
        log.info("[CsvController] Importando productos desde CSV: {} | rule={}",
                LogSanitizer.sanitizeFilename(file.getOriginalFilename()), rule);
        CsvImportResultDto result = csvImportService.importProducts(file, rule);
        return ResponseEntity.ok(result);
    }

    /**
     * Importa usuarios desde un archivo CSV.
     *
     * <p>
     * El archivo debe enviarse como {@code multipart/form-data} con
     * el campo {@code file}.
     * </p>
     *
     * @param file archivo CSV con columnas:
     *             {@code Nombre,Apellidos,Correo,Telefono,Rol,Activo,EsCliente}
     * @return resumen de la operación: filas procesadas, insertadas, actualizadas y
     *         errores
     */
    @PostMapping(value = "/import/users", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CsvImportResultDto> importUsers(
            @RequestParam("file") MultipartFile file) throws IOException {

        validateCsvFile(file);
        log.info("[CsvController] Importando usuarios desde CSV: {}",
                LogSanitizer.sanitizeFilename(file.getOriginalFilename()));
        CsvImportResultDto result = csvImportService.importUsers(file);
        return ResponseEntity.ok(result);
    }

    // ── Utilidades ─────────────────────────────────────────────────────────────

    // ── Nuevos endpoints: Columnas, Exportación configurable, Preview, Plantilla
    // ──

    /**
     * Retorna las columnas disponibles para exportación de productos.
     */
    @GetMapping("/export/columns/products")
    public ResponseEntity<List<ColumnMetadataDto>> getProductColumns() {
        return ResponseEntity.ok(csvExportService.getAvailableProductColumns());
    }

    /**
     * Exporta productos con configuración personalizada de columnas, orden y
     * límite.
     */
    @PostMapping(value = "/export/products/download", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportProductsConfigured(@Valid @RequestBody ExportConfigDto config)
            throws IOException {
        log.info("[CsvController] Exportación configurable: columns={}, sort={}:{}, limit={}",
                config.columns().size(), config.sortBy(), config.sortDir(), config.limit());
        byte[] csv = csvExportService.exportProductsWithConfig(config);
        return downloadResponse(csv, "productos_" + today() + ".csv");
    }

    /**
     * Genera una plantilla CSV de ejemplo para importación de productos.
     */
    @GetMapping("/import/template/products")
    public ResponseEntity<byte[]> downloadProductTemplate() throws IOException {
        log.info("[CsvController] Descargando plantilla CSV de productos");
        byte[] csv = csvExportService.generateProductTemplate();
        return downloadResponse(csv, "plantilla_productos.csv");
    }

    /**
     * Valida y genera una previsualización del CSV sin importar datos.
     * El usuario puede revisar las filas, ver errores y decidir qué importar.
     */
    @PostMapping(value = "/import/preview/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> previewProductImport(@RequestParam("file") MultipartFile file) throws IOException {
        // — Validación de seguridad —
        List<String> securityErrors = csvSecurityValidator.validate(file);
        if (!securityErrors.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    java.util.Map.of("errors", securityErrors, "message",
                            "El archivo no pasó la validación de seguridad"));
        }

        log.info("[CsvController] Preview de importación CSV: {}",
                LogSanitizer.sanitizeFilename(file.getOriginalFilename()));
        CsvImportPreviewDto preview = csvImportService.previewProducts(file);
        return ResponseEntity.ok(preview);
    }

    /**
     * Confirma la importación procesando solo las filas seleccionadas por el
     * usuario.
     *
     * @param file         archivo CSV original (se re-parsea)
     * @param selectedRows filas seleccionadas (1-based, JSON array como string)
     * @param rule         regla de colisión: UPDATE o SKIP
     */
    @PostMapping(value = "/import/confirm/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> confirmProductImport(
            @RequestParam("file") MultipartFile file,
            @RequestParam("selectedRows") List<Integer> selectedRows,
            @RequestParam(name = "rule", defaultValue = "UPDATE") CollisionRule rule) throws IOException {

        // — Validación de seguridad —
        List<String> securityErrors = csvSecurityValidator.validate(file);
        if (!securityErrors.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    java.util.Map.of("errors", securityErrors, "message",
                            "El archivo no pasó la validación de seguridad"));
        }

        log.info("[CsvController] Confirmando importación: {} filas seleccionadas, rule={}",
                selectedRows.size(), rule);
        CsvImportResultDto result = csvImportService.importProductsFromPreview(file, selectedRows, rule);
        return ResponseEntity.ok(result);
    }

    // ── Utilidades legacy ────────────────────────────────────────────────────

    private ResponseEntity<byte[]> downloadResponse(byte[] content, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(content);
    }

    private void validateCsvFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("El archivo CSV no puede estar vacio");
        }
        // Defensa DoS: máximo 10 MB
        if (file.getSize() > MAX_CSV_BYTES) {
            throw new IllegalArgumentException(
                    "El archivo excede el limite de 10 MB (" + (file.getSize() / 1024 / 1024) + " MB recibidos)");
        }
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase().endsWith(".csv")) {
            throw new IllegalArgumentException("El archivo debe tener extension .csv");
        }
    }

    private String today() {
        return LocalDate.now().format(DATE_FMT);
    }
}
