package com.security.service.automation;

import com.security.dto.admin.TableMaintenanceDto;
import com.security.entity.Product;
import com.security.entity.SystemAutomation;
import com.security.entity.User;
import com.security.enums.InvitationStatus;
import com.security.repository.PasswordResetTokenRepository;
import com.security.repository.ProductRepository;
import com.security.repository.StaffInvitationRepository;
import com.security.repository.UserRepository;
import com.security.repository.VerificationTokenRepository;
import com.security.service.DatabaseBackupService;
import com.security.service.DatabaseMaintenanceService;
import com.security.service.EmailService;
import com.security.service.SessionManagementService;
import com.security.service.TwoFactorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Registro centralizado de los {@link Runnable} que el motor de
 * automatizaciones puede ejecutar.
 *
 * <p>
 * Cada {@code job_name} almacenado en {@code system_automations}
 * se resuelve aquí a un Runnable concreto que invoca los servicios
 * existentes del backend.
 * </p>
 *
 * <p>
 * <strong>Seguridad:</strong> ningún dato del frontend se ejecuta
 * como código. Los parámetros JSONB solo se leen como valores de
 * configuración (umbrales, emails), nunca como expresiones evaluables.
 * </p>
 */
@Component
public class JobRunnableFactory {

    private static final Logger log = LoggerFactory.getLogger(JobRunnableFactory.class);

    private final DatabaseBackupService backupService;
    private final DatabaseMaintenanceService maintenanceService;
    private final SessionManagementService sessionService;
    private final TwoFactorService twoFactorService;
    private final ProductRepository productRepository;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final StaffInvitationRepository staffInvitationRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final TransactionTemplate txTemplate;

    public JobRunnableFactory(
            DatabaseBackupService backupService,
            DatabaseMaintenanceService maintenanceService,
            SessionManagementService sessionService,
            TwoFactorService twoFactorService,
            ProductRepository productRepository,
            EmailService emailService,
            UserRepository userRepository,
            StaffInvitationRepository staffInvitationRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            VerificationTokenRepository verificationTokenRepository,
            PlatformTransactionManager txManager) {
        this.backupService = backupService;
        this.maintenanceService = maintenanceService;
        this.sessionService = sessionService;
        this.twoFactorService = twoFactorService;
        this.productRepository = productRepository;
        this.emailService = emailService;
        this.userRepository = userRepository;
        this.staffInvitationRepository = staffInvitationRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Resuelve el {@link Callable} correspondiente al job_name de la entidad.
     * El String retornado es el result_summary descriptivo del job.
     *
     * @param automation la entidad con job_name y parameters
     * @return un Callable que ejecuta la lógica del job y devuelve un resumen
     * @throws IllegalArgumentException si el job_name no está registrado
     */
    public Callable<String> resolve(SystemAutomation automation) {
        String jobName = automation.getJobName();
        Map<String, Object> params = automation.getParameters();

        return switch (jobName) {
            case "BACKUP_DATABASE_JOB" -> createBackupJob(params);
            case "SESSION_CLEANUP_JOB" -> createSessionCleanupJob();
            case "DB_MAINTENANCE_JOB" -> createMaintenanceJob(params);
            case "INVENTORY_AUDIT_JOB" -> createInventoryAuditJob(params);
            case "ACCOUNT_CLEANUP_JOB" -> createAccountCleanupJob(params);
            default -> throw new IllegalArgumentException(
                    "Job no registrado en JobRunnableFactory: " + jobName);
        };
    }

    // ── Job: Respaldo de Base de Datos ─────────────────────────────────────

    private Callable<String> createBackupJob(Map<String, Object> params) {
        return () -> {
            log.info("🔄 [AutoJob] Ejecutando BACKUP_DATABASE_JOB (disparado por motor dinámico)");

            // Leer parámetros de la automatización
            List<String> tables = Collections.emptyList();
            String backupType = "FULL";
            if (params != null) {
                backupType = params.containsKey("backup_type")
                        ? String.valueOf(params.get("backup_type")).toUpperCase()
                        : "FULL";
                if ("PARTIAL".equals(backupType) && params.containsKey("tables")) {
                    Object tablesObj = params.get("tables");
                    if (tablesObj instanceof List<?> rawList) {
                        tables = rawList.stream()
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .toList();
                    }
                }
            }

            backupService.triggerBackup("SYSTEM_AUTOMATION", tables);

            // Construir resumen descriptivo
            String tipo = tables.isEmpty()
                    ? "Completo"
                    : "Selectivo (" + tables.size() + " tabla" + (tables.size() == 1 ? "" : "s") + ")";
            return String.format("Respaldo %s iniciado correctamente · Tipo: %s", backupType, tipo);
        };
    }

    // ── Job: Limpieza de Sesiones Expiradas ────────────────────────────────

    private Callable<String> createSessionCleanupJob() {
        return () -> {
            log.info("🔄 [AutoJob] Ejecutando SESSION_CLEANUP_JOB");
            int steps = 0;
            StringBuilder detail = new StringBuilder();

            try {
                sessionService.cleanupExpiredSessions();
                steps++;
                detail.append("Sesiones persistentes expiradas limpiadas");
                log.info("  ↳ Limpieza de sesiones persistentes expiradas completada.");
            } catch (Exception e) {
                log.warn("  ↳ Error limpiando sesiones: {}", e.getMessage());
            }
            try {
                twoFactorService.cleanupExpiredTokens();
                steps++;
                if (!detail.isEmpty())
                    detail.append(" · ");
                detail.append("Tokens 2FA purgados");
                log.info("  ↳ Limpieza de tokens 2FA expirados completada.");
            } catch (Exception e) {
                log.warn("  ↳ Error limpiando 2FA: {}", e.getMessage());
            }

            return String.format("%d proceso(s) de limpieza ejecutados · %s", steps, detail);
        };
    }

    // ── Job: Mantenimiento PostgreSQL (VACUUM + ANALYZE) ───────────────────

    private Callable<String> createMaintenanceJob(Map<String, Object> params) {
        return () -> {
            log.info("🔄 [AutoJob] Ejecutando DB_MAINTENANCE_JOB");
            try {
                // Leer parámetros opcionales
                // El frontend guarda "run_analyze"; se acepta también "analyze" por
                // compatibilidad
                boolean runAnalyze = true;
                int deadTupleThreshold = 20; // default
                if (params != null) {
                    Object analyzeObj = params.containsKey("run_analyze")
                            ? params.get("run_analyze")
                            : params.get("analyze");
                    if (analyzeObj instanceof Boolean b) {
                        runAnalyze = b;
                    } else if (analyzeObj instanceof String s) {
                        runAnalyze = Boolean.parseBoolean(s);
                    }

                    if (params.containsKey("dead_tuple_threshold")) {
                        Object threshObj = params.get("dead_tuple_threshold");
                        if (threshObj instanceof Number n) {
                            deadTupleThreshold = Math.max(1, n.intValue());
                        }
                    }
                }

                // ── Paso 1: VACUUM ANALYZE sobre tablas con dead tuples ──────
                List<TableMaintenanceDto> allDirtyTables = maintenanceService.getDeadTuplesStats();

                // Filtrar por umbral configurado
                final int threshold = deadTupleThreshold;
                List<TableMaintenanceDto> dirtyTables = allDirtyTables.stream()
                        .filter(t -> t.deadTuples() > threshold)
                        .toList();

                int vacuumed = 0;
                int errors = 0;
                long totalDeadBefore = 0;
                long totalDeadAfter = 0;

                if (dirtyTables.isEmpty()) {
                    log.info("  ↳ No se encontraron tablas con más de {} dead tuples. VACUUM no es necesario.",
                            threshold);
                } else {
                    log.info("  ↳ {} tablas con más de {} dead tuples. Iniciando VACUUM ANALYZE...",
                            dirtyTables.size(), threshold);
                    for (TableMaintenanceDto table : dirtyTables) {
                        try {
                            long deadBefore = table.deadTuples();
                            totalDeadBefore += deadBefore;

                            maintenanceService.runVacuumWithLog(
                                    table.tableName(), "SYSTEM_AUTOMATION");
                            vacuumed++;

                            // Consultar dead tuples después del vacuum
                            Integer deadAfter = maintenanceService.queryDeadTuplesPublic(table.tableName());
                            totalDeadAfter += (deadAfter != null ? deadAfter : 0);
                        } catch (Exception e) {
                            errors++;
                            log.warn("  ↳ Error en VACUUM ANALYZE '{}': {}",
                                    table.tableName(), e.getMessage());
                            // Continuar con las demás tablas
                        }
                    }
                    log.info("  ↳ VACUUM completado: {}/{} tablas procesadas ({} errores)",
                            vacuumed, dirtyTables.size(), errors);
                }

                // ── Paso 2: ANALYZE global (estadísticas del planificador) ───
                if (runAnalyze) {
                    maintenanceService.runAnalyzeAll();
                    log.info("  ↳ ANALYZE global completado exitosamente.");
                }

                long deadFreed = Math.max(0, totalDeadBefore - totalDeadAfter);

                log.info("  ↳ DB_MAINTENANCE_JOB finalizado: {} tablas limpiadas, {} dead tuples liberados, {} errores",
                        vacuumed, deadFreed, errors);

                // Si hubo errores parciales pero no totales, no fallar el job
                if (errors > 0 && vacuumed == 0 && !dirtyTables.isEmpty()) {
                    throw new RuntimeException(
                            "VACUUM falló en todas las " + dirtyTables.size() + " tablas.");
                }

                // Construir resumen descriptivo
                StringBuilder summary = new StringBuilder();
                summary.append(String.format("%d tabla(s) limpiadas", vacuumed));
                if (deadFreed > 0) {
                    summary.append(String.format(" · %d dead tuples eliminados", deadFreed));
                }
                if (runAnalyze) {
                    summary.append(" · ANALYZE global ejecutado");
                }
                if (errors > 0) {
                    summary.append(String.format(" · %d error(es)", errors));
                }
                if (dirtyTables.isEmpty()) {
                    return "Sin tablas con dead tuples > " + threshold + " · Base de datos limpia";
                }
                return summary.toString();

            } catch (Exception e) {
                log.error("  ↳ Error en mantenimiento automático: {}", e.getMessage());
                throw e; // propagar para que el motor marque FAILED
            }
        };
    }

    // ── Job: Auditoría de Inventario + Notificación por Email ─────────────

    private Callable<String> createInventoryAuditJob(Map<String, Object> params) {
        return () -> {
            log.info("🔄 [AutoJob] Ejecutando INVENTORY_AUDIT_JOB");

            // ── Determinar umbral desde params (system_automations.parameters) ──
            int threshold = 10;
            if (params != null) {
                if (params.containsKey("stock_threshold")) {
                    threshold = ((Number) params.get("stock_threshold")).intValue();
                } else if (params.containsKey("threshold")) {
                    threshold = ((Number) params.get("threshold")).intValue();
                }
            }

            // ── Paso 1: Escanear productos con stock bajo ──────────────────
            List<Product> lowStock = productRepository.findLowStockProducts(threshold);
            log.info("  ↳ Umbral configurado: {}. Productos en stock crítico: {}",
                    threshold, lowStock.size());

            if (lowStock.isEmpty()) {
                log.info("  ↳ No se encontraron productos con stock bajo. Auditoría completada.");
                return "0 productos en stock crítico · Inventario saludable (umbral: " + threshold + ")";
            }

            // ── Paso 2: Obtener y validar correos de notificación ───────────
            List<String> emails = extractValidEmails(params);
            if (emails.isEmpty()) {
                log.warn("  ↳ No hay correos de notificación configurados. " +
                        "Los productos con stock bajo no serán notificados por email.");
                return lowStock.size() + " producto(s) en stock crítico · Sin destinatarios configurados";
            }

            // ── Paso 3: Construir y enviar email de reporte ─────────────────
            String subject = "⚠️ Alerta de Inventario Crítico — " + lowStock.size() + " producto(s)";
            String html = buildInventoryAlertHtml(lowStock, threshold);

            int sent = 0;
            int failed = 0;
            for (String email : emails) {
                try {
                    emailService.sendHtmlEmail(email, subject, html);
                    sent++;
                    log.info("  ↳ Notificación enviada a: {}", maskEmail(email));
                } catch (Exception e) {
                    failed++;
                    log.warn("  ↳ Error enviando notificación a {}: {}", maskEmail(email), e.getMessage());
                }
            }

            log.info("  ↳ INVENTORY_AUDIT_JOB finalizado: {} productos críticos, " +
                    "{}/{} correos enviados ({} fallidos)",
                    lowStock.size(), sent, emails.size(), failed);

            if (sent == 0 && !emails.isEmpty()) {
                throw new RuntimeException(
                        "Falló el envío a todos los " + emails.size() + " destinatarios configurados.");
            }

            // Construir resumen descriptivo
            StringBuilder summary = new StringBuilder();
            summary.append(String.format("%d producto(s) en stock crítico", lowStock.size()));
            summary.append(String.format(" · Reporte enviado a %d destinatario(s)", sent));
            if (failed > 0) {
                summary.append(String.format(" · %d envío(s) fallido(s)", failed));
            }
            return summary.toString();
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILIDADES PRIVADAS (Inventory Audit)
    // ═══════════════════════════════════════════════════════════════════════

    // ── Job: Limpieza de Cuentas Inactivas / Tokens Expirados ──────────────

    private Callable<String> createAccountCleanupJob(Map<String, Object> params) {
        return () -> {
            log.info("🔄 [AutoJob] Ejecutando ACCOUNT_CLEANUP_JOB");
            int steps = 0;
            StringBuilder detail = new StringBuilder();

            // ── Paso 1: Expirar invitaciones pendientes vencidas ──
            try {
                var pendingInvitations = staffInvitationRepository
                        .findByStatusOrderByCreatedAtDesc(InvitationStatus.PENDING);
                long expiredCount = pendingInvitations.stream()
                        .filter(inv -> inv.isExpired())
                        .peek(inv -> inv.setStatus(InvitationStatus.EXPIRED))
                        .count();
                if (expiredCount > 0) {
                    staffInvitationRepository.saveAll(pendingInvitations);
                    detail.append(expiredCount).append(" invitaciones expiradas");
                    log.info("  ↳ {} invitaciones marcadas como expiradas", expiredCount);
                } else {
                    detail.append("0 invitaciones pendientes vencidas");
                }
                steps++;
            } catch (Exception e) {
                log.warn("  ↳ Error expirando invitaciones: {}", e.getMessage());
            }

            // ── Paso 2: Eliminar tokens de reset de contraseña expirados/usados ──
            try {
                LocalDateTime now = LocalDateTime.now();
                var expiredTokens = passwordResetTokenRepository.findExpiredTokens(now);
                if (!expiredTokens.isEmpty()) {
                    passwordResetTokenRepository.deleteAll(expiredTokens);
                    if (!detail.isEmpty())
                        detail.append(" · ");
                    detail.append(expiredTokens.size()).append(" tokens de reset eliminados");
                    log.info("  ↳ {} tokens de reset expirados/usados eliminados", expiredTokens.size());
                }
                steps++;
            } catch (Exception e) {
                log.warn("  ↳ Error limpiando tokens de reset: {}", e.getMessage());
            }

            // ── Paso 3: Eliminar tokens de verificación expirados ──
            try {
                LocalDateTime now = LocalDateTime.now();
                verificationTokenRepository.deleteExpiredTokens(now);
                if (!detail.isEmpty())
                    detail.append(" · ");
                detail.append("Tokens de verificación purgados");
                steps++;
                log.info("  ↳ Tokens de verificación expirados eliminados");
            } catch (Exception e) {
                log.warn("  ↳ Error limpiando tokens de verificación: {}", e.getMessage());
            }

            // ── Paso 4: Purgar usuarios fantasma (enabled=false) con más de 48h ──
            try {
                int ghostHours = 48; // default: 48 horas sin verificar → borrar
                if (params != null && params.containsKey("ghost_hours")) {
                    ghostHours = ((Number) params.get("ghost_hours")).intValue();
                }
                LocalDateTime cutoff = LocalDateTime.now().minusHours(ghostHours);

                List<Long> ghostIds = userRepository.findGhostUserIds(cutoff);
                if (!ghostIds.isEmpty()) {
                    final List<Long> ids = Collections.unmodifiableList(ghostIds);
                    // Ejecutar toda la cascada de borrado en una sola transacción
                    txTemplate.executeWithoutResult(status -> {
                        // 4a) Tokens de verificación de los fantasmas
                        verificationTokenRepository.deleteByUserIdIn(ids);
                        // 4b) Tokens de reset de contraseña de los fantasmas
                        passwordResetTokenRepository.deleteByUserIdIn(ids);
                        // 4c) Filas de la join table user_roles (no tiene cascade)
                        userRepository.deleteUserRolesByUserIds(ids);
                        // 4d) Eliminar los usuarios fantasma
                        userRepository.deleteByIdIn(ids);
                    });

                    if (!detail.isEmpty())
                        detail.append(" · ");
                    detail.append(ghostIds.size()).append(" usuarios fantasma purgados (>").append(ghostHours)
                            .append("h)");
                    log.info("  ↳ {} usuarios fantasma eliminados (creados hace >{}h)", ghostIds.size(), ghostHours);
                } else {
                    if (!detail.isEmpty())
                        detail.append(" · ");
                    detail.append("0 usuarios fantasma");
                }
                steps++;
            } catch (Exception e) {
                log.warn("  ↳ Error purgando usuarios fantasma: {}", e.getMessage());
            }

            log.info("  ↳ ACCOUNT_CLEANUP_JOB finalizado: {} pasos ejecutados", steps);
            return String.format("%d proceso(s) de limpieza · %s", steps, detail);
        };
    }

    /**
     * Extrae y valida los emails del parámetro {@code notify_emails}
     * contra la base de datos de empleados activos del sistema.
     *
     * <p>
     * <strong>Seguridad:</strong> solo se permiten emails que pertenezcan
     * a usuarios activos (enabled=true, accountNonLocked=true) y no-clientes.
     * Si un empleado fue desactivado desde la última configuración, su email
     * se descarta silenciosamente sin generar error.
     * </p>
     */
    private List<String> extractValidEmails(Map<String, Object> params) {
        if (params == null || !params.containsKey("notify_emails")) {
            return List.of();
        }

        Object raw = params.get("notify_emails");
        List<String> candidates;

        if (raw instanceof List<?> list) {
            candidates = list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .toList();
        } else if (raw instanceof String str) {
            candidates = List.of(str.split(",")).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .toList();
        } else {
            return List.of();
        }

        // Obtener el set de emails de empleados activos desde la BD
        Set<String> activeStaffEmails = userRepository.findActiveStaffMembers().stream()
                .map(User::getEmail)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        List<String> valid = candidates.stream()
                .filter(activeStaffEmails::contains)
                .distinct()
                .toList();

        long rejected = candidates.size() - valid.size();
        if (rejected > 0) {
            log.warn("  ↳ {} email(s) descartados porque no pertenecen a empleados activos del sistema.",
                    rejected);
        }

        return valid;
    }

    /**
     * Construye el HTML del reporte de inventario crítico.
     * Diseño profesional con tabla de productos, SKU, stock actual y umbral.
     */
    private String buildInventoryAlertHtml(List<Product> products, int threshold) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));

        StringBuilder rows = new StringBuilder();
        for (Product p : products) {
            String stockColor = (p.getStock() != null && p.getStock() == 0) ? "#dc2626" : "#d97706";
            rows.append(String.format(
                    """
                            <tr>
                              <td style="padding:10px 14px;border-bottom:1px solid #f0ece6;font-size:14px;color:#1a1a1a;">%s</td>
                              <td style="padding:10px 14px;border-bottom:1px solid #f0ece6;font-size:13px;color:#6b7280;font-family:monospace;">%s</td>
                              <td style="padding:10px 14px;border-bottom:1px solid #f0ece6;text-align:center;">
                                <span style="display:inline-block;padding:3px 10px;border-radius:12px;font-size:13px;font-weight:700;color:#fff;background:%s;">%d</span>
                              </td>
                            </tr>
                            """,
                    escapeHtml(truncate(p.getName(), 60)),
                    escapeHtml(p.getSku()),
                    stockColor,
                    p.getStock() != null ? p.getStock() : 0));
        }

        return String.format(
                """
                        <!DOCTYPE html>
                        <html lang="es">
                        <head><meta charset="UTF-8"></head>
                        <body style="margin:0;padding:0;font-family:'Segoe UI',Arial,sans-serif;background:#f5f0eb;">
                          <table width="100%%" cellpadding="0" cellspacing="0" style="max-width:620px;margin:30px auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,0.08);">
                            <!-- Header -->
                            <tr>
                              <td style="background:linear-gradient(135deg,#722f37,#944050);padding:28px 32px;">
                                <h1 style="margin:0;color:#fff;font-size:20px;font-weight:700;">⚠️ Alerta de Inventario Crítico</h1>
                                <p style="margin:8px 0 0;color:rgba(255,255,255,0.8);font-size:14px;">%s — Generado automáticamente</p>
                              </td>
                            </tr>
                            <!-- Summary -->
                            <tr>
                              <td style="padding:24px 32px 16px;">
                                <p style="margin:0;font-size:15px;color:#374151;line-height:1.6;">
                                  Se encontraron <strong style="color:#722f37;">%d producto(s)</strong> con stock
                                  <strong>igual o menor a %d unidades</strong>. Se recomienda reabastecer a la brevedad.
                                </p>
                              </td>
                            </tr>
                            <!-- Table -->
                            <tr>
                              <td style="padding:0 32px 24px;">
                                <table width="100%%" cellpadding="0" cellspacing="0" style="border:1px solid #ece8e2;border-radius:10px;overflow:hidden;">
                                  <thead>
                                    <tr style="background:#f8f6f3;">
                                      <th style="padding:10px 14px;text-align:left;font-size:12px;font-weight:700;color:#6b7280;text-transform:uppercase;letter-spacing:0.05em;">Producto</th>
                                      <th style="padding:10px 14px;text-align:left;font-size:12px;font-weight:700;color:#6b7280;text-transform:uppercase;letter-spacing:0.05em;">SKU</th>
                                      <th style="padding:10px 14px;text-align:center;font-size:12px;font-weight:700;color:#6b7280;text-transform:uppercase;letter-spacing:0.05em;">Stock</th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    %s
                                  </tbody>
                                </table>
                              </td>
                            </tr>
                            <!-- Footer -->
                            <tr>
                              <td style="padding:16px 32px 24px;border-top:1px solid #f0ece6;">
                                <p style="margin:0;font-size:12px;color:#9ca3af;text-align:center;">
                                  Este correo fue generado automáticamente por el sistema de automatizaciones.<br>
                                  Umbral configurado: <strong>%d unidades</strong> · No responder a este correo.
                                </p>
                              </td>
                            </tr>
                          </table>
                        </body>
                        </html>
                        """,
                timestamp,
                products.size(),
                threshold,
                rows.toString(),
                threshold);
    }

    /** Escapa caracteres HTML para prevenir XSS en el email. */
    private static String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Trunca un String a maxLen caracteres, añadiendo "..." si excede. */
    private static String truncate(String text, int maxLen) {
        if (text == null)
            return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /** Enmascara un email para logs (ej. ad***@empresa.com). */
    private static String maskEmail(String email) {
        if (email == null || !email.contains("@"))
            return "***";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2)
            return local + "***" + domain;
        return local.substring(0, 2) + "***" + domain;
    }
}
