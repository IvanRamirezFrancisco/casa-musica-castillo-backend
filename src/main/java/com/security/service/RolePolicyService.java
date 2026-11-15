package com.security.service;

import com.security.entity.Role;
import com.security.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Servicio centralizado para políticas de roles, visibilidad y agrupación.
 * Trabaja en conjunto con AdminHierarchyService para evitar contradicciones.
 */
@Service
@RequiredArgsConstructor
public class RolePolicyService {

    private final AdminHierarchyService adminHierarchyService;

    public enum RoleScope {
        TECHNICAL,
        BUSINESS_MANAGER,
        OPERATIONAL,
        CUSTOMER,
        UNKNOWN
    }

    // ─────────────────────────────────────────────────────────
    //  Delegación directa a AdminHierarchyService (Fuente única de verdad)
    // ─────────────────────────────────────────────────────────

    public boolean isProtectedOwner(User user) {
        return adminHierarchyService.isProtectedOwner(user);
    }

    public int getHighestRoleLevel(User user) {
        return adminHierarchyService.getHighestRoleLevel(user);
    }

    public boolean canManageUser(User actor, User target) {
        try {
            adminHierarchyService.assertCanManageUser(actor, target);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Clasificación y Scopes
    // ─────────────────────────────────────────────────────────

    private static final Set<String> OWNER_ONLY_PERMISSIONS = Set.of(
            "SYSTEM_OWNER_MANAGE",
            "SUPER_ADMIN_MANAGE",
            "USER_ROLE_ASSIGN_SUPER_ADMIN",
            "USER_ROLE_REMOVE_SUPER_ADMIN",
            "USER_DISABLE_SUPER_ADMIN",
            "USER_DELETE_SUPER_ADMIN"
    );

    private static final Set<String> CRITICAL_PERMISSIONS = Set.of(
            "ROLE_SYSTEM_UPDATE",
            "ROLE_SYSTEM_DELETE",
            "PERMISSION_CRITICAL_ASSIGN",
            "DATABASE_DROP",
            "DATABASE_RESTORE"
    );

    public boolean isOwnerOnlyPermission(String permission) {
        return permission != null && OWNER_ONLY_PERMISSIONS.contains(permission);
    }

    public boolean isCriticalPermission(String permission) {
        return permission != null && CRITICAL_PERMISSIONS.contains(permission);
    }

    public void assertCanAssignPermissions(User actor, Set<String> permissionNames) {
        boolean isProtectedOwner = isProtectedOwner(actor);
        
        for (String perm : permissionNames) {
            if (isOwnerOnlyPermission(perm) && !isProtectedOwner) {
                throw new com.security.exception.SecurityHierarchyException(
                        "No tienes permiso para gestionar permisos de nivel Owner: " + perm);
            }
            if (isCriticalPermission(perm) && !isProtectedOwner) {
                throw new com.security.exception.SecurityHierarchyException(
                        "No tienes permiso para gestionar permisos críticos: " + perm);
            }
        }
    }

    public RoleScope getRoleScope(String roleName) {
        if (roleName == null) return RoleScope.UNKNOWN;
        return switch (roleName) {
            case "ROLE_SUPER_ADMIN", "ROLE_ADMIN", "ROLE_PROJECT_ADMIN" -> RoleScope.TECHNICAL;
            case "ROLE_STORE_MANAGER" -> RoleScope.BUSINESS_MANAGER;
            case "ROLE_STORE_STAFF", "ROLE_CATALOG_MANAGER", "ROLE_ORDER_MANAGER", "ROLE_PAYMENT_ASSISTANT" -> RoleScope.OPERATIONAL;
            case "ROLE_USER" -> RoleScope.CUSTOMER;
            default -> RoleScope.UNKNOWN;
        };
    }

    public boolean isTechnicalRole(String roleName) {
        return getRoleScope(roleName) == RoleScope.TECHNICAL;
    }

    public boolean isOperationalRole(String roleName) {
        return getRoleScope(roleName) == RoleScope.OPERATIONAL;
    }

    public boolean isTechnicalUser(User user) {
        if (user == null || user.getRoles() == null) return false;
        return user.getRoles().stream().anyMatch(r -> isTechnicalRole(r.getName()));
    }

    public boolean isOperationalUser(User user) {
        if (user == null || user.getRoles() == null) return false;
        // Consideramos operativo si tiene algún rol operativo y NO tiene roles técnicos o managers
        boolean hasOperational = user.getRoles().stream().anyMatch(r -> isOperationalRole(r.getName()));
        boolean hasTechnicalOrManager = user.getRoles().stream().anyMatch(r -> 
            isTechnicalRole(r.getName()) || getRoleScope(r.getName()) == RoleScope.BUSINESS_MANAGER);
        
        return hasOperational && !hasTechnicalOrManager;
    }

    public boolean isStoreManager(User user) {
        if (user == null || user.getRoles() == null) return false;
        return user.getRoles().stream().anyMatch(r -> "ROLE_STORE_MANAGER".equals(r.getName()));
    }

    public boolean isOwnerLevel(User user) {
        if (user == null) return false;
        return isProtectedOwner(user) && user.getRoles().stream().anyMatch(r -> "ROLE_SUPER_ADMIN".equals(r.getName()));
    }

    // ─────────────────────────────────────────────────────────
    //  Visibilidad de Usuarios
    // ─────────────────────────────────────────────────────────

    /**
     * Determina si el 'actor' tiene permiso para VER al 'target' en el listado técnico (/api/admin/staff).
     */
    public boolean canViewUserTechnical(User actor, User target) {
        if (actor == null || target == null) return false;
        if (adminHierarchyService.isSameUser(actor, target)) return true;

        boolean actorIsProtected = isProtectedOwner(actor);
        boolean targetIsProtected = isProtectedOwner(target);

        // Nadie ve al Protected Owner excepto él mismo.
        if (targetIsProtected && !actorIsProtected) {
            return false;
        }

        // STORE_MANAGER solo ve usuarios operativos puros
        if (isStoreManager(actor) && !actorIsProtected && !isTechnicalUser(actor)) {
             return isOperationalUser(target) && !isProtectedOwner(target);
        }
        
        // Un admin no ve a otro super admin, pero sí puede ver inferiores
        // La vista es abierta para inferiores, y entre iguales se decide por la política.
        return true; 
    }

    /**
     * Determina si el 'actor' tiene permiso para VER al 'target' en el listado operativo (/api/admin/store-staff).
     */
    public boolean canViewUserOperational(User actor, User target) {
        if (actor == null || target == null) return false;
        if (adminHierarchyService.isSameUser(actor, target)) return true;

        boolean actorIsProtected = isProtectedOwner(actor);
        boolean targetIsProtected = isProtectedOwner(target);

        if (targetIsProtected && !actorIsProtected) {
            return false;
        }

        // Si el target es técnico (o manager) y el actor es solo manager u operativo, no se ve.
        if ((isTechnicalUser(target) || isStoreManager(target)) && !actorIsProtected) {
            if (isStoreManager(actor)) return false; // El manager no ve a técnicos ni a otros managers
        }
        
        return true;
    }

    // ─────────────────────────────────────────────────────────
    //  Roles Asignables
    // ─────────────────────────────────────────────────────────

    /**
     * Retorna los roles que un actor puede asignar en el contexto TÉCNICO.
     */
    public List<Role> getAssignableTechnicalRoles(User actor, List<Role> allRoles) {
        boolean isOwner = isProtectedOwner(actor);
        int actorLevel = getHighestRoleLevel(actor);

        return allRoles.stream()
                .filter(role -> {
                    // Protected owner asigna lo que quiera
                    if (isOwner) return true;
                    
                    // Nadie más puede asignar SUPER_ADMIN
                    if ("ROLE_SUPER_ADMIN".equals(role.getName())) return false;
                    
                    // Un no-owner no puede asignar roles >= a su propio nivel
                    int roleLevel = adminHierarchyService.getRoleLevel(role);
                    return actorLevel > roleLevel;
                })
                .collect(Collectors.toList());
    }

    /**
     * Retorna los roles que un actor puede asignar en el contexto OPERATIVO (tienda).
     */
    public List<Role> getAssignableOperationalRoles(User actor, List<Role> allRoles) {
        int actorLevel = getHighestRoleLevel(actor);
        boolean isOwner = isProtectedOwner(actor);

        return allRoles.stream()
                .filter(role -> {
                    // Solo roles puramente operativos
                    if (getRoleScope(role.getName()) != RoleScope.OPERATIONAL) return false;
                    
                    if (isOwner) return true;
                    
                    int roleLevel = adminHierarchyService.getRoleLevel(role);
                    return actorLevel > roleLevel;
                })
                .collect(Collectors.toList());
    }
}
