package com.security.entity;

import com.security.enums.BackupStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entidad de auditoría para cada ejecución del proceso de respaldo.
 *
 * <p>
 * Mapeada a la tabla {@code backup_logs} creada manualmente en PostgreSQL:
 * 
 * <pre>
 * CREATE TABLE backup_logs (
 *     id                BIGSERIAL PRIMARY KEY,
 *     filename          VARCHAR(255)  NOT NULL,
 *     file_path         VARCHAR(500),
 *     file_size_bytes   BIGINT,
 *     status            VARCHAR(50)   NOT NULL,
 *     error_message     TEXT,
 *     execution_time_ms BIGINT,
 *     created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *     triggered_by      VARCHAR(100),
 *     is_deleted        BOOLEAN DEFAULT false
 * );
 * </pre>
 *
 * <p>
 * <strong>Política de auditoría:</strong> los registros nunca se borran
 * físicamente;
 * cuando el archivo en disco se elimina por la política de retención, solo se
 * establece {@code isDeleted = true}.
 */
@Entity
@Table(name = "backup_logs", schema = "ops")
public class BackupLog {

    // ── Identidad ──────────────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Archivo ────────────────────────────────────────────────────────────────

    /**
     * Nombre lógico del archivo (sin ruta). Ej:
     * {@code backup_security_db_20260306_030000.dump}
     */
    @Column(nullable = false, length = 255)
    private String filename;

    /**
     * Ruta del objeto en Supabase Storage (ej.
     * {@code backups/backup_security_db_20260307.dump}).
     * Es {@code null} mientras el proceso está en estado {@code PENDING} o
     * {@code FAILED}
     * (antes de completar la subida). Solo tiene valor cuando
     * {@code status = COMPLETED}.
     */
    @Column(name = "file_path", nullable = true, length = 500)
    private String filePath;

    /**
     * Tamaño en bytes del volcado comprimido (-Fc). Null mientras el proceso está
     * en PENDING.
     */
    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    // ── Estado del proceso ─────────────────────────────────────────────────────

    /**
     * Estado del ciclo de vida: PENDING → COMPLETED | FAILED.
     * Almacenado como String (EnumType.STRING) para legibilidad directa en la BD.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private BackupStatus status;

    /**
     * Mensaje de error capturado cuando el proceso falla.
     * Contiene la excepción exacta o la salida stderr de pg_dump para diagnóstico
     * del DBA.
     * Es nulo en registros COMPLETED.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Tiempo total en milisegundos que tardó pg_dump en completarse. */
    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    // ── Auditoría ──────────────────────────────────────────────────────────────

    /** Timestamp de creación del registro (cuando se disparó la solicitud). */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Identidad de quien disparó el respaldo.
     * Valor {@code "SYSTEM"} para tareas programadas (cron),
     * o el email del Super Admin que hizo click en "Generar Respaldo".
     */
    @Column(name = "triggered_by", length = 100)
    private String triggeredBy;

    /**
     * Soft-delete para política de retención.
     * Cuando el archivo físico es eliminado del disco (ej. backups de más de 30
     * días),
     * este campo se marca {@code true}. El registro histórico de auditoría
     * permanece intacto.
     */
    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    /**
     * Bitácora técnica completa del proceso de respaldo.
     *
     * <p>
     * Contiene, con timestamp por línea:
     * <ul>
     * <li>Hitos internos del servicio (inicio, verificación de archivo, subida,
     * limpieza).</li>
     * <li>Salida estándar (stdout) de pg_dump.</li>
     * <li>Salida de errores (stderr) de pg_dump.</li>
     * </ul>
     *
     * <p>
     * Se persiste tanto en registros {@code COMPLETED} como en {@code FAILED},
     * garantizando visibilidad completa del proceso sin importar el resultado.
     * Es {@code null} para registros creados antes de la migración V14.
     */
    @Column(name = "execution_log", columnDefinition = "TEXT")
    private String executionLog;

    // ── Constructores ──────────────────────────────────────────────────────────

    public BackupLog() {
    }

    /** Constructor de conveniencia para crear el registro inicial (PENDING). */
    public BackupLog(String filename, String filePath, String triggeredBy) {
        this.filename = filename;
        this.filePath = filePath;
        this.triggeredBy = triggeredBy;
        this.status = BackupStatus.PENDING;
        this.isDeleted = false;
    }

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String v) {
        this.filename = v;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String v) {
        this.filePath = v;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long v) {
        this.fileSizeBytes = v;
    }

    public BackupStatus getStatus() {
        return status;
    }

    public void setStatus(BackupStatus v) {
        this.status = v;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String v) {
        this.errorMessage = v;
    }

    public Long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(Long v) {
        this.executionTimeMs = v;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String v) {
        this.triggeredBy = v;
    }

    public boolean isDeleted() {
        return isDeleted;
    }

    public void setDeleted(boolean v) {
        this.isDeleted = v;
    }

    public String getExecutionLog() {
        return executionLog;
    }

    public void setExecutionLog(String v) {
        this.executionLog = v;
    }

    @Override
    public String toString() {
        return "BackupLog{id=" + id + ", filename='" + filename +
                "', status=" + status + ", triggeredBy='" + triggeredBy + "'}";
    }
}
