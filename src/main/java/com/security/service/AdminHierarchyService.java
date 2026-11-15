package com.security.service;

import com.security.config.ProtectedOwnerProperties;
import com.security.entity.Role;
import com.security.entity.User;
import com.security.exception.SecurityHierarchyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Servicio central que gobierna la jerarquía de roles y la protección absoluta del
 * Protected Owner.
 *
 * Modelo de seguridad:
 *   PROTECTED_OWNER (identidad, no rol) > ROLE_SUPER_ADMIN (100) > ROLE_ADMIN (80)
 *   > ROLE_STORE_MANAGER (50) > ROLE_USER (10)
 *
 * Reglas absolutas:
 *   A. Si target es PROTECTED_OWNER y actor NO es el mismo usuario → BLOQUEAR SIEMPRE.
 *      No importa si el actor tiene ROLE_SUPER_ADMIN, SYSTEM_OWNER_MANAGE u otro permiso.
 *
 *   B. Si actor es PROTECTED_OWNER y target NO es PROTECTED_OWNER → PERMITIR siempre.
 *      El propietario puede modificar, degradar, deshabilitar o eliminar a cualquier
 *      usuario no protegido, incluyendo otros SUPER_ADMINs.
 *
 *   C. Si actor NO es PROTECTED_OWNER → aplicar jerarquía normal.
 *      No puede modificar usuarios de igual o mayor nivel, ni al Protected Owner.
 *
 *   D. El PROTECTED_OWNER no puede autoeliminarse, autodeshabilitarse ni
 *      quitarse ROLE_SUPER_ADMIN.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminHierarchyService {

    private final ProtectedOwnerProperties protectedOwnerProperties;

    // ─────────────────────────────────────────────────────────
    //  Constantes de nivel de jerarquía
    // ─────────────────────────────────────────────────────────

    public static final int LEVEL_SUPER_ADMIN    = 100;
    public static final int LEVEL_ADMIN          = 80;
    public static final int LEVEL_PROJECT_ADMIN  = 80;
    public static final int LEVEL_STORE_MANAGER  = 50;
    public static final int LEVEL_USER           = 10;

    // ─────────────────────────────────────────────────────────
    //  Identificación del Protected Owner
    // ─────────────────────────────────────────────────────────

    /**
     * Devuelve true si el usuario coincide con alguno de los correos protegidos
     * definidos en la configuración.
     */
    public boolean isProtectedOwner(User user) {
        if (user == null || user.getEmail() == null) {
            return false;
        }
        List<String> emails = protectedOwnerProperties.getProtectedOwnerEmails();
        if (emails == null || emails.isEmpty()) {
            return false;
        }
        String userEmail = user.getEmail().trim().toLowerCase();
        return emails.stream()
                .map(e -> e.trim().toLowerCase())
                .anyMatch(e -> e.equals(userEmail));
    }

    /**
     * Devuelve el nivel de jerarquía más alto que posee el usuario.
     */
    public int getHighestRoleLevel(User user) {
        if (user == null || user.getRoles() == null || user.getRoles().isEmpty()) {
            return 0;
        }
        return user.getRoles().stream()
                .mapToInt(this::getRoleLevel)
                .max()
                .orElse(0);
    }

    /**
     * Devuelve el nivel numérico de un rol por su nombre.
     */
    public int getRoleLevel(Role role) {
        if (role == null || role.getName() == null) return 0;
        return switch (role.getName()) {
            case "ROLE_SUPER_ADMIN"   -> LEVEL_SUPER_ADMIN;
            case "ROLE_ADMIN"         -> LEVEL_ADMIN;
            case "ROLE_PROJECT_ADMIN" -> LEVEL_PROJECT_ADMIN;
            case "ROLE_STORE_MANAGER" -> LEVEL_STORE_MANAGER;
            case "ROLE_STORE_STAFF", "ROLE_CATALOG_MANAGER", "ROLE_ORDER_MANAGER", "ROLE_PAYMENT_ASSISTANT" -> 30;
            case "ROLE_USER"          -> LEVEL_USER;
            default                   -> 0;
        };
    }

    /**
     * Comprueba si actor y target son el mismo usuario (por ID).
     */
    public boolean isSameUser(User actor, User target) {
        if (actor == null || target == null) return false;
        return actor.getId() != null && actor.getId().equals(target.getId());
    }

    // ─────────────────────────────────────────────────────────
    //  Auditoría segura interna
    // ─────────────────────────────────────────────────────────

    private void auditBlocked(User actor, User target, String reason) {
        String actorId    = actor  != null && actor.getId()  != null ? actor.getId().toString()  : "UNKNOWN";
        String targetId   = target != null && target.getId() != null ? target.getId().toString() : "UNKNOWN";
        String actorEmail = actor  != null ? actor.getEmail()  : "UNKNOWN";
        log.warn("[SECURITY_BLOCKED] reason={} actorId={} actorEmail={} targetId={}",
                reason, actorId, actorEmail, targetId);
    }

    private void auditBlocked(User actor, String targetDesc, String reason) {
        String actorId    = actor != null && actor.getId() != null ? actor.getId().toString() : "UNKNOWN";
        String actorEmail = actor != null ? actor.getEmail() : "UNKNOWN";
        log.warn("[SECURITY_BLOCKED] reason={} actorId={} actorEmail={} target={}",
                reason, actorId, actorEmail, targetDesc);
    }

    // ─────────────────────────────────────────────────────────
    //  REGLA CENTRAL: ¿Puede 'actor' administrar a 'target'?
    // ─────────────────────────────────────────────────────────

    /**
     * Regla Central de Gestión de Usuario.
     *
     * Implementa el modelo de seguridad completo:
     *   - PROTECTED_OWNER es inviolable por cualquier otro usuario.
     *   - PROTECTED_OWNER puede administrar a cualquier usuario no protegido.
     *   - Usuarios no protegidos aplican jerarquía de niveles.
     */
    public void assertCanManageUser(User actor, User target) {
        if (actor == null || target == null) {
            throw new SecurityHierarchyException("Usuario inválido para validación de jerarquía.");
        }

        boolean actorIsProtectedOwner  = isProtectedOwner(actor);
        boolean targetIsProtectedOwner = isProtectedOwner(target);
        boolean sameUser               = isSameUser(actor, target);

        // ── REGLA A: El Protected Owner es inviolable por cualquier otro usuario ──
        // No importa si actor tiene ROLE_SUPER_ADMIN, SYSTEM_OWNER_MANAGE u otro permiso.
        if (targetIsProtectedOwner && !sameUser) {
            auditBlocked(actor, target, "ATTEMPT_TO_MANAGE_PROTECTED_OWNER");
            throw new SecurityHierarchyException(
                    "No se puede modificar la cuenta del propietario técnico del sistema.");
        }

        // ── REGLA B: El Protected Owner puede administrar a cualquier usuario no protegido ──
        if (actorIsProtectedOwner && !targetIsProtectedOwner) {
            // Permitido sin restricciones de nivel — el propietario manda sobre todos.
            return;
        }

        // ── REGLA D (autogestión del Protected Owner): Validaciones de integridad del sistema ──
        // La autogestión peligrosa se valida en métodos específicos (assertCanDisableUser, etc.)
        // El assertCanManageUser genérico permite automodificación de datos personales seguros.
        if (actorIsProtectedOwner && sameUser) {
            // La autogestión no peligrosa es permitida.
            return;
        }

        // ── REGLA C: Jerarquía normal para usuarios no protegidos ──
        if (!sameUser) {
            int actorLevel  = getHighestRoleLevel(actor);
            int targetLevel = getHighestRoleLevel(target);

            if (actorLevel < targetLevel) {
                auditBlocked(actor, target, "INSUFFICIENT_HIERARCHY_LEVEL");
                throw new SecurityHierarchyException(
                        "No tienes la jerarquía necesaria para modificar a este usuario.");
            }
            if (actorLevel == targetLevel) {
                // Un SUPER_ADMIN no protegido NO puede modificar a otro SUPER_ADMIN.
                // (El Protected Owner ya fue procesado arriba con REGLA B.)
                auditBlocked(actor, target, "INSUFFICIENT_HIERARCHY_LEVEL");
                throw new SecurityHierarchyException(
                        "No puedes modificar a un usuario del mismo nivel jerárquico que el tuyo.");
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Asignación y remoción de roles
    // ─────────────────────────────────────────────────────────

    /**
     * Valida si 'actor' puede asignar 'roleToAssign' a 'target'.
     *
     * Reglas adicionales:
     *   - Si actor es PROTECTED_OWNER → puede asignar cualquier rol a cualquier usuario no protegido.
     *   - Si actor NO es PROTECTED_OWNER → no puede asignar roles de nivel >= al suyo.
     */
    public void assertCanAssignRole(User actor, User target, Role roleToAssign) {
        // Primera barrera: ¿puede actor gestionar a target?
        assertCanManageUser(actor, target);

        // El Protected Owner puede asignar cualquier rol a usuarios no protegidos.
        if (isProtectedOwner(actor) && !isProtectedOwner(target)) {
            return;
        }

        // Usuarios no protegidos no pueden asignar roles de nivel >= al suyo.
        int actorLevel = getHighestRoleLevel(actor);
        int roleLevel  = getRoleLevel(roleToAssign);

        if (actorLevel < roleLevel) {
            auditBlocked(actor, target.getEmail(), "ATTEMPT_TO_ASSIGN_SUPERIOR_ROLE[" + roleToAssign.getName() + "]");
            throw new SecurityHierarchyException(
                    "No puedes asignar un rol con mayor jerarquía que la tuya.");
        }
        if (actorLevel == roleLevel) {
            // Un SUPER_ADMIN no protegido no puede asignar ROLE_SUPER_ADMIN.
            auditBlocked(actor, target.getEmail(), "ATTEMPT_TO_ASSIGN_SUPER_ADMIN_WITHOUT_OWNER_AUTHORITY");
            throw new SecurityHierarchyException(
                    "No puedes asignar un rol del mismo nivel jerárquico que el tuyo. Solo el propietario del sistema puede asignar ROLE_SUPER_ADMIN.");
        }
    }

    /**
     * Valida si 'actor' puede remover 'roleToRemove' de 'target'.
     *
     * Reglas adicionales:
     *   - Nadie puede quitar ROLE_SUPER_ADMIN al Protected Owner, ni siquiera él mismo.
     *   - Si actor es PROTECTED_OWNER → puede remover cualquier rol de cualquier usuario no protegido.
     *   - Si actor NO es PROTECTED_OWNER → no puede remover roles de nivel > su propio nivel.
     */
    public void assertCanRemoveRole(User actor, User target, Role roleToRemove) {
        boolean targetIsProtectedOwner = isProtectedOwner(target);
        boolean sameUser               = isSameUser(actor, target);

        // ── REGLA B especial: Nadie puede quitar ROLE_SUPER_ADMIN al Protected Owner ──
        // Esto incluye al propio Protected Owner (protección de integridad del sistema).
        if (targetIsProtectedOwner && "ROLE_SUPER_ADMIN".equals(roleToRemove.getName())) {
            auditBlocked(actor, target, "ATTEMPT_TO_REMOVE_SUPER_ADMIN_FROM_PROTECTED_OWNER");
            throw new SecurityHierarchyException(
                    "No se puede quitar ROLE_SUPER_ADMIN al propietario del sistema bajo ninguna circunstancia.");
        }

        // Primera barrera: ¿puede actor gestionar a target?
        assertCanManageUser(actor, target);

        // El Protected Owner puede remover cualquier rol de usuarios no protegidos.
        if (isProtectedOwner(actor) && !targetIsProtectedOwner) {
            return;
        }

        // Usuarios no protegidos no pueden remover roles de nivel > su propio nivel.
        int actorLevel = getHighestRoleLevel(actor);
        int roleLevel  = getRoleLevel(roleToRemove);

        if (actorLevel < roleLevel) {
            auditBlocked(actor, target.getEmail(), "INSUFFICIENT_HIERARCHY_LEVEL_TO_REMOVE_ROLE[" + roleToRemove.getName() + "]");
            throw new SecurityHierarchyException(
                    "No puedes remover un rol con mayor jerarquía que la tuya.");
        }
        if (actorLevel == roleLevel) {
            // Un SUPER_ADMIN no protegido no puede quitar ROLE_SUPER_ADMIN a otro SUPER_ADMIN.
            auditBlocked(actor, target.getEmail(), "ATTEMPT_TO_REMOVE_SUPER_ADMIN_WITHOUT_OWNER_AUTHORITY");
            throw new SecurityHierarchyException(
                    "No puedes remover un rol del mismo nivel jerárquico que el tuyo.");
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Operaciones críticas con validaciones de integridad
    // ─────────────────────────────────────────────────────────

    /**
     * Valida si 'actor' puede deshabilitar a 'target'.
     *
     * El Protected Owner no puede ser deshabilitado por nadie.
     * El Protected Owner no puede autodeshabilitarse (integridad del sistema).
     */
    public void assertCanDisableUser(User actor, User target) {
        if (isProtectedOwner(target)) {
            // Bloquear siempre, incluso si actor == target (autodeshabilitación)
            auditBlocked(actor, target, "ATTEMPT_TO_DISABLE_PROTECTED_OWNER");
            throw new SecurityHierarchyException(
                    "La cuenta del propietario del sistema no puede ser deshabilitada.");
        }
        assertCanManageUser(actor, target);
    }

    /**
     * Valida si 'actor' puede eliminar a 'target'.
     *
     * El Protected Owner no puede ser eliminado por nadie.
     * El Protected Owner no puede autoeliminarse (integridad del sistema).
     */
    public void assertCanDeleteUser(User actor, User target) {
        if (isProtectedOwner(target)) {
            // Bloquear siempre, incluso si actor == target (autoeliminación)
            auditBlocked(actor, target, "ATTEMPT_TO_DELETE_PROTECTED_OWNER");
            throw new SecurityHierarchyException(
                    "La cuenta del propietario del sistema no puede ser eliminada.");
        }
        assertCanManageUser(actor, target);
    }

    /**
     * Valida si 'actor' puede resetear el 2FA de 'target' desde un flujo administrativo.
     *
     * El Protected Owner no puede tener su 2FA reseteado desde administración por nadie externo.
     */
    public void assertCanResetTwoFactor(User actor, User target) {
        if (isProtectedOwner(target) && !isSameUser(actor, target)) {
            auditBlocked(actor, target, "ATTEMPT_TO_RESET_2FA_OF_PROTECTED_OWNER");
            throw new SecurityHierarchyException(
                    "El 2FA del propietario del sistema no puede ser reseteado desde administración.");
        }
        assertCanManageUser(actor, target);
    }

    /**
     * Valida si 'actor' puede cambiar la contraseña de 'target' desde un flujo administrativo.
     *
     * El Protected Owner no puede tener su contraseña cambiada por nadie externo.
     */
    public void assertCanChangePasswordAdmin(User actor, User target) {
        if (isProtectedOwner(target) && !isSameUser(actor, target)) {
            auditBlocked(actor, target, "ATTEMPT_TO_CHANGE_PASSWORD_OF_PROTECTED_OWNER");
            throw new SecurityHierarchyException(
                    "La contraseña del propietario del sistema no puede ser cambiada desde administración.");
        }
        assertCanManageUser(actor, target);
    }

    /**
     * Valida si 'actor' puede cambiar el email de 'target' desde un flujo administrativo.
     *
     * El Protected Owner no puede tener su email cambiado por nadie externo.
     */
    public void assertCanChangeEmailAdmin(User actor, User target) {
        if (isProtectedOwner(target) && !isSameUser(actor, target)) {
            auditBlocked(actor, target, "ATTEMPT_TO_CHANGE_EMAIL_OF_PROTECTED_OWNER");
            throw new SecurityHierarchyException(
                    "El email del propietario del sistema no puede ser cambiado desde administración.");
        }
        assertCanManageUser(actor, target);
    }

    // ─────────────────────────────────────────────────────────
    //  Protección de roles del sistema
    // ─────────────────────────────────────────────────────────

    /**
     * Valida si 'actor' puede modificar los permisos de un rol del sistema.
     *
     * Solo el Protected Owner puede modificar roles de sistema.
     * Otro SUPER_ADMIN no protegido NO puede hacerlo.
     */
    public void assertCanModifyRole(User actor, Role targetRole) {
        if (targetRole.getIsSystemRole() != null && targetRole.getIsSystemRole()) {
            if (!isProtectedOwner(actor)) {
                auditBlocked(actor, "ROLE:" + targetRole.getName(), "ATTEMPT_TO_MODIFY_SYSTEM_ROLE");
                throw new SecurityHierarchyException(
                        "Solo el propietario técnico del sistema puede modificar roles del sistema.");
            }
        }
    }

    /**
     * Valida si 'actor' puede eliminar un rol del sistema.
     *
     * Los roles del sistema nunca pueden ser eliminados.
     */
    public void assertCanDeleteRole(User actor, Role targetRole) {
        if (targetRole.getIsSystemRole() != null && targetRole.getIsSystemRole()) {
            auditBlocked(actor, "ROLE:" + targetRole.getName(), "ATTEMPT_TO_DELETE_SYSTEM_ROLE");
            throw new SecurityHierarchyException(
                    "Los roles del sistema no pueden ser eliminados.");
        }
    }
}
