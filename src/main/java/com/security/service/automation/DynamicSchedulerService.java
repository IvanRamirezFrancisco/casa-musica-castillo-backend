package com.security.service.automation;

import com.security.entity.AutomationExecutionLog;
import com.security.entity.SystemAutomation;
import com.security.entity.User;
import com.security.repository.AutomationExecutionLogRepository;
import com.security.repository.SystemAutomationRepository;
import com.security.repository.UserRepository;
import com.security.service.EmailService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

/**
 * Motor central de automatizaciones dinámicas.
 *
 * <h2>Responsabilidades</h2>
 * <ol>
 * <li>Al arrancar la app, lee los registros con {@code is_enabled = true}
 * y los programa en el {@link ThreadPoolTaskScheduler}.</li>
 * <li>Mantiene un {@code Map<String, ScheduledFuture<?>>} en memoria
 * para cerrar las tareas al detener la aplicación.</li>
 * <li>Registra telemetría (duración, estado, errores) en la BD
 * después de cada ejecución.</li>
 * </ol>
 *
 * <h2>Seguridad</h2>
 * <ul>
 * <li>Los parámetros JSONB solo se leen como valores de configuración,
 * nunca se evalúan como código.</li>
 * <li>Todas las escrituras a BD usan Prepared Statements vía Spring Data
 * JPA.</li>
 * </ul>
 */
@Service
public class DynamicSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(DynamicSchedulerService.class);

    private final SystemAutomationRepository automationRepo;
    private final AutomationExecutionLogRepository execLogRepo;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final JobRunnableFactory jobFactory;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final boolean backupDatabaseEnabled;
    private final boolean inventoryAuditEnabled;

    /**
     * Mapa en memoria: jobName → ScheduledFuture.
     * Permite cancelar/reprogramar tareas sin reiniciar el servidor.
     */
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public DynamicSchedulerService(
            SystemAutomationRepository automationRepo,
            AutomationExecutionLogRepository execLogRepo,
            @Qualifier("automationTaskScheduler") ThreadPoolTaskScheduler taskScheduler,
            JobRunnableFactory jobFactory,
            EmailService emailService,
            UserRepository userRepository,
            @Value("${app.jobs.backup-database.enabled:${APP_JOBS_BACKUP_DATABASE_ENABLED:true}}") boolean backupDatabaseEnabled,
            @Value("${app.jobs.inventory-audit.enabled:${APP_JOBS_INVENTORY_AUDIT_ENABLED:true}}") boolean inventoryAuditEnabled) {
        this.automationRepo = automationRepo;
        this.execLogRepo = execLogRepo;
        this.taskScheduler = taskScheduler;
        this.jobFactory = jobFactory;
        this.emailService = emailService;
        this.userRepository = userRepository;
        this.backupDatabaseEnabled = backupDatabaseEnabled;
        this.inventoryAuditEnabled = inventoryAuditEnabled;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ARRANQUE: cargar y programar todos los jobs habilitados
    // ═══════════════════════════════════════════════════════════════════════

    @PostConstruct
    public void onStartup() {
        log.info("⚙️ [DynamicScheduler] Iniciando motor de automatizaciones...");
        List<SystemAutomation> enabled = automationRepo.findByEnabledTrue();
        log.info("  ↳ {} automatización(es) habilitada(s) encontrada(s).", enabled.size());

        for (SystemAutomation auto : enabled) {
            if ("DB_MAINTENANCE_JOB".equals(auto.getJobName())) {
                continue;
            }
            if (!isLocallyEnabled(auto.getJobName())) {
                log.info("  ↳ {} omitido por configuración de esta instancia.", auto.getJobName());
                continue;
            }
            try {
                scheduleJob(auto);
                log.info("  ✅ Programado: {} (cron: {}, tz: {})",
                        auto.getJobName(), auto.getCronExpression(), auto.getTimezone());
            } catch (Exception e) {
                log.error("  ❌ Error programando {}: {}", auto.getJobName(), e.getMessage());
            }
        }
        log.info("⚙️ [DynamicScheduler] Motor inicializado correctamente.");
    }

    private boolean isLocallyEnabled(String jobName) {
        return switch (jobName) {
            case "BACKUP_DATABASE_JOB" -> backupDatabaseEnabled;
            case "INVENTORY_AUDIT_JOB" -> inventoryAuditEnabled;
            default -> true;
        };
    }

    @PreDestroy
    public void onShutdown() {
        log.info("⚙️ [DynamicScheduler] Apagando motor — cancelando {} tarea(s)...", scheduledTasks.size());
        scheduledTasks.forEach((name, future) -> future.cancel(false));
        scheduledTasks.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTERNOS: scheduling, ejecución, telemetría
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Programa un job en el {@link ThreadPoolTaskScheduler} usando su cron y
     * timezone.
     */
    private void scheduleJob(SystemAutomation auto) {
        // Cancelar versión anterior si existe
        cancelJob(auto.getJobName());

        CronTrigger trigger = new CronTrigger(
                auto.getCronExpression(),
                TimeZone.getTimeZone(ZoneId.of(auto.getTimezone())));

        Runnable wrapped = () -> executeWithTelemetry(auto, "SCHEDULER");
        ScheduledFuture<?> future = taskScheduler.schedule(wrapped, trigger);
        scheduledTasks.put(auto.getJobName(), future);
    }

    /**
     * Cancela un job previamente programado.
     */
    private void cancelJob(String jobName) {
        ScheduledFuture<?> existing = scheduledTasks.remove(jobName);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    /**
     * Wrapper que ejecuta el Runnable del job y registra telemetría
     * en AMBAS tablas: system_automations (resumen) y automation_execution_logs
     * (historial).
     *
     * @param autoSnapshot snapshot de la automatización al momento de programar
     * @param triggeredBy  "SCHEDULER" para cron automático, o email del usuario
     *                     para manual
     */
    private void executeWithTelemetry(SystemAutomation autoSnapshot, String triggeredBy) {
        // Recargar desde BD para tener datos frescos
        SystemAutomation auto = automationRepo.findByJobName(autoSnapshot.getJobName())
                .orElse(autoSnapshot);

        // ── Paso 1: Marcar IN_PROGRESS en ambas tablas ──────────────────────
        auto.setLastExecution(LocalDateTime.now());
        auto.setLastStatus("IN_PROGRESS");
        auto.setErrorMessage(null);
        automationRepo.save(auto);

        AutomationExecutionLog execLog = AutomationExecutionLog.start(auto, triggeredBy);
        execLogRepo.save(execLog);

        long start = System.currentTimeMillis();
        try {
            Callable<String> job = jobFactory.resolve(auto);
            String summary = job.call();
            long duration = System.currentTimeMillis() - start;

            // ── Paso 2a: Marcar SUCCESS en ambas tablas ─────────────────────
            auto.setLastDurationMs(duration);
            auto.setLastStatus("SUCCESS");
            auto.setErrorMessage(null);
            auto.setNextExecution(calculateNextExecution(auto.getCronExpression(), auto.getTimezone()));
            automationRepo.save(auto);

            // Usar el resumen devuelto por el job; fallback al genérico
            if (summary == null || summary.isBlank()) {
                summary = auto.getJobName() + " completado en " + duration + " ms";
            }
            execLog.markSuccess(duration, summary);
            execLogRepo.save(execLog);

            log.info("✅ [DynamicScheduler] {} completado en {} ms (log #{})",
                    auto.getJobName(), duration, execLog.getId());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;

            // ── Paso 2b: Marcar FAILED en ambas tablas ──────────────────────
            auto.setLastDurationMs(duration);
            auto.setLastStatus("FAILED");
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.length() > 500) {
                errorMsg = errorMsg.substring(0, 500) + "...";
            }
            auto.setErrorMessage(errorMsg);
            auto.setNextExecution(calculateNextExecution(auto.getCronExpression(), auto.getTimezone()));
            automationRepo.save(auto);

            execLog.markFailed(duration, e.getMessage());
            execLogRepo.save(execLog);

            log.error("❌ [DynamicScheduler] {} FALLÓ tras {} ms (log #{}): {}",
                    auto.getJobName(), duration, execLog.getId(), e.getMessage());

            // ── Paso 3: Enviar alerta por correo si alert_on_failure está activo ──
            sendFailureAlertIfConfigured(auto, e.getMessage(), duration);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ALERTA DE FALLO POR EMAIL
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Si el parámetro {@code alert_on_failure} es true y hay correos configurados
     * en {@code failure_notify_emails}, envía un correo HTML con los detalles del
     * fallo.
     * Los emails se validan contra empleados activos del sistema.
     */
    private void sendFailureAlertIfConfigured(SystemAutomation auto, String errorMessage, long durationMs) {
        try {
            Map<String, Object> params = auto.getParameters();
            if (params == null)
                return;

            Object alertFlag = params.get("alert_on_failure");
            boolean alertEnabled = Boolean.TRUE.equals(alertFlag);
            if (!alertEnabled)
                return;

            // Extraer lista de emails de fallo
            List<String> candidates = extractFailureEmails(params);
            if (candidates.isEmpty()) {
                log.info("  ↳ alert_on_failure activo pero sin correos de notificación configurados.");
                return;
            }

            // Validar contra empleados activos
            Set<String> activeStaffEmails = userRepository.findActiveStaffMembers().stream()
                    .map(User::getEmail)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());

            List<String> validEmails = candidates.stream()
                    .map(String::toLowerCase)
                    .map(String::trim)
                    .filter(e -> !e.isEmpty())
                    .filter(activeStaffEmails::contains)
                    .distinct()
                    .toList();

            if (validEmails.isEmpty()) {
                log.warn("  ↳ Ninguno de los correos de alerta de fallo pertenece a empleados activos.");
                return;
            }

            // Construir y enviar email
            String subject = "❌ Alerta: Fallo en automatización — " + (auto.getDisplayName() != null
                    ? auto.getDisplayName()
                    : auto.getJobName());
            String html = buildFailureAlertHtml(auto, errorMessage, durationMs);

            int sent = 0;
            for (String email : validEmails) {
                try {
                    emailService.sendHtmlEmail(email, subject, html);
                    sent++;
                    log.info("  ↳ Alerta de fallo enviada a: {}***", email.substring(0, Math.min(2, email.length())));
                } catch (Exception ex) {
                    log.warn("  ↳ Error enviando alerta de fallo a {}: {}", email, ex.getMessage());
                }
            }
            log.info("  ↳ Alertas de fallo enviadas: {}/{}", sent, validEmails.size());

        } catch (Exception ex) {
            // Nunca dejar que el envío de alertas rompa el flujo principal
            log.error("  ↳ Error inesperado al enviar alerta de fallo: {}", ex.getMessage());
        }
    }

    /**
     * Extrae los emails de failure_notify_emails del parámetro (puede ser List o
     * String CSV).
     */
    private List<String> extractFailureEmails(Map<String, Object> params) {
        Object raw = params.get("failure_notify_emails");
        if (raw == null)
            return Collections.emptyList();

        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(e -> !e.isEmpty())
                    .toList();
        }
        if (raw instanceof String str && !str.isBlank()) {
            return Arrays.stream(str.split(","))
                    .map(String::trim)
                    .filter(e -> !e.isEmpty())
                    .toList();
        }
        return Collections.emptyList();
    }

    /**
     * Construye el HTML profesional para la alerta de fallo de automatización.
     */
    private String buildFailureAlertHtml(SystemAutomation auto, String errorMessage, long durationMs) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        String jobDisplay = auto.getDisplayName() != null ? auto.getDisplayName() : auto.getJobName();
        String safeError = errorMessage != null
                ? errorMessage.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        .replace("\"", "&quot;").replace("'", "&#39;")
                : "Error desconocido";
        if (safeError.length() > 500) {
            safeError = safeError.substring(0, 500) + "...";
        }

        return String.format(
                """
                        <!DOCTYPE html>
                        <html lang="es">
                        <head><meta charset="UTF-8"></head>
                        <body style="margin:0;padding:0;font-family:'Segoe UI',Arial,sans-serif;background:#f5f0eb;">
                          <table width="100%%" cellpadding="0" cellspacing="0" style="max-width:620px;margin:30px auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,0.08);">
                            <tr>
                              <td style="background:linear-gradient(135deg,#991b1b,#dc2626);padding:28px 32px;">
                                <h1 style="margin:0;color:#fff;font-size:20px;font-weight:700;">❌ Fallo en Automatización</h1>
                                <p style="margin:8px 0 0;color:rgba(255,255,255,0.8);font-size:14px;">%s — Notificación automática</p>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:24px 32px 16px;">
                                <p style="margin:0;font-size:15px;color:#374151;line-height:1.6;">
                                  La tarea <strong style="color:#991b1b;">%s</strong> ha fallado durante su ejecución.
                                </p>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:0 32px 16px;">
                                <table width="100%%" cellpadding="0" cellspacing="0" style="border:1px solid #fecaca;border-radius:10px;overflow:hidden;">
                                  <tr style="background:#fef2f2;">
                                    <td style="padding:12px 16px;font-size:12px;font-weight:700;color:#991b1b;text-transform:uppercase;letter-spacing:0.05em;">Detalle del Error</td>
                                  </tr>
                                  <tr>
                                    <td style="padding:14px 16px;font-size:13px;color:#374151;font-family:monospace;word-break:break-word;background:#fff;">%s</td>
                                  </tr>
                                </table>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:0 32px 24px;">
                                <table cellpadding="0" cellspacing="0" style="font-size:13px;color:#6b7280;">
                                  <tr><td style="padding:4px 12px 4px 0;font-weight:600;">Job:</td><td>%s</td></tr>
                                  <tr><td style="padding:4px 12px 4px 0;font-weight:600;">Cron:</td><td style="font-family:monospace;">%s</td></tr>
                                  <tr><td style="padding:4px 12px 4px 0;font-weight:600;">Duración:</td><td>%d ms</td></tr>
                                  <tr><td style="padding:4px 12px 4px 0;font-weight:600;">Zona Horaria:</td><td>%s</td></tr>
                                </table>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:16px 32px 24px;border-top:1px solid #f0ece6;">
                                <p style="margin:0;font-size:12px;color:#9ca3af;text-align:center;">
                                  Alerta generada automáticamente por el motor de automatizaciones.<br>
                                  Se recomienda revisar los logs del sistema. No responder a este correo.
                                </p>
                              </td>
                            </tr>
                          </table>
                        </body>
                        </html>
                        """,
                timestamp,
                jobDisplay,
                safeError,
                auto.getJobName(),
                auto.getCronExpression(),
                durationMs,
                auto.getTimezone());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VALIDACIÓN Y UTILIDADES
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Calcula la próxima ejecución a partir de la expresión cron y la zona horaria.
     */
    private LocalDateTime calculateNextExecution(String cronExpr, String tz) {
        try {
            CronExpression cron = CronExpression.parse(cronExpr);
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(tz));
            ZonedDateTime next = cron.next(now.toLocalDateTime().atZone(ZoneId.of(tz)));
            return next != null ? next.toLocalDateTime() : null;
        } catch (Exception e) {
            log.warn("[DynamicScheduler] No se pudo calcular nextExecution para cron '{}': {}",
                    cronExpr, e.getMessage());
            return null;
        }
    }
}
