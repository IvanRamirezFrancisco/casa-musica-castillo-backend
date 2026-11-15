package com.security.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Entidad AuditLog - Registro de auditoría para acciones administrativas
 * críticas
 * Cumple con estándares de seguridad Enterprise para trazabilidad
 */
@Entity
@Table(name = "audit_logs", schema = "security", indexes = {
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_entity_type", columnList = "entity_type"),
        @Index(name = "idx_audit_performed_by", columnList = "performed_by"),
        @Index(name = "idx_audit_created_at", columnList = "created_at")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Acción realizada (CREATE, UPDATE, DELETE, ACTIVATE, DEACTIVATE, LOCK, UNLOCK,
     * etc.)
     */
    @NotBlank(message = "Action is required")
    @Size(max = 50, message = "Action cannot exceed 50 characters")
    @Column(nullable = false, length = 50)
    private String action;

    /**
     * Tipo de evento — espejo de action para compatibilidad con esquema de BD
     * (event_type NOT NULL en la tabla audit_logs)
     */
    @Size(max = 100, message = "Event type cannot exceed 100 characters")
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * Tipo de entidad afectada (USER, ROLE, PERMISSION, ORDER, PRODUCT, etc.)
     */
    @NotBlank(message = "Entity type is required")
    @Size(max = 50, message = "Entity type cannot exceed 50 characters")
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /**
     * ID de la entidad afectada (ej: userId, roleId, orderId)
     */
    @Column(name = "entity_id")
    private Long entityId;

    /**
     * Email o username del usuario que realizó la acción
     */
    @NotBlank(message = "Performed by is required")
    @Size(max = 100, message = "Performed by cannot exceed 100 characters")
    @Column(name = "performed_by", nullable = false, length = 100)
    private String performedBy;

    /**
     * ID del usuario que realizó la acción (referencia a users.id)
     */
    @Column(name = "performed_by_user_id")
    private Long performedByUserId;

    /**
     * Descripción detallada de la acción realizada
     * Ejemplo: "Admin admin@empresa.com desactivó al usuario staff@empresa.com (ID:
     * 15)"
     */
    @Size(max = 500, message = "Details cannot exceed 500 characters")
    @Column(columnDefinition = "TEXT")
    private String details;

    /**
     * Dirección IP desde donde se realizó la acción
     */
    @Size(max = 45, message = "IP address cannot exceed 45 characters")
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * Descripción del evento — espejo de details para compatibilidad con BD
     * (event_description NOT NULL en la tabla audit_logs)
     */
    @Column(name = "event_description", nullable = false, columnDefinition = "TEXT")
    private String eventDescription;

    /**
     * User-Agent del navegador/aplicación que realizó la acción
     */
    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    /**
     * Datos antiguos en formato JSON (antes de la actualización).
     * 
     * @JdbcTypeCode(SqlTypes.JSON) hace que Hibernate 6 envíe el binding
     *                              como un tipo JSON nativo de PostgreSQL, evitando
     *                              el error "character varying".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_values", columnDefinition = "json")
    private String oldValues;

    /**
     * Datos nuevos en formato JSON (después de la actualización).
     * 
     * @JdbcTypeCode(SqlTypes.JSON) hace que Hibernate 6 envíe el binding
     *                              como un tipo JSON nativo de PostgreSQL, evitando
     *                              el error "character varying".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_values", columnDefinition = "json")
    private String newValues;

    /**
     * Indica si la acción fue exitosa o falló
     */
    @Column(name = "is_success", nullable = false)
    private Boolean isSuccess = true;

    /**
     * Mensaje de error si la acción falló
     */
    @Size(max = 500, message = "Error message cannot exceed 500 characters")
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /**
     * Severidad del evento (INFO, WARNING, ERROR, CRITICAL)
     */
    @Column(name = "severity", length = 20)
    private String severity = "INFO";

    /**
     * Estado del evento (SUCCESS, FAILED, etc.)
     */
    @Column(name = "status", length = 50)
    private String status = "SUCCESS";

    /**
     * Recurso afectado por la acción
     */
    @Size(max = 200, message = "Resource affected cannot exceed 200 characters")
    @Column(name = "resource_affected", length = 200)
    private String resourceAffected;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ==================== Constructors ====================

    public AuditLog() {
    }

    /**
     * Constructor básico para logs de auditoría simples
     */
    public AuditLog(String action, String entityType, Long entityId, String performedBy, String details) {
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.performedBy = performedBy;
        this.details = details;
        this.isSuccess = true;
    }

    // ==================== Getters and Setters ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public void setEntityId(Long entityId) {
        this.entityId = entityId;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(String performedBy) {
        this.performedBy = performedBy;
    }

    public Long getPerformedByUserId() {
        return performedByUserId;
    }

    public void setPerformedByUserId(Long performedByUserId) {
        this.performedByUserId = performedByUserId;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getOldValues() {
        return oldValues;
    }

    public void setOldValues(String oldValues) {
        this.oldValues = oldValues;
    }

    public String getNewValues() {
        return newValues;
    }

    public void setNewValues(String newValues) {
        this.newValues = newValues;
    }

    public Boolean getIsSuccess() {
        return isSuccess;
    }

    public void setIsSuccess(Boolean isSuccess) {
        this.isSuccess = isSuccess;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getEventDescription() {
        return eventDescription;
    }

    public void setEventDescription(String eventDescription) {
        this.eventDescription = eventDescription;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResourceAffected() {
        return resourceAffected;
    }

    public void setResourceAffected(String resourceAffected) {
        this.resourceAffected = resourceAffected;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    // ==================== Helper Methods ====================

    @Override
    public String toString() {
        return "AuditLog{" +
                "id=" + id +
                ", action='" + action + '\'' +
                ", entityType='" + entityType + '\'' +
                ", entityId=" + entityId +
                ", performedBy='" + performedBy + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
