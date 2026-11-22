package com.security.service;

import com.security.config.DatabaseBackupProperties;
import com.security.entity.BackupLog;
import com.security.enums.BackupStatus;
import com.security.repository.BackupLogRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Servicio asíncrono de respaldos de base de datos — arquitectura Supabase
 * Storage.
 *
 * <h3>Flujo de 9 pasos</h3>
 * <ol>
 * <li>Crear directorio temporal si no existe.</li>
 * <li>Generar nombre de archivo con marca de tiempo.</li>
 * <li>Insertar {@link BackupLog} con estado {@code PENDING}.</li>
 * <li>Ejecutar {@code pg_dump --format=custom} → archivo temporal.</li>
 * <li>Verificar integridad con {@code pg_restore --list}.</li>
 * <li>Subir el archivo a Supabase Storage (bucket privado).</li>
 * <li>Eliminar el archivo temporal local.</li>
 * <li>Actualizar el registro a {@code COMPLETED} con metadatos.</li>
 * <li>Capturar cualquier excepción no controlada → {@code FAILED}.</li>
 * </ol>
 */
@Service
public class DatabaseBackupService {
    @Value("${app.maintenance.enabled:true}")
    private boolean maintenanceEnabled = true;

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupService.class);
    private static final int TIMEOUT_SECONDS = 300;
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * OWASP: Patrón estricto para validar nombres de tabla antes de pasarlos a
     * pg_dump via ProcessBuilder. Solo permite letras, dígitos y guiones bajos.
     * Previene OS Command Injection (CWE-78) al rechazar cualquier carácter
     * especial (espacios, punto y coma, pipes, guiones, comillas, etc.).
     */
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)?$");

    /**
     * Máximo de tablas permitidas en un respaldo parcial (defensa en profundidad).
     */
    private static final int MAX_PARTIAL_TABLES = 50;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String datasourceUsername;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    private final DatabaseBackupProperties backupProperties;
    private final BackupLogRepository backupLogRepository;
    private final SupabaseStorageService supabaseStorage;

    /**
     * Auto-referencia al proxy de Spring. Necesaria para que las llamadas
     * internas a métodos {@code @Async} pasen por el proxy AOP y sean
     * interceptadas correctamente (evita el self-call anti-pattern).
     */
    @Autowired
    @Lazy
    private DatabaseBackupService self;

    /**
     * Mapa en memoria de bitácoras de backups activos (PENDING).
     *
     * <p>
     * Clave: ID del {@link BackupLog}. Valor: {@link StringBuilder} que acumula
     * las entradas de log en tiempo real mientras el proceso corre.
     *
     * <p>
     * El controller lee de aquí para el endpoint {@code /live-log}.
     * Una vez que el proceso termina (COMPLETED o FAILED) y se persiste
     * el log en la BD, la entrada se elimina del mapa.
     *
     * <p>
     * {@link ConcurrentHashMap} garantiza acceso thread-safe sin bloqueos
     * costosos: el hilo del pool {@code backupTaskExecutor} escribe,
     * el hilo HTTP del controller solo lee con {@code get()}.
     */
    private final ConcurrentHashMap<Long, StringBuilder> activeLogs = new ConcurrentHashMap<>();

    public DatabaseBackupService(DatabaseBackupProperties backupProperties,
            BackupLogRepository backupLogRepository,
            SupabaseStorageService supabaseStorage) {
        this.backupProperties = backupProperties;
        this.backupLogRepository = backupLogRepository;
        this.supabaseStorage = supabaseStorage;
    }

    // ── Recuperación de backups zombie al arrancar ────────────────────────

    /**
     * Detecta backups que quedaron en estado {@code PENDING} tras un reinicio
     * inesperado del servidor y los marca como {@code FAILED}.
     *
     * <p>
     * <strong>Problema que resuelve:</strong> Si el servidor se detiene mientras
     * un backup está en curso (ej. OOM kill, deploy, crash), el registro en
     * {@code backup_logs} queda en PENDING para siempre — un «estado zombie».
     * El frontend no puede distinguirlos de un backup real en progreso.
     * </p>
     *
     * <p>
     * <strong>Solución:</strong> Al arrancar, cualquier registro PENDING no
     * puede estar realmente en ejecución (el proceso {@code @Async} ya no
     * existe), así que se transiciona a FAILED con un mensaje descriptivo.
     * Se ejecuta en una transacción propia para no interferir con la
     * inicialización de otros beans.
     * </p>
     */
    @PostConstruct
    @Transactional
    public void recoverZombiePendingBackups() {
        if (!maintenanceEnabled) return;
        List<BackupLog> zombies = backupLogRepository.findByStatus(BackupStatus.PENDING);

        if (zombies.isEmpty()) {
            log.info("✅ [Backup Recovery] No se encontraron backups en estado PENDING al arrancar.");
            return;
        }

        String timestamp = LocalDateTime.now().format(LOG_TS);
        String errorMsg = "Interrumpido por reinicio inesperado del servidor";
        String logEntry = String.format(
                "[%s] ═══════════════════════════════════════════════════════\n" +
                        "[%s] PROCESO INTERRUMPIDO POR REINICIO DEL SERVIDOR\n" +
                        "[%s] El servidor se detuvo mientras este respaldo estaba en curso.\n" +
                        "[%s] El proceso no puede reanudarse — se requiere un nuevo respaldo.\n" +
                        "[%s] ═══════════════════════════════════════════════════════\n",
                timestamp, timestamp, timestamp, timestamp, timestamp);

        List<Long> ids = zombies.stream().map(BackupLog::getId).toList();

        backupLogRepository.bulkUpdateStatus(ids, BackupStatus.FAILED, errorMsg, logEntry);

        log.warn("⚠️ [Backup Recovery] {} backup(s) en estado PENDING marcados como FAILED tras reinicio: IDs = {}",
                zombies.size(), ids);
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Crea el registro {@link BackupLog} en estado PENDING de forma
     * <em>síncrona</em>
     * y dispara el proceso de backup en el pool {@code backupTaskExecutor}.
     *
     * <p>
     * Devuelve el ID del registro recién creado para que el caller (controller)
     * pueda informar al cliente qué ID consultar en {@code /live-log}.
     *
     * <p>
     * <strong>Separación síncrona / asíncrona:</strong> la creación del registro
     * ocurre en este método (hilo HTTP), garantizando que el ID exista antes de que
     * el controller responda 202. La ejecución real de pg_dump ocurre en
     * {@link #runBackupAsync} (hilo del pool).
     *
     * @param triggeredBy Email del admin o {@code "SYSTEM_CRON"}
     * @return ID del {@link BackupLog} recién creado
     */
    public long triggerManualBackup(String triggeredBy) {
        return triggerBackup(triggeredBy, Collections.emptyList());
    }

    /**
     * Dispara un respaldo parcial: solo las tablas indicadas.
     *
     * <p>
     * <strong>OWASP — Prevención de OS Command Injection (CWE-78):</strong>
     * Cada nombre de tabla se valida contra el patrón estricto
     * {@link #TABLE_NAME_PATTERN} ({@code ^[a-zA-Z0-9_]+$}) ANTES de
     * pasarse a ProcessBuilder. Si cualquier tabla contiene caracteres
     * prohibidos (espacios, {@code ;}, {@code |}, {@code -}, comillas, etc.),
     * se lanza {@link IllegalArgumentException} y se aborta inmediatamente.
     * </p>
     *
     * @param triggeredBy Email del admin o {@code "SYSTEM_AUTOMATION"}
     * @param tables      lista de nombres de tabla a respaldar (vacía = full)
     * @return ID del {@link BackupLog} recién creado
     * @throws IllegalArgumentException si alguna tabla no pasa la validación
     */
    public long triggerBackup(String triggeredBy, List<String> tables) {
        // ── Sanitizar nombres de tabla ANTES de cualquier operación ──────────
        List<String> sanitizedTables = validateAndSanitizeTables(tables);
        // Parsear las coordenadas de la BD de forma síncrona para detectar
        // una mala configuración antes de crear el registro y responder 202.
        DbCoordinates db;
        try {
            db = parseJdbcUrl(datasourceUrl);
        } catch (BackupException e) {
            log.error("❌ No se pudo parsear la URL JDBC: {}", e.getMessage());
            throw new IllegalStateException("Configuración de BD inválida: " + e.getMessage(), e);
        }

        String timestamp = LocalDateTime.now().format(FILE_TS);
        String prefix = sanitizedTables.isEmpty() ? "backup" : "backup_partial";
        String filename = String.format("%s_%s_%s.dump", prefix, db.dbName(), timestamp);

        BackupLog backupLog = new BackupLog(filename, null, triggeredBy);
        backupLogRepository.save(backupLog); // Persist en hilo HTTP → ID disponible de inmediato
        long backupId = backupLog.getId();

        // Inicializar la bitácora y registrar en el mapa antes de lanzar el hilo,
        // así cualquier llamada a /live-log que llegue antes de que el hilo
        // arranque verá el mapa en lugar de ir a la BD.
        StringBuilder logBuilder = new StringBuilder();
        appendLog(logBuilder, "═══════════════════════════════════════════════════════");
        appendLog(logBuilder, "INICIO DEL PROCESO DE RESPALDO");
        appendLog(logBuilder, "ID       : " + backupId);
        appendLog(logBuilder, "Archivo  : " + filename);
        appendLog(logBuilder, "Tipo     : "
                + (sanitizedTables.isEmpty() ? "COMPLETO" : "PARCIAL (" + sanitizedTables.size() + " tablas)"));
        appendLog(logBuilder, "Disparado por: " + triggeredBy);
        if (!sanitizedTables.isEmpty()) {
            appendLog(logBuilder, "Tablas   : " + String.join(", ", sanitizedTables));
        }
        appendLog(logBuilder, "═══════════════════════════════════════════════════════");
        activeLogs.put(backupId, logBuilder);

        // Lanzar el proceso async a través del proxy de Spring (self-injection con
        // @Lazy)
        // para que @Async("backupTaskExecutor") sea interceptado correctamente.
        self.runBackupAsync(backupLog, db, filename, logBuilder, sanitizedTables);
        return backupId;
    }

    /**
     * Ejecución asíncrona del proceso de respaldo.
     * Solo debe llamarse desde {@link #triggerBackup} —nunca directamente.
     *
     * @param tables lista de tablas para respaldo parcial (vacía = full backup)
     */
    @Async("backupTaskExecutor")
    public void runBackupAsync(BackupLog backupLog, DbCoordinates db,
            String filename, StringBuilder logBuilder, List<String> tables) {

        Path tempDir = Paths.get(backupProperties.getTempDir());
        Path tempFile = tempDir.resolve(filename);
        appendLog(logBuilder, "[PASO 2] Directorio temporal: " + tempDir.toAbsolutePath());
        appendLog(logBuilder, "[PASO 3] Registro de auditoría — ID: " + backupLog.getId() + " | Estado: PENDING");

        long startMs = System.currentTimeMillis();

        try {
            // ── PASO 1b: crear directorio temporal ───────────────────────────
            Files.createDirectories(tempDir);
            appendLog(logBuilder, "[PASO 3b] Directorio temporal listo: " + tempDir.toAbsolutePath());

            // ── PASO 4: ejecutar pg_dump ──────────────────────────────────────
            List<String> dumpCmd = buildDumpCommand(db, tempFile, tables);
            appendLog(logBuilder, "───────────────────────────────────────────────────────");
            appendLog(logBuilder, "[PASO 4] Iniciando pg_dump...");
            appendLog(logBuilder, "[PASO 4] Host destino: " + db.host() + ":" + db.port() + "/" + db.dbName());
            if (!tables.isEmpty()) {
                appendLog(logBuilder, "[PASO 4] Modo: PARCIAL — " + tables.size() + " tablas seleccionadas");
                appendLog(logBuilder, "[PASO 4] Tablas: " + String.join(", ", tables));
            } else {
                appendLog(logBuilder, "[PASO 4] Modo: COMPLETO — todas las tablas");
            }
            appendLog(logBuilder, "[PASO 4] Timeout máximo: " + TIMEOUT_SECONDS + "s");
            appendLog(logBuilder, "───────────────────────────────────────────────────────");

            runProcess(dumpCmd, "pg_dump", logBuilder);

            appendLog(logBuilder, "[PASO 4] ✔ pg_dump finalizó correctamente (exit code 0)");

            // ── PASO 5: verificar integridad ──────────────────────────────────
            appendLog(logBuilder, "[PASO 5] Verificando integridad del archivo generado...");
            if (!Files.exists(tempFile) || Files.size(tempFile) < 1024) {
                throw new BackupException(
                        "El archivo generado por pg_dump no existe o está vacío: " + tempFile.getFileName());
            }
            long fileSize = Files.size(tempFile);
            runProcess(List.of(backupProperties.getPgRestorePath(), "--list", tempFile.toAbsolutePath().toString()),
                    "pg_restore --list", logBuilder);
            appendLog(logBuilder,
                    "[PASO 5] ✔ Archivo verificado: " + formatBytes(fileSize) + " (" + fileSize + " bytes)");

            // ── PASO 6: subir a Supabase Storage ─────────────────────────────
            appendLog(logBuilder, "───────────────────────────────────────────────────────");
            appendLog(logBuilder, "[PASO 6] Subiendo a Supabase Storage...");
            String objectPath;
            try {
                objectPath = supabaseStorage.uploadBackup(tempFile, filename);
                appendLog(logBuilder, "[PASO 6] ✔ Upload completado — objeto: " + objectPath);
            } catch (RuntimeException ex) {
                appendLog(logBuilder, "[PASO 6] ✘ Error al subir a Supabase: " + ex.getMessage());
                fail(backupLog, startMs, "Error al subir a Supabase: " + ex.getMessage(), logBuilder);
                deleteQuietly(tempFile);
                appendLog(logBuilder, "[LIMPIEZA] Archivo temporal eliminado tras error de upload.");
                return;
            }

            // ── PASO 7: eliminar archivo temporal ────────────────────────────
            deleteQuietly(tempFile);
            appendLog(logBuilder, "[PASO 7] ✔ Archivo temporal eliminado del servidor.");

            // ── PASO 8: actualizar registro a COMPLETED ───────────────────────
            long elapsed = System.currentTimeMillis() - startMs;
            backupLog.setStatus(BackupStatus.COMPLETED);
            backupLog.setFilePath(objectPath);
            backupLog.setFileSizeBytes(fileSize);
            backupLog.setExecutionTimeMs(elapsed);

            appendLog(logBuilder, "═══════════════════════════════════════════════════════");
            appendLog(logBuilder, "RESPALDO COMPLETADO EXITOSAMENTE");
            appendLog(logBuilder, "Archivo : " + filename);
            appendLog(logBuilder, "Tamaño  : " + formatBytes(fileSize));
            appendLog(logBuilder, "Duración: " + formatElapsed(elapsed));
            appendLog(logBuilder, "Destino : Supabase Storage → " + objectPath);
            appendLog(logBuilder, "═══════════════════════════════════════════════════════");

            backupLog.setExecutionLog(logBuilder.toString());
            backupLogRepository.save(backupLog);

            log.info("✅ Backup completado y subido: {} ({} bytes, {}ms)", filename, fileSize, elapsed);

        } catch (BackupException | IOException e) {
            appendLog(logBuilder, "═══════════════════════════════════════════════════════");
            appendLog(logBuilder, "✘ PROCESO FALLIDO: " + e.getMessage());
            appendLog(logBuilder, "═══════════════════════════════════════════════════════");
            fail(backupLog, startMs, e.getMessage(), logBuilder);
            deleteQuietly(tempFile);
        } catch (Exception e) {
            appendLog(logBuilder, "═══════════════════════════════════════════════════════");
            appendLog(logBuilder, "✘ ERROR INESPERADO: " + e.getMessage());
            appendLog(logBuilder, "Tipo   : " + e.getClass().getName());
            appendLog(logBuilder, "═══════════════════════════════════════════════════════");
            fail(backupLog, startMs, "Error inesperado: " + e.getMessage(), logBuilder);
            deleteQuietly(tempFile);
            log.error("❌ Error inesperado en backup", e);
        } finally {
            // Remover del mapa de logs activos: el proceso terminó (éxito o fallo)
            // y el log ya fue persistido en la BD por complete() o fail().
            activeLogs.remove(backupLog.getId());
        }
    }

    /**
     * Retorna los últimos 50 registros de backup no eliminados, más recientes
     * primero.
     */
    public List<BackupLog> getHistory() {
        return backupLogRepository.findTop50ByIsDeletedFalseOrderByCreatedAtDesc();
    }

    /**
     * Retorna el snapshot actual del log en memoria de un backup activo (PENDING).
     *
     * <p>
     * Si el ID no está en el mapa (proceso ya terminó o ID inválido),
     * retorna {@code null} y el controller debe caer a la BD.
     *
     * @param backupId ID del registro {@link BackupLog}
     * @return snapshot del log acumulado hasta ahora, o {@code null}
     */
    public String getActiveLogSnapshot(Long backupId) {
        StringBuilder sb = activeLogs.get(backupId);
        return (sb != null) ? sb.toString() : null;
    }

    // ── Utilidades internas ───────────────────────────────────────────────────

    private void fail(BackupLog bl, long startMs, String message, StringBuilder logBuilder) {
        String msg = (message != null && message.length() > 2000) ? message.substring(0, 2000) : message;
        bl.setStatus(BackupStatus.FAILED);
        bl.setErrorMessage(msg);
        bl.setExecutionTimeMs(System.currentTimeMillis() - startMs);
        bl.setExecutionLog(logBuilder.toString());
        backupLogRepository.save(bl);
        log.error("❌ Backup fallido: {}", msg);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    // ── Construcción de comandos ──────────────────────────────────────────────

    private List<String> buildDumpCommand(DbCoordinates db, Path outputFile, List<String> tables)
            throws BackupException {
        String pgDumpPath = backupProperties.getPgDumpPath();
        File pgDumpExec = new File(pgDumpPath);
        if (pgDumpExec.isAbsolute() && !pgDumpExec.exists()) {
            throw new BackupException(
                    "pg_dump no encontrado en: " + pgDumpPath +
                            ". Verifica 'app.backup.pg-dump-path' en application-local.yml");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(pgDumpPath);
        cmd.add("--host=" + db.host());
        cmd.add("--port=" + db.port());
        cmd.add("--username=" + datasourceUsername);
        cmd.add("--dbname=" + db.dbName());
        cmd.add("--format=custom");
        cmd.add("--compress=6");
        cmd.add("--encoding=UTF8");
        cmd.add("--no-password");

        // ── Respaldo parcial: añadir -t por cada tabla validada ──────────
        // Los nombres ya fueron validados por validateAndSanitizeTables()
        // contra TABLE_NAME_PATTERN (^[a-zA-Z0-9_]+$) — seguros para CLI.
        if (tables != null && !tables.isEmpty()) {
            for (String table : tables) {
                cmd.add("-t");
                cmd.add(table);
            }
            log.info("[Backup] Modo parcial: {} tablas → {}", tables.size(), tables);
        }

        cmd.add("--file=" + outputFile.toAbsolutePath());
        return cmd;
    }

    /**
     * Valida y sanitiza una lista de nombres de tabla para uso seguro en
     * ProcessBuilder (prevención de OS Command Injection — CWE-78).
     *
     * <p>
     * <strong>Reglas de seguridad (OWASP):</strong>
     * </p>
     * <ol>
     * <li>Cada nombre debe coincidir con {@link #TABLE_NAME_PATTERN}
     * ({@code ^[a-zA-Z0-9_]+$}). Rechaza espacios, {@code ;}, {@code |},
     * {@code -}, comillas, backticks, etc.</li>
     * <li>Lista limitada a {@link #MAX_PARTIAL_TABLES} elementos.</li>
     * <li>Se eliminan duplicados y strings vacíos.</li>
     * </ol>
     *
     * @param tables lista cruda de nombres de tabla del frontend
     * @return lista limpia y validada (puede ser vacía)
     * @throws IllegalArgumentException si algún nombre tiene formato inválido
     */
    private List<String> validateAndSanitizeTables(List<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return Collections.emptyList();
        }
        if (tables.size() > MAX_PARTIAL_TABLES) {
            throw new IllegalArgumentException(
                    "Demasiadas tablas solicitadas (" + tables.size() +
                            "). Máximo permitido: " + MAX_PARTIAL_TABLES);
        }
        List<String> sanitized = new ArrayList<>();
        for (String raw : tables) {
            if (raw == null)
                continue;
            String trimmed = raw.trim();
            if (trimmed.isEmpty())
                continue;
            if (!TABLE_NAME_PATTERN.matcher(trimmed).matches()) {
                log.error("⛔ [SECURITY] Invalid table name detected: '{}'",
                        trimmed.replaceAll("[^a-zA-Z0-9_]", "?"));
                throw new IllegalArgumentException(
                        "Invalid table name detected: nombre de tabla contiene caracteres no permitidos.");
            }
            if (!sanitized.contains(trimmed)) {
                sanitized.add(trimmed);
            }
        }
        return sanitized;
    }

    /**
     * Ejecuta un proceso externo, captura stdout y stderr de forma asíncrona
     * y los vuelca en el {@code logBuilder}. Lanza {@link BackupException} si
     * el proceso termina con exit code distinto de 0 o supera el timeout.
     */
    private void runProcess(List<String> command, String label, StringBuilder logBuilder)
            throws BackupException, IOException {

        ProcessBuilder pb = configureProcess(command, label, datasourceUrl, datasourcePassword);
        pb.redirectErrorStream(false);
        // Redirigir stdin al NUL del sistema para que el proceso hijo no quede
        // esperando entrada por consola (comportamiento común en Windows con pg_dump).
        pb.redirectInput(ProcessBuilder.Redirect.from(new File(
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "NUL"
                        : "/dev/null")));

        Process process = null;
        try {
            process = pb.start();
            final Process fp = process;
            final StringBuilder stdoutCapture = new StringBuilder();
            final StringBuilder stderrCapture = new StringBuilder();

            // Lector de stdout en hilo daemon
            Thread stdoutReader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(fp.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        stdoutCapture.append(line).append("\n");
                    }
                } catch (IOException ignored) {
                }
            });
            stdoutReader.setDaemon(true);
            stdoutReader.setName("backup-stdout-reader");

            // Lector de stderr en hilo daemon
            Thread stderrReader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(fp.getErrorStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        stderrCapture.append(line).append("\n");
                    }
                } catch (IOException ignored) {
                }
            });
            stderrReader.setDaemon(true);
            stderrReader.setName("backup-stderr-reader");

            stdoutReader.start();
            stderrReader.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                appendLog(logBuilder,
                        "[" + label + "] ✘ El proceso superó el timeout de " + TIMEOUT_SECONDS + "s y fue terminado.");
                throw new BackupException(label + " superó el tiempo límite de " + TIMEOUT_SECONDS + "s.");
            }

            stdoutReader.join(2000);
            stderrReader.join(2000);

            // Volcar salidas al log
            String stdoutStr = stdoutCapture.toString().trim();
            String stderrStr = stderrCapture.toString().trim();

            if (!stdoutStr.isEmpty()) {
                appendLog(logBuilder, "[" + label + "] --- STDOUT ---");
                for (String line : stdoutStr.split("\n")) {
                    appendLog(logBuilder, "[" + label + "] " + line);
                }
            }
            if (!stderrStr.isEmpty()) {
                appendLog(logBuilder, "[" + label + "] --- STDERR ---");
                for (String line : stderrStr.split("\n")) {
                    appendLog(logBuilder, "[" + label + "] " + line);
                }
            }

            int exit = process.exitValue();
            if (exit != 0) {
                String errMsg = stderrStr.isEmpty() ? "(sin salida de error)" : stderrStr;
                appendLog(logBuilder, "[" + label + "] ✘ Exit code: " + exit);
                log.error("❌ {} falló (exit {}): {}", label, exit, errMsg);
                throw new BackupException(label + " terminó con error (código " + exit + "): " + errMsg);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null)
                process.destroyForcibly();
            appendLog(logBuilder, "[" + label + "] ✘ Proceso interrumpido.");
            throw new BackupException("El proceso fue interrumpido: " + label, e);
        }
    }

    static ProcessBuilder configureProcess(List<String> command, String label, String datasourceUrl,
            String datasourcePassword) throws BackupException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if ("pg_dump".equals(label)) {
            pb.environment().put("PGPASSWORD", datasourcePassword);
            var jdbcOptions = org.postgresql.Driver.parseURL(datasourceUrl, null);
            if (jdbcOptions == null) {
                throw new BackupException("No se pudo leer la configuración de conexión para pg_dump");
            }
            String sslMode = jdbcOptions.getProperty("sslmode");
            if (sslMode == null && Boolean.parseBoolean(jdbcOptions.getProperty("ssl"))) {
                sslMode = "verify-full";
            }
            if (sslMode != null) {
                pb.environment().put("PGSSLMODE", sslMode);
            }
            String sslRootCert = jdbcOptions.getProperty("sslrootcert");
            if (sslRootCert != null) {
                pb.environment().put("PGSSLROOTCERT", sslRootCert);
            }
        } else {
            pb.environment().remove("PGPASSWORD");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        return pb;
    }

    // ── URL JDBC parsing ──────────────────────────────────────────────────────

    static DbCoordinates parseJdbcUrl(String jdbcUrl) throws BackupException {
        try {
            String raw = jdbcUrl.replace("jdbc:", "");
            int qIdx = raw.indexOf('?');
            String clean = (qIdx >= 0) ? raw.substring(0, qIdx) : raw;
            URI uri = new URI(clean);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String db = uri.getPath().replaceFirst("^/", "");
            if (host == null || host.isBlank() || db == null || db.isBlank()) {
                throw new BackupException("No se pudo extraer host o BD de la URL JDBC");
            }
            return new DbCoordinates(host, port, db);
        } catch (URISyntaxException e) {
            throw new BackupException("URL JDBC con formato inválido");
        }
    }

    // ── Helpers de bitácora ───────────────────────────────────────────────────

    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Agrega una línea con timestamp al StringBuilder de bitácora.
     * Formato: {@code [YYYY-MM-DD HH:mm:ss] mensaje}
     */
    private void appendLog(StringBuilder sb, String message) {
        sb.append('[')
                .append(LocalDateTime.now().format(LOG_TS))
                .append("] ")
                .append(message)
                .append('\n');
    }

    /** Convierte bytes a representación legible (B / KB / MB / GB). */
    private String formatBytes(long bytes) {
        if (bytes >= 1_073_741_824L)
            return String.format("%.2f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)
            return String.format("%.2f MB", bytes / 1_048_576.0);
        if (bytes >= 1_024L)
            return String.format("%.1f KB", bytes / 1_024.0);
        return bytes + " B";
    }

    /** Convierte milisegundos a representación legible (ms / s / min). */
    private String formatElapsed(long ms) {
        if (ms >= 60_000)
            return String.format("%.1f min", ms / 60_000.0);
        if (ms >= 1_000)
            return String.format("%.1f s", ms / 1_000.0);
        return ms + " ms";
    }

    // ── Tipos internos ────────────────────────────────────────────────────────

    /** Coordenadas de conexión extraídas de la URL JDBC. */
    public record DbCoordinates(String host, int port, String dbName) {
    }

    public static class BackupException extends Exception {
        public BackupException(String message) {
            super(message);
        }

        public BackupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
