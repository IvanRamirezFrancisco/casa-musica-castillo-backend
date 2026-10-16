package com.security.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.entity.AuditLog;
import com.security.repository.AuditLogRepository;
import com.security.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * Servicio para registrar logs de auditoría de acciones administrativas
 * críticas
 * Cumple con estándares de seguridad Enterprise para trazabilidad
 */
@Service
public class AuditLogService {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== Método unificado (solicitud principal)
    // ====================

    /**
     * Registra un evento de auditoría de forma asíncrona.
     *
     * <p>
     * Uso recomendado en servicios que modifican datos críticos:
     * 
     * <pre>
     * auditLogService.log("UPDATE", "PRODUCT_UPDATE", "PRODUCT",
     *         product.getId(), oldValues, newValues, "INFO", true);
     * </pre>
     *
     * @param action     Acción realizada (UPDATE, DELETE, LOGIN, etc.)
     * @param eventType  Tipo de evento (PRODUCT_UPDATE, ORDER_STATUS_CHANGE, etc.)
     * @param entityType Tipo de entidad afectada (PRODUCT, ORDER, USER, etc.)
     * @param entityId   ID de la entidad afectada (puede ser null)
     * @param oldValues  Estado anterior — cualquier objeto; se serializa a JSON
     *                   internamente
     * @param newValues  Nuevo estado — cualquier objeto; se serializa a JSON
     *                   internamente
     * @param severity   Nivel de severidad: INFO | WARNING | ERROR | CRITICAL
     * @param isSuccess  true si la operación fue exitosa, false si falló
     */
    @Async
    @Transactional
    public void log(String action, String eventType, String entityType,
            Long entityId, Object oldValues, Object newValues,
            String severity, boolean isSuccess) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String performedBy = resolvePerformedBy(auth);
            Long performedByUserId = resolveUserId(auth);

            String oldJson = toJson(oldValues);
            String newJson = toJson(newValues);

            String description = buildDescription(action, eventType, entityType, entityId, performedBy, isSuccess);

            AuditLog entry = new AuditLog();
            entry.setAction(action);
            entry.setEventType(eventType);
            entry.setEventDescription(description);
            entry.setEntityType(entityType);
            entry.setEntityId(entityId);
            entry.setPerformedBy(performedBy);
            entry.setPerformedByUserId(performedByUserId);
            entry.setOldValues(oldJson);
            entry.setNewValues(newJson);
            entry.setIsSuccess(isSuccess);
            entry.setSeverity(severity != null ? severity : "INFO");
            entry.setStatus(isSuccess ? "SUCCESS" : "FAILED");
            entry.setDetails(description);
            entry.setResourceAffected(entityType + (entityId != null ? ":" + entityId : ""));

            // Capturar IP y User-Agent desde RequestContextHolder (no requiere parámetro)
            enrichWithRequestData(entry);

            auditLogRepository.save(entry);
            logger.info("✅ Audit log: action={} eventType={} entity={}:{} by={} success={}",
                    action, eventType, entityType, entityId, performedBy, isSuccess);

        } catch (Exception e) {
            // El fallo de auditoría nunca debe interrumpir la operación principal
            logger.error("❌ Error al registrar audit log [{} / {}]: {}", action, eventType, e.getMessage());
        }
    }



    /**
     * Registra un log de auditoría de forma asíncrona
     * NO bloquea la operación principal
     */
    @Async
    @Transactional
    public void logAction(String action, String entityType, Long entityId, String details) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String performedBy = auth != null ? auth.getName() : "SYSTEM";

            AuditLog log = new AuditLog();
            log.setAction(action);
            log.setEventType(action);
            log.setEventDescription(details != null ? details : action);
            log.setEntityType(entityType);
            log.setEntityId(entityId);
            log.setPerformedBy(performedBy);
            log.setDetails(details);
            log.setIsSuccess(true);
            log.setSeverity("INFO");
            log.setStatus("SUCCESS");

            // Capturar IP y User-Agent si está disponible
            try {
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder
                        .getRequestAttributes();
                if (attributes != null) {
                    HttpServletRequest request = attributes.getRequest();
                    log.setIpAddress(getClientIp(request));
                    log.setUserAgent(request.getHeader("User-Agent"));
                }
            } catch (Exception e) {
                logger.warn("No se pudo capturar información de la request: {}", e.getMessage());
            }

            auditLogRepository.save(log);
            logger.info("✅ Audit log registrado: {} - {} - {}", action, entityType, performedBy);

        } catch (Exception e) {
            logger.error("❌ Error al registrar audit log: {}", e.getMessage(), e);
        }
    }

    /**
     * Registra un log de auditoría con información completa (síncrono)
     */
    @Transactional
    public void logActionSync(String action, String entityType, Long entityId, Long performedByUserId,
            String performedBy, String details, String oldValues, String newValues) {
        try {
            AuditLog log = new AuditLog();
            log.setAction(action);
            log.setEventType(action);
            log.setEventDescription(details != null ? details : action);
            log.setEntityType(entityType);
            log.setEntityId(entityId);
            log.setPerformedBy(performedBy);
            log.setPerformedByUserId(performedByUserId);
            log.setDetails(details);
            log.setOldValues(oldValues);
            log.setNewValues(newValues);
            log.setIsSuccess(true);
            log.setSeverity("INFO");
            log.setStatus("SUCCESS");

            // Capturar IP y User-Agent
            try {
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder
                        .getRequestAttributes();
                if (attributes != null) {
                    HttpServletRequest request = attributes.getRequest();
                    log.setIpAddress(getClientIp(request));
                    log.setUserAgent(request.getHeader("User-Agent"));
                }
            } catch (Exception e) {
                logger.warn("No se pudo capturar información de la request: {}", e.getMessage());
            }

            auditLogRepository.save(log);
            logger.info("✅ Audit log registrado (sync): {} - {} - {}", action, entityType, performedBy);

        } catch (Exception e) {
            logger.error("❌ Error al registrar audit log: {}", e.getMessage(), e);
        }
    }

    /**
     * Registra un log de auditoría de acción fallida
     */
    @Async
    @Transactional
    public void logFailedAction(String action, String entityType, Long entityId, String errorMessage) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String performedBy = auth != null ? auth.getName() : "SYSTEM";

            AuditLog log = new AuditLog();
            log.setAction(action);
            log.setEventType(action);
            log.setEventDescription("Acción fallida: " + (errorMessage != null ? errorMessage : action));
            log.setEntityType(entityType);
            log.setEntityId(entityId);
            log.setPerformedBy(performedBy);
            log.setDetails("Acción fallida");
            log.setErrorMessage(errorMessage);
            log.setIsSuccess(false);
            log.setSeverity("ERROR");
            log.setStatus("FAILED");

            // Capturar IP y User-Agent
            try {
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder
                        .getRequestAttributes();
                if (attributes != null) {
                    HttpServletRequest request = attributes.getRequest();
                    log.setIpAddress(getClientIp(request));
                    log.setUserAgent(request.getHeader("User-Agent"));
                }
            } catch (Exception e) {
                logger.warn("No se pudo capturar información de la request: {}", e.getMessage());
            }

            auditLogRepository.save(log);
            logger.warn("⚠️ Audit log de fallo registrado: {} - {} - {}", action, entityType, performedBy);

        } catch (Exception e) {
            logger.error("❌ Error al registrar audit log de fallo: {}", e.getMessage(), e);
        }
    }

    /**
     * Obtener logs con filtros
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> getLogsWithFilters(String action, String entityType, String performedBy,
            Boolean isSuccess, LocalDateTime startDate, LocalDateTime endDate,
            Pageable pageable) {
        if (startDate == null) {
            startDate = LocalDateTime.now().minusMonths(1); // Por defecto: último mes
        }
        if (endDate == null) {
            endDate = LocalDateTime.now();
        }

        return auditLogRepository.findWithFilters(action, entityType, performedBy, isSuccess, startDate, endDate,
                pageable);
    }

    /**
     * Obtener logs de una entidad específica (para ver historial de cambios)
     */
    @Transactional(readOnly = true)
    public java.util.List<AuditLog> getEntityHistory(String entityType, Long entityId) {
        return auditLogRepository.findByEntityTypeAndEntityId(entityType, entityId);
    }

    // ==================== Métodos privados de soporte ====================

    /**
     * Serializa un objeto a JSON de forma segura.
     * Devuelve null si el valor es null; un JSON string válido en caso contrario.
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            logger.warn("No se pudo serializar el valor a JSON: {}", e.getMessage());
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    /**
     * Resuelve el nombre del usuario autenticado desde el SecurityContext.
     * Devuelve "SYSTEM" si no hay sesión activa.
     */
    private String resolvePerformedBy(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return "SYSTEM";
        }
        return auth.getName();
    }

    /**
     * Resuelve el ID numérico del usuario autenticado, si el principal es
     * UserPrincipal.
     */
    private Long resolveUserId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            return null;
        }
        return ((UserPrincipal) auth.getPrincipal()).getId();
    }

    /**
     * Construye una descripción legible del evento de auditoría.
     */
    private String buildDescription(String action, String eventType, String entityType,
            Long entityId, String performedBy, boolean isSuccess) {
        return String.format("[%s] %s sobre %s%s — por %s — %s",
                eventType,
                action,
                entityType,
                entityId != null ? " (ID:" + entityId + ")" : "",
                performedBy,
                isSuccess ? "ÉXITO" : "FALLO");
    }

    /**
     * Inyecta IP y User-Agent en el AuditLog leyendo el RequestContextHolder.
     * No lanza excepción si no hay request disponible (operaciones asíncronas o de
     * batch).
     */
    private void enrichWithRequestData(AuditLog entry) {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder
                    .getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                entry.setIpAddress(getClientIp(request));
                entry.setUserAgent(request.getHeader("User-Agent"));
            }
        } catch (Exception e) {
            logger.warn("No se pudo capturar información de la request para audit log: {}", e.getMessage());
        }
    }

    /**
     * Obtener la IP real del cliente (considerando proxies)
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // Si hay múltiples IPs (proxy chain), tomar la primera
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip;
    }

    // ==================== Métodos Helper para logs específicos
    // ====================

    /**
     * Log para creación de usuario
     */
    public void logUserCreation(Long userId, String userEmail, String createdBy) {
        logAction("CREATE_USER", "USER", userId,
                String.format("Usuario %s creado por %s", userEmail, createdBy));
    }

    /**
     * Log para actualización de usuario
     */
    public void logUserUpdate(Long userId, String userEmail, String updatedBy, String changes) {
        logAction("UPDATE_USER", "USER", userId,
                String.format("Usuario %s actualizado por %s. Cambios: %s", userEmail, updatedBy, changes));
    }

    /**
     * Log para desactivación de usuario
     */
    public void logUserDeactivation(Long userId, String userEmail, String deactivatedBy) {
        logAction("DEACTIVATE_USER", "USER", userId,
                String.format("Usuario %s desactivado por %s", userEmail, deactivatedBy));
    }

    /**
     * Log para activación de usuario
     */
    public void logUserActivation(Long userId, String userEmail, String activatedBy) {
        logAction("ACTIVATE_USER", "USER", userId,
                String.format("Usuario %s activado por %s", userEmail, activatedBy));
    }

    /**
     * Log para bloqueo de cuenta — severity WARNING por ser evento de seguridad
     */
    public void logAccountLock(Long userId, String userEmail, String lockedBy) {
        log("LOCK_ACCOUNT", "ACCOUNT_LOCK", "USER", userId, null, null, "WARNING", true);
        logger.warn("⚠️ Cuenta bloqueada: {} por {}", userEmail, lockedBy);
    }

    /**
     * Log para desbloqueo de cuenta
     */
    public void logAccountUnlock(Long userId, String userEmail, String unlockedBy) {
        logAction("UNLOCK_ACCOUNT", "USER", userId,
                String.format("Cuenta de usuario %s desbloqueada por %s", userEmail, unlockedBy));
    }

    /**
     * Log para asignación de roles
     */
    public void logRoleAssignment(Long userId, String userEmail, String roles, String assignedBy) {
        logAction("ASSIGN_ROLES", "USER", userId,
                String.format("Roles %s asignados al usuario %s por %s", roles, userEmail, assignedBy));
    }

    /**
     * Log para creación de rol
     */
    public void logRoleCreation(Long roleId, String roleName, String createdBy) {
        logAction("CREATE_ROLE", "ROLE", roleId,
                String.format("Rol %s creado por %s", roleName, createdBy));
    }

    /**
     * Log para actualización de permisos de rol
     */
    public void logRolePermissionsUpdate(Long roleId, String roleName, String permissions, String updatedBy) {
        logAction("UPDATE_ROLE_PERMISSIONS", "ROLE", roleId,
                String.format("Permisos del rol %s actualizados por %s. Permisos: %s", roleName, updatedBy,
                        permissions));
    }
}
