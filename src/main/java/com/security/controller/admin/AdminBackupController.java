package com.security.controller.admin;

import com.security.entity.BackupLog;
import com.security.enums.BackupStatus;
import com.security.repository.BackupLogRepository;
import com.security.service.DatabaseBackupService;
import com.security.service.SupabaseStorageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Controller de backup de base de datos — arquitectura Supabase Storage.
 *
 * <ul>
 * <li>POST /api/admin/backups/trigger → 202 Accepted (backup asíncrono)</li>
 * <li>GET /api/admin/backups?page=0&amp;size=20 → historial paginado</li>
 * <li>GET /api/admin/backups/{id}/download-url → URL firmada de descarga (1
 * h)</li>
 * </ul>
 *
 * <p>
 * Seguridad: solo {@code ROLE_SUPER_ADMIN} puede acceder.
 * </p>
 */
@RestController
@RequestMapping("/api/admin/backups")
@PreAuthorize("hasAuthority('DATABASE_BACKUP')")
public class AdminBackupController {

        private static final int SIGNED_URL_EXPIRY_SECONDS = 3600; // 1 hora

        private final DatabaseBackupService backupService;
        private final SupabaseStorageService supabaseStorage;
        private final BackupLogRepository backupLogRepository;
        private final JdbcTemplate jdbc;

        public AdminBackupController(DatabaseBackupService backupService,
                        SupabaseStorageService supabaseStorage,
                        BackupLogRepository backupLogRepository,
                        JdbcTemplate jdbc) {
                this.backupService = backupService;
                this.supabaseStorage = supabaseStorage;
                this.backupLogRepository = backupLogRepository;
                this.jdbc = jdbc;
        }

        @GetMapping("/tables")
        public List<BackupTable> listDatabaseTables() {
                return jdbc.query("""
                                SELECT t.table_schema || '.' || t.table_name AS name,
                                       COALESCE(s.n_live_tup, 0) AS row_estimate
                                FROM information_schema.tables t
                                LEFT JOIN pg_stat_user_tables s
                                    ON s.schemaname = t.table_schema AND s.relname = t.table_name
                                WHERE t.table_schema IN ('auth', 'security', 'catalog', 'sales', 'customer', 'ops')
                                  AND t.table_type = 'BASE TABLE'
                                ORDER BY row_estimate DESC, name
                                """, (rs, row) -> new BackupTable(rs.getString("name"), rs.getLong("row_estimate")));
        }

        public record BackupTable(String name, long rowEstimate) { }

        // ── POST /trigger ─────────────────────────────────────────────────────────

        /**
         * Dispara el backup en segundo plano y retorna 202 Accepted inmediatamente.
         * La respuesta incluye el {@code backupId} del registro recién creado para
         * que el cliente pueda hacer polling a {@code /{id}/live-log}.
         *
         * <p>
         * Acepta un body JSON opcional con:
         * </p>
         * <ul>
         * <li>{@code backup_type}: "FULL" (por defecto) o "PARTIAL"</li>
         * <li>{@code tables}: lista de nombres de tabla (solo si PARTIAL)</li>
         * </ul>
         *
         * <p>
         * <strong>Seguridad:</strong> los nombres de tabla se validan con regex
         * estricto {@code ^[a-zA-Z0-9_]+$} en {@link DatabaseBackupService} antes
         * de pasarse a pg_dump. Inyección de comandos imposible.
         * </p>
         */
        @PostMapping("/trigger")
        public ResponseEntity<Map<String, Object>> trigger(
                        Principal principal,
                        @RequestBody(required = false) Map<String, Object> body) {
                String user = (principal != null) ? principal.getName() : "SYSTEM";

                // Extraer parámetros del body (si vienen)
                List<String> tables = Collections.emptyList();
                String backupType = "FULL";
                if (body != null) {
                        Object typeObj = body.get("backup_type");
                        if (typeObj instanceof String t) {
                                backupType = t.toUpperCase();
                        }
                        if ("PARTIAL".equals(backupType)) {
                                Object tablesObj = body.get("tables");
                                if (tablesObj instanceof List<?> rawList) {
                                        tables = rawList.stream()
                                                        .filter(String.class::isInstance)
                                                        .map(String.class::cast)
                                                        .toList();
                                }
                        }
                }

                long backupId = backupService.triggerBackup(user, tables);
                return ResponseEntity.accepted().body(Map.of(
                                "message", tables.isEmpty()
                                                ? "Respaldo completo iniciado en segundo plano."
                                                : "Respaldo parcial (" + tables.size()
                                                                + " tablas) iniciado en segundo plano.",
                                "status", BackupStatus.PENDING.name(),
                                "backupId", backupId,
                                "backupType", backupType,
                                "triggeredBy", user,
                                "timestamp", LocalDateTime.now().toString()));
        }

        // ── GET /{id}/live-log ────────────────────────────────────────────────────

        /**
         * Retorna el log de ejecución de un backup, en tiempo real si sigue activo.
         *
         * <ul>
         * <li>Si el backup está <strong>PENDING</strong> (proceso corriendo),
         * lee el snapshot del mapa en memoria y devuelve {@code status=PENDING}.</li>
         * <li>Si el backup ya <strong>terminó</strong> (COMPLETED / FAILED),
         * lee el campo {@code execution_log} persistido en la BD.</li>
         * </ul>
         *
         * @param id ID del registro de backup
         * @return JSON {@code {log, status}}
         */
        @GetMapping("/{id}/live-log")
        public ResponseEntity<Map<String, String>> getLiveLog(@PathVariable Long id) {
                // 1. Intentar primero el mapa en memoria (proceso aún PENDING)
                String liveSnapshot = backupService.getActiveLogSnapshot(id);
                if (liveSnapshot != null) {
                        return ResponseEntity.ok(Map.of(
                                        "log", liveSnapshot,
                                        "status", BackupStatus.PENDING.name()));
                }

                // 2. Proceso terminado → caer a la BD
                BackupLog backup = backupLogRepository.findById(id)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Backup con id=" + id + " no encontrado."));

                if (backup.isDeleted()) {
                        throw new ResponseStatusException(HttpStatus.GONE,
                                        "Este backup ha sido eliminado y ya no está disponible.");
                }

                String storedLog = (backup.getExecutionLog() != null && !backup.getExecutionLog().isBlank())
                                ? backup.getExecutionLog()
                                : "— Sin bitácora disponible para este registro.";

                return ResponseEntity.ok(Map.of(
                                "log", storedLog,
                                "status", backup.getStatus().name()));
        }

        // ── GET / (paginado) ──────────────────────────────────────────────────────

        /**
         * Retorna el historial de backups paginado, más recientes primero.
         *
         * @param page número de página (0-based, por defecto 0)
         * @param size registros por página (por defecto 20)
         */
        @GetMapping
        public ResponseEntity<Page<BackupLog>> getHistory(
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size) {
                Pageable pageable = PageRequest.of(page, size);
                Page<BackupLog> result = backupLogRepository.findByIsDeletedFalseOrderByCreatedAtDesc(pageable);
                return ResponseEntity.ok(result);
        }

        // ── GET /{id}/download-url ────────────────────────────────────────────────

        /**
         * Genera una URL firmada de tiempo limitado para descargar el backup indicado.
         *
         * <p>
         * Solo funciona si el backup tiene estado {@code COMPLETED} y tiene
         * una ruta de objeto válida en Supabase Storage.
         * </p>
         *
         * @param id ID del registro de backup en {@code backup_logs}
         * @return JSON con {@code signedUrl} (válido por 1 hora)
         * @throws ResponseStatusException 404 si no existe, 409 si no está COMPLETED
         */
        @GetMapping("/{id}/download-url")
        public ResponseEntity<Map<String, String>> getDownloadUrl(@PathVariable Long id) {
                BackupLog backup = backupLogRepository.findById(id)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Backup con id=" + id + " no encontrado."));

                if (backup.isDeleted()) {
                        throw new ResponseStatusException(HttpStatus.GONE,
                                        "Este backup ha sido eliminado y ya no está disponible.");
                }

                if (backup.getStatus() != BackupStatus.COMPLETED) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        "El backup aún no está disponible (estado: " + backup.getStatus() + ").");
                }

                if (backup.getFilePath() == null || backup.getFilePath().isBlank()) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                        "El backup no tiene ruta de archivo en Supabase Storage.");
                }

                String signedUrl = supabaseStorage.generateSignedUrl(backup.getFilePath(), SIGNED_URL_EXPIRY_SECONDS); // Los
                                                                                                                       // 3600
                return ResponseEntity.ok(Map.of(
                                "signedUrl", signedUrl,
                                "filename", backup.getFilename(),
                                "expiresIn", SIGNED_URL_EXPIRY_SECONDS + "s"));
        }
}
