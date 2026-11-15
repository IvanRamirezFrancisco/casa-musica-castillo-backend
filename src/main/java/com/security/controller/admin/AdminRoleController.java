package com.security.controller.admin;

import com.security.dto.admin.PermissionDTO;
import com.security.dto.admin.AdminUserListDTO;
import com.security.dto.admin.RoleCreateDTO;
import com.security.dto.admin.RoleResponseDTO;
import com.security.dto.admin.RoleUpdatePermissionsDTO;
import com.security.service.admin.AdminRoleService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controlador REST para gestión de Roles y Permisos del sistema.
 *
 * Reglas de seguridad aplicadas:
 * - Lectura: ROLE_ADMIN o ROLE_SUPER_ADMIN.
 * - Escritura (crear, modificar, eliminar): exclusivamente ROLE_SUPER_ADMIN.
 * - Los roles base del sistema (ROLE_SUPER_ADMIN, ROLE_ADMIN, ROLE_USER)
 * son inmutables: no se pueden eliminar ni modificar sus permisos.
 * - Safe-delete: no se puede eliminar un rol con usuarios asignados.
 */
@RestController
@RequestMapping("/api/admin/roles")
public class AdminRoleController {

    @Autowired
    private AdminRoleService adminRoleService;

    /**
     * GET /api/admin/roles - Listar todos los roles del sistema
     * Requiere: ROLE_ADMIN o ROLE_SUPER_ADMIN
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_READ')")
    public ResponseEntity<List<RoleResponseDTO>> getAllRoles() {
        List<RoleResponseDTO> roles = adminRoleService.getAllRoles();
        return ResponseEntity.ok(roles);
    }

    /**
     * GET /api/admin/roles/{id} - Obtener detalles de un rol específico
     * Requiere: ROLE_ADMIN o ROLE_SUPER_ADMIN
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_READ')")
    public ResponseEntity<RoleResponseDTO> getRoleById(@PathVariable Long id) {
        RoleResponseDTO role = adminRoleService.getRoleById(id);
        return ResponseEntity.ok(role);
    }

    /**
     * GET /api/admin/permissions - Listar todos los permisos disponibles
     * Requiere: ROLE_ADMIN o ROLE_SUPER_ADMIN
     */
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('PERMISSION_READ')")
    public ResponseEntity<List<PermissionDTO>> getAllPermissions() {
        List<PermissionDTO> permissions = adminRoleService.getAllPermissions();
        return ResponseEntity.ok(permissions);
    }

    /**
     * GET /api/admin/roles/permissions/by-category - Obtener permisos agrupados por
     * categoría
     * Requiere: ROLE_ADMIN o ROLE_SUPER_ADMIN
     */
    @GetMapping("/permissions/by-category")
    @PreAuthorize("hasAuthority('PERMISSION_READ')")
    public ResponseEntity<Map<String, List<PermissionDTO>>> getPermissionsByCategory() {
        Map<String, List<PermissionDTO>> permissionsByCategory = adminRoleService.getPermissionsByCategory();
        return ResponseEntity.ok(permissionsByCategory);
    }

    /**
     * GET /api/admin/roles/assignable-permissions-by-category
     * Obtener permisos asignables para el actor actual, filtrando críticos y ownerOnly si no es owner.
     * Requiere: ROLE_ADMIN o ROLE_SUPER_ADMIN
     */
    @GetMapping("/assignable-permissions-by-category")
    @PreAuthorize("hasAuthority('PERMISSION_READ')")
    public ResponseEntity<Map<String, List<PermissionDTO>>> getAssignablePermissionsByCategory() {
        Map<String, List<PermissionDTO>> permissionsByCategory = adminRoleService.getAssignablePermissionsByCategory();
        return ResponseEntity.ok(permissionsByCategory);
    }

    /**
     * POST /api/admin/roles - Crear un nuevo rol con permisos
     * Requiere: ROLE_SUPER_ADMIN (solo super admins pueden crear roles)
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_CREATE')")
    public ResponseEntity<Map<String, Object>> createRole(@Valid @RequestBody RoleCreateDTO dto) {
        RoleResponseDTO createdRole = adminRoleService.createRole(dto);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Rol creado exitosamente");
        response.put("role", createdRole);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * PUT /api/admin/roles/{id}/permissions - Actualizar permisos de un rol
     * Requiere: ROLE_SUPER_ADMIN (solo super admins pueden modificar permisos)
     * Reemplaza todos los permisos actuales con los nuevos
     */
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('PERMISSION_ASSIGN')")
    public ResponseEntity<Map<String, Object>> updateRolePermissions(
            @PathVariable Long id,
            @Valid @RequestBody RoleUpdatePermissionsDTO dto) {
        RoleResponseDTO updatedRole = adminRoleService.updateRolePermissions(id, dto);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Permisos del rol actualizados exitosamente");
        response.put("role", updatedRole);

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/admin/roles/{id}/permissions/add - Agregar permisos adicionales a
     * un rol
     * Requiere: ROLE_SUPER_ADMIN
     * No elimina los permisos existentes, solo agrega nuevos
     */
    @PostMapping("/{id}/permissions/add")
    @PreAuthorize("hasAuthority('PERMISSION_ASSIGN')")
    public ResponseEntity<Map<String, Object>> addPermissionsToRole(
            @PathVariable Long id,
            @RequestBody Map<String, java.util.Set<Long>> payload) {
        java.util.Set<Long> permissionIds = payload.get("permissionIds");
        if (permissionIds == null || permissionIds.isEmpty()) {
            throw new IllegalArgumentException("Se requiere al menos un permiso para agregar");
        }

        RoleResponseDTO updatedRole = adminRoleService.addPermissionsToRole(id, permissionIds);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Permisos agregados exitosamente al rol");
        response.put("role", updatedRole);

        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/admin/roles/{id}/permissions/remove - Remover permisos
     * específicos de un rol
     * Requiere: ROLE_SUPER_ADMIN
     * Valida que el rol no se quede sin permisos
     */
    @DeleteMapping("/{id}/permissions/remove")
    @PreAuthorize("hasAuthority('PERMISSION_ASSIGN')")
    public ResponseEntity<Map<String, Object>> removePermissionsFromRole(
            @PathVariable Long id,
            @RequestBody Map<String, java.util.Set<Long>> payload) {
        java.util.Set<Long> permissionIds = payload.get("permissionIds");
        if (permissionIds == null || permissionIds.isEmpty()) {
            throw new IllegalArgumentException("Se requiere al menos un permiso para remover");
        }

        RoleResponseDTO updatedRole = adminRoleService.removePermissionsFromRole(id, permissionIds);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Permisos removidos exitosamente del rol");
        response.put("role", updatedRole);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/admin/roles/{id}/users-count — Cantidad de usuarios con este rol.
     * Requiere: ROLE_ADMIN o ROLE_SUPER_ADMIN.
     */
    @GetMapping("/{id}/users-count")
    @PreAuthorize("hasAuthority('ROLE_READ')")
    public ResponseEntity<Map<String, Object>> countUsersWithRole(@PathVariable Long id) {
        Long userCount = adminRoleService.countUsersWithRole(id);

        Map<String, Object> response = new HashMap<>();
        response.put("roleId", id);
        response.put("userCount", userCount);

        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/admin/roles/{id} — Eliminar un rol del sistema.
     * Requiere: ROLE_SUPER_ADMIN (acceso exclusivo).
     *
     * Aplica las reglas de oro:
     * 1. Los roles base del sistema no pueden ser eliminados.
     * 2. No se puede eliminar un rol con usuarios asignados (safe-delete).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_DELETE')")
    public ResponseEntity<Map<String, Object>> deleteRole(@PathVariable Long id) {
        adminRoleService.deleteRole(id);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Rol eliminado exitosamente");
        response.put("roleId", id);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/admin/roles/{id}/users — Usuarios paginados que poseen este rol.
     * Requiere: ROLE_ADMIN o ROLE_SUPER_ADMIN.
     * Parámetros: page (default 0), size (default 10)
     */
    @GetMapping("/{id}/users")
    @PreAuthorize("hasAuthority('ROLE_READ')")
    public ResponseEntity<Page<AdminUserListDTO>> getUsersByRole(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<AdminUserListDTO> users = adminRoleService.getUsersByRole(id, page, size);
        return ResponseEntity.ok(users);
    }
}
