package com.security.service;

import com.security.entity.User;
import com.security.entity.Role;
import com.security.repository.UserRepository;
import com.security.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Servicio de Control de Acceso Basado en Roles (RBAC) reforzado
 * con auditoría y políticas de seguridad avanzadas
 */
@Service
public class RBACService {

    private static final Logger logger = LoggerFactory.getLogger(RBACService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SecureLoggingService loggingService;

    // Definición de recursos y acciones del sistema
    private static final Map<String, Set<String>> RESOURCE_ACTIONS = Map.of(
            "USER_PROFILE", Set.of("READ", "UPDATE", "DELETE"),
            "ADMIN_PANEL", Set.of("READ", "CREATE", "UPDATE", "DELETE"),
            "REPORTS", Set.of("READ", "GENERATE", "EXPORT"),
            "SYSTEM_CONFIG", Set.of("READ", "UPDATE"),
            "USER_MANAGEMENT", Set.of("READ", "CREATE", "UPDATE", "DELETE", "SUSPEND"),
            "PRODUCT_CATALOG", Set.of("READ", "CREATE", "UPDATE", "DELETE"),
            "ORDERS", Set.of("READ", "CREATE", "UPDATE", "CANCEL"),
            "PAYMENTS", Set.of("READ", "PROCESS", "REFUND"),
            "SECURITY_LOGS", Set.of("READ", "EXPORT"),
            "TWO_FACTOR", Set.of("READ", "SETUP", "DISABLE"));

    // Matriz de permisos por rol
    private static final Map<String, Map<String, Set<String>>> ROLE_PERMISSIONS = Map.of(
            "ROLE_USER", Map.of(
                    "USER_PROFILE", Set.of("READ", "UPDATE"),
                    "PRODUCT_CATALOG", Set.of("READ"),
                    "ORDERS", Set.of("READ", "CREATE"),
                    "TWO_FACTOR", Set.of("READ", "SETUP", "DISABLE")),
            "ROLE_PREMIUM_USER", Map.of(
                    "USER_PROFILE", Set.of("READ", "UPDATE"),
                    "PRODUCT_CATALOG", Set.of("READ"),
                    "ORDERS", Set.of("READ", "CREATE", "UPDATE"),
                    "REPORTS", Set.of("READ"),
                    "TWO_FACTOR", Set.of("READ", "SETUP", "DISABLE")),
            "ROLE_MODERATOR", Map.of(
                    "USER_PROFILE", Set.of("READ", "UPDATE"),
                    "PRODUCT_CATALOG", Set.of("READ", "UPDATE"),
                    "ORDERS", Set.of("READ", "UPDATE", "CANCEL"),
                    "USER_MANAGEMENT", Set.of("READ", "UPDATE"),
                    "REPORTS", Set.of("READ", "GENERATE"),
                    "TWO_FACTOR", Set.of("READ", "SETUP", "DISABLE")),
            "ROLE_ADMIN", Map.of(
                    "USER_PROFILE", Set.of("READ", "UPDATE", "DELETE"),
                    "ADMIN_PANEL", Set.of("READ", "CREATE", "UPDATE", "DELETE"),
                    "PRODUCT_CATALOG", Set.of("READ", "CREATE", "UPDATE", "DELETE"),
                    "USER_MANAGEMENT", Set.of("READ", "CREATE", "UPDATE", "DELETE", "SUSPEND"),
                    "ORDERS", Set.of("READ", "CREATE", "UPDATE", "CANCEL"),
                    "PAYMENTS", Set.of("READ", "PROCESS", "REFUND"),
                    "REPORTS", Set.of("READ", "GENERATE", "EXPORT"),
                    "SYSTEM_CONFIG", Set.of("READ", "UPDATE"),
                    "SECURITY_LOGS", Set.of("READ", "EXPORT"),
                    "TWO_FACTOR", Set.of("READ", "SETUP", "DISABLE")),
            "ROLE_SUPER_ADMIN", Map.of(
                    "USER_PROFILE", Set.of("READ", "UPDATE", "DELETE"),
                    "ADMIN_PANEL", Set.of("READ", "CREATE", "UPDATE", "DELETE"),
                    "PRODUCT_CATALOG", Set.of("READ", "CREATE", "UPDATE", "DELETE"),
                    "USER_MANAGEMENT", Set.of("READ", "CREATE", "UPDATE", "DELETE", "SUSPEND"),
                    "ORDERS", Set.of("READ", "CREATE", "UPDATE", "CANCEL"),
                    "PAYMENTS", Set.of("READ", "PROCESS", "REFUND"),
                    "REPORTS", Set.of("READ", "GENERATE", "EXPORT"),
                    "SYSTEM_CONFIG", Set.of("READ", "UPDATE"),
                    "SECURITY_LOGS", Set.of("READ", "EXPORT"),
                    "TWO_FACTOR", Set.of("READ", "SETUP", "DISABLE")));

    /**
     * Verifica si un usuario tiene permiso para realizar una acción en un recurso
     */
    public boolean hasPermission(Authentication authentication, String resource, String action) {
        return hasPermission(authentication, resource, action, null);
    }

    /**
     * Verifica permisos con contexto adicional (ej: ownership)
     */
    public boolean hasPermission(Authentication authentication, String resource, String action, String context) {
        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                logAccessDenied(null, resource, action, "Not authenticated");
                return false;
            }

            String userId = getUserIdFromAuthentication(authentication);
            String clientIp = getCurrentClientIp(); // Implementar según contexto

            // Verificar si el usuario está activo
            Optional<User> userOpt = userRepository.findByIdWithRoles(Long.valueOf(userId));
            if (userOpt.isEmpty() || !userOpt.get().isEnabled()) {
                logAccessDenied(userId, resource, action, "User inactive or not found");
                return false;
            }

            User user = userOpt.get();

            // Verificar permisos basados en roles
            Set<String> userRoles = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());

            boolean hasRolePermission = checkRolePermissions(userRoles, resource, action);

            // Verificar contexto específico (ej: ownership, temporal constraints)
            boolean hasContextualPermission = checkContextualPermissions(user, resource, action, context);

            boolean granted = hasRolePermission && hasContextualPermission;

            // Log del evento de autorización
            loggingService.logAuthorizationEvent(userId, resource, action, clientIp, granted);

            if (!granted) {
                logAccessDenied(userId, resource, action, "Insufficient permissions");
            }

            return granted;

        } catch (Exception e) {
            logger.error("Error checking permissions: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verifica permisos basados en roles
     */
    private boolean checkRolePermissions(Set<String> userRoles, String resource, String action) {
        for (String role : userRoles) {
            Map<String, Set<String>> rolePermissions = ROLE_PERMISSIONS.get(role);
            if (rolePermissions != null) {
                Set<String> allowedActions = rolePermissions.get(resource);
                if (allowedActions != null && allowedActions.contains(action)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Verifica permisos contextuales (ownership, tiempo, etc.)
     */
    private boolean checkContextualPermissions(User user, String resource, String action, String context) {
        // Verificar ownership para recursos de perfil de usuario
        if ("USER_PROFILE".equals(resource) && context != null) {
            try {
                Long targetUserId = Long.valueOf(context);
                if (!user.getId().equals(targetUserId) && !isAdmin(user)) {
                    return false; // Solo puede modificar su propio perfil o ser admin
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // Verificar restricciones temporales
        if (!isWithinAllowedTimeWindow(resource, action)) {
            return false;
        }

        // Verificar límites de frecuencia para acciones críticas
        if (isCriticalAction(resource, action) && !checkRateLimit(user.getId(), resource, action)) {
            return false;
        }

        return true;
    }

    /**
     * Verifica si el usuario es administrador
     */
    private boolean isAdmin(User user) {
        return user.getRoles().stream()
                .anyMatch(role -> "ROLE_ADMIN".equals(role.getName()) ||
                        "ROLE_SUPER_ADMIN".equals(role.getName()));
    }

    /**
     * Verifica ventana de tiempo permitida para ciertas acciones
     */
    private boolean isWithinAllowedTimeWindow(String resource, String action) {
        // Ejemplo: Transferencias solo durante horario comercial
        if ("PAYMENTS".equals(resource) && "PROCESS".equals(action)) {
            LocalDateTime now = LocalDateTime.now();
            int hour = now.getHour();
            // Horario comercial: 8:00 - 22:00
            return hour >= 8 && hour <= 22;
        }

        // Por defecto, permitir todas las horas
        return true;
    }

    /**
     * Verifica si es una acción crítica que requiere rate limiting
     */
    private boolean isCriticalAction(String resource, String action) {
        return ("PAYMENTS".equals(resource) && "PROCESS".equals(action)) ||
                ("USER_MANAGEMENT".equals(resource) && "DELETE".equals(action)) ||
                ("SYSTEM_CONFIG".equals(resource) && "UPDATE".equals(action));
    }

    /**
     * Verifica límites de frecuencia para acciones críticas
     */
    private boolean checkRateLimit(Long userId, String resource, String action) {
        // Implementar lógica de rate limiting con Redis
        // Por ahora, una implementación básica en memoria
        String key = userId + ":" + resource + ":" + action;
        // Aquí implementarías la lógica de rate limiting real
        return true; // Placeholder
    }

    /**
     * Obtiene todos los permisos de un usuario
     */
    public Map<String, Set<String>> getUserPermissions(Authentication authentication) {
        Map<String, Set<String>> permissions = new HashMap<>();

        if (authentication == null || !authentication.isAuthenticated()) {
            return permissions;
        }

        Set<String> userRoles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        for (String role : userRoles) {
            Map<String, Set<String>> rolePermissions = ROLE_PERMISSIONS.get(role);
            if (rolePermissions != null) {
                for (Map.Entry<String, Set<String>> entry : rolePermissions.entrySet()) {
                    permissions.computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                            .addAll(entry.getValue());
                }
            }
        }

        return permissions;
    }

    /**
     * Verifica si un usuario puede acceder a un recurso
     */
    public boolean canAccessResource(Authentication authentication, String resource) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        Set<String> userRoles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        for (String role : userRoles) {
            Map<String, Set<String>> rolePermissions = ROLE_PERMISSIONS.get(role);
            if (rolePermissions != null && rolePermissions.containsKey(resource)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Actualiza los roles de un usuario (solo administradores)
     */
    public boolean updateUserRoles(Authentication authentication, Long targetUserId, Set<String> newRoles) {
        try {
            if (!hasPermission(authentication, "USER_MANAGEMENT", "UPDATE")) {
                return false;
            }

            Optional<User> userOpt = userRepository.findByIdWithRoles(targetUserId);
            if (userOpt.isEmpty()) {
                return false;
            }

            User user = userOpt.get();
            String adminId = getUserIdFromAuthentication(authentication);

            // Validar que los roles existen
            Set<Role> roles = new HashSet<>();
            for (String roleNameStr : newRoles) {
                Optional<Role> roleOpt = roleRepository.findByName(roleNameStr);
                if (roleOpt.isEmpty()) {
                    logger.warn("Role not found: {}", roleNameStr);
                    return false;
                }
                roles.add(roleOpt.get());
            }

            // Actualizar roles
            Set<String> oldRoles = user.getRoles().stream()
                    .map(role -> role.getName())
                    .collect(Collectors.toSet());

            user.setRoles(roles);
            userRepository.save(user);

            // Log del cambio
            loggingService.logSensitiveDataChange(adminId, "USER_ROLES", "UPDATE",
                    oldRoles.toString(), newRoles.toString(), getCurrentClientIp());

            logger.info("User roles updated for user {} by admin {}", targetUserId, adminId);
            return true;

        } catch (Exception e) {
            logger.error("Error updating user roles: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Obtiene el ID del usuario desde la autenticación
     */
    private String getUserIdFromAuthentication(Authentication authentication) {
        if (authentication.getPrincipal() instanceof User user) {
            return user.getId().toString();
        }
        // Fallback si es String
        return authentication.getName();
    }

    /**
     * Obtiene la IP del cliente actual (simplificado)
     */
    private String getCurrentClientIp() {
        // Implementar según el contexto de la request
        return "unknown";
    }

    /**
     * Log de acceso denegado
     */
    private void logAccessDenied(String userId, String resource, String action, String reason) {
        logger.warn("Access denied - User: {}, Resource: {}, Action: {}, Reason: {}",
                userId, resource, action, reason);

        loggingService.logSecurityEvent("ACCESS_DENIED",
                String.format("Resource: %s, Action: %s, Reason: %s", resource, action, reason),
                userId, getCurrentClientIp(), "MEDIUM");
    }
}