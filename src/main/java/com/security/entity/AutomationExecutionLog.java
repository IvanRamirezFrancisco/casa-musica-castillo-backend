package com.security.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Entidad JPA que mapea la tabla {@code automation_execution_logs}.
 *
 * <p>
 * Registra cada ejecución individual de una automatización del sistema,
 * tanto las disparadas por el scheduler (cron) como las manuales.
 * Mantiene relación N:1 con {@link SystemAutomation}.
 * </p>
 */
@Entity
@Table(name = "automation_execution_logs", schema = "ops")
public class AutomationExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "automation_id", nullable = false)
    private SystemAutomation automation;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /**
     * Estado de la ejecución: IN_PROGRESS, SUCCESS, FAILED, CANCELLED.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /**
     * Quién disparó la ejecución:
     * "SCHEDULER" para cron automático, o el email del usuario para manual.
     */
    @Column(name = "triggered_by", nullable = false, length = 100)
    private String triggeredBy;

    @Column(name = "duration_ms")
    private Long durationMs;

    /**
     * Resumen legible del resultado.
     * Ej: "Backup completado: 183KB" o "3 tablas limpiadas, 45 registros
     * eliminados".
     */
    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // ── Callbacks ──────────────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        if (this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
    }

    // ── Factory Methods ───────────────────────────────────────────────────

    /**
     * Crea un log de ejecución en estado IN_PROGRESS.
     */
    public static AutomationExecutionLog start(SystemAutomation automation, String triggeredBy) {
        AutomationExecutionLog log = new AutomationExecutionLog();
        log.automation = automation;
        log.startedAt = LocalDateTime.now();
        log.status = "IN_PROGRESS";
        log.triggeredBy = sanitizeTriggeredBy(triggeredBy);
        return log;
    }

    /**
     * Marca la ejecución como exitosa.
     */
    public void markSuccess(long durationMs, String resultSummary) {
        this.finishedAt = LocalDateTime.now();
        this.status = "SUCCESS";
        this.durationMs = durationMs;
        this.resultSummary = resultSummary;
        this.errorMessage = null;
    }

    /**
     * Marca la ejecución como fallida.
     */
    public void markFailed(long durationMs, String errorMessage) {
        this.finishedAt = LocalDateTime.now();
        this.status = "FAILED";
        this.durationMs = durationMs;
        this.errorMessage = truncate(errorMessage, 2000);
    }

    // ── Sanitización ──────────────────────────────────────────────────────

    private static String sanitizeTriggeredBy(String value) {
        if (value == null || value.isBlank())
            return "UNKNOWN";
        // Solo permitir alfanuméricos, @, ., _, -
        String sanitized = value.replaceAll("[^a-zA-Z0-9@._\\-]", "");
        return sanitized.length() > 100 ? sanitized.substring(0, 100) : sanitized;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null)
            return null;
        return value.length() > maxLength
                ? value.substring(0, maxLength) + "..."
                : value;
    }

    // ── Getters y Setters ─────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public SystemAutomation getAutomation() {
        return automation;
    }

    public void setAutomation(SystemAutomation automation) {
        this.automation = automation;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
