package com.security.service.admin;

import com.security.dto.admin.PermissionDTO;
import com.security.dto.admin.AdminUserListDTO;
import com.security.dto.admin.RoleCreateDTO;
import com.security.dto.admin.RoleResponseDTO;
import com.security.dto.admin.RoleUpdatePermissionsDTO;
import com.security.entity.Permission;
import com.security.entity.Role;
import com.security.entity.User;
import com.security.exception.ResourceNotFoundException;
import com.security.repository.PermissionRepository;
import com.security.repository.RoleRepository;
import com.security.repository.UserRepository;
import com.security.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Servicio para gestión de Roles y Permisos del sistema.
 * Implementa RBAC con permisos granulares y las tres reglas de oro:
 * 1. Roles inmutables: ROLE_SUPER_ADMIN, ROLE_ADMIN y ROLE_USER no se pueden
 * eliminar ni modificar sus permisos.
 * 2. Safe-delete: no se puede eliminar un rol si tiene usuarios asignados.
 * 3. Acceso restringido: todas las operaciones de escritura requieren
 * SUPER_ADMIN.
 */
@Service
public class AdminRoleService {

        /**
         * Roles base del sistema que nunca pueden ser eliminados ni modificados.
         * Cualquier intento de mutación sobre estos roles lanzará una excepción.
         */
        private static final Set<String> IMMUTABLE_ROLES = Set.of(
                        "ROLE_SUPER_ADMIN",
                        "ROLE_ADMIN",
                        "ROLE_USER");

        /**
         * Permisos sensibles que solo pueden ser asignados por SUPER_ADMIN.
         * Si un usuario con rol ADMIN u otro intenta crear/actualizar un rol
         * incluyendo alguno de estos permisos, se lanzará una excepción.
         */
        private static final Set<String> ADMIN_ONLY_PERMISSIONS = Set.of(
                        "ROLE_CREATE", "ROLE_UPDATE", "ROLE_DELETE",
                        "PERMISSION_ASSIGN",
                        "USER_DELETE", "USER_MANAGE_ROLES",
                        "DATABASE_BACKUP", "DATABASE_MAINTAIN", "DATABASE_AUTOMATE",
                        "SYSTEM_SETTINGS");

        private static final Set<String> RESERVED_ROLE_NAMES = Set.of(
                        "ROLE_SUPER_ADMIN", "ROLE_ADMIN", "ROLE_USER",
                        "ROLE_SYSTEM_OWNER", "ROLE_PROTECTED_OWNER", "ROLE_ROOT",
                        "ROLE_OWNER", "ROLE_MASTER_ADMIN", "ROLE_FULL_ACCESS");

        private static final Logger logger = LoggerFactory.getLogger(AdminRoleService.class);

        @Autowired
        private RoleRepository roleRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private AuditLogService auditLogService;

        @Autowired
        private com.security.service.AdminHierarchyService adminHierarchyService;

        @Autowired
        private com.security.service.RolePolicyService rolePolicyService;

        /**
         * Helper para obtener el actor actual.
         */
        private User getCurrentActor() {
                String currentAdmin = getCurrentUsername();
                if (currentAdmin != null && !"SYSTEM".equals(currentAdmin)) {
                        return userRepository.findByEmail(currentAdmin).orElse(null);
                }
                return null;
        }

        /**
         * Obtener todos los roles del sistema
         */
        @Transactional(readOnly = true)
        public List<RoleResponseDTO> getAllRoles() {
                List<Role> roles = roleRepository.findAll();

                return roles.stream()
                                .map(this::convertToResponseDTO)
                                .collect(Collectors.toList());
        }

        /**
         * Obtener un rol específico por ID con sus permisos
         */
        @Transactional(readOnly = true)
        public RoleResponseDTO getRoleById(Long id) {
                Role role = roleRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Rol no encontrado con ID: " + id));

                return convertToResponseDTO(role);
        }

        /**
         * Obtener todos los permisos disponibles en el sistema
         */
        @Transactional(readOnly = true)
        public List<PermissionDTO> getAllPermissions() {
                List<Permission> permissions = permissionRepository.findAllOrderedByCategoryAndName();

                return permissions.stream()
                                .map(this::convertToPermissionDTO)
                                .collect(Collectors.toList());
        }

        /**
         * Obtener permisos agrupados por categoría
         */
        @Transactional(readOnly = true)
        public java.util.Map<String, List<PermissionDTO>> getPermissionsByCategory() {
                List<Permission> permissions = permissionRepository.findAllOrderedByCategoryAndName();

                return permissions.stream()
                                .collect(Collectors.groupingBy(
                                                p -> p.getCategory() != null ? p.getCategory() : "UNCATEGORIZED",
                                                Collectors.mapping(this::convertToPermissionDTO, Collectors.toList())));
        }

        /**
         * Obtener permisos asignables agrupados por categoría, filtrando los no permitidos.
         */
        @Transactional(readOnly = true)
        public java.util.Map<String, List<PermissionDTO>> getAssignablePermissionsByCategory() {
                User actor = getCurrentActor();
                boolean isProtectedOwner = actor != null && rolePolicyService.isProtectedOwner(actor);

                List<Permission> permissions = permissionRepository.findAllOrderedByCategoryAndName();

                return permissions.stream()
                                .filter(p -> {
                                        if (!isProtectedOwner) {
                                                return !rolePolicyService.isOwnerOnlyPermission(p.getName()) &&
                                                       !rolePolicyService.isCriticalPermission(p.getName());
                                        }
                                        return true;
                                })
                                .collect(Collectors.groupingBy(
                                                p -> p.getCategory() != null ? p.getCategory() : "UNCATEGORIZED",
                                                Collectors.mapping(p -> {
                                                        PermissionDTO dto = convertToPermissionDTO(p);
                                                        dto.setAssignable(true);
                                                        return dto;
                                                }, Collectors.toList())));
        }

        /**
         * Crear un nuevo rol con permisos asignados.
         * No se permite crear roles cuyo nombre coincida con los roles inmutables del
         * sistema.
         */
        @Transactional
        public RoleResponseDTO createRole(RoleCreateDTO dto) {
                String normalizedName = dto.getName().trim().toUpperCase();

                // Regla de oro 1: no se pueden crear roles con nombres reservados
                if (RESERVED_ROLE_NAMES.contains(normalizedName)) {
                        throw new IllegalArgumentException(
                                        "El nombre '" + normalizedName
                                                        + "' está reservado y no puede ser utilizado.");
                }

                // Validar unicidad del nombre
                if (roleRepository.existsByName(normalizedName)) {
                        throw new IllegalArgumentException("Ya existe un rol con el nombre: " + normalizedName);
                }

                // Obtener los permisos a asignar
                Set<Permission> permissions = dto.getPermissionIds().stream()
                                .map(permId -> permissionRepository.findById(permId)
                                                .orElseThrow(() -> new ResourceNotFoundException(
                                                                "Permiso no encontrado con ID: " + permId)))
                                .collect(Collectors.toSet());

                if (permissions.isEmpty()) {
                        throw new IllegalArgumentException("Debe asignar al menos un permiso al rol.");
                }

                // Regla de seguridad: permisos sensibles solo pueden ser asignados por
                // SUPER_ADMIN
                validateAdminOnlyPermissions(permissions);
                
                User actor = getCurrentActor();
                if (actor != null) {
                        rolePolicyService.assertCanAssignPermissions(actor, permissions.stream().map(Permission::getName).collect(Collectors.toSet()));
                }

                // Crear y persistir el nuevo rol
                Role newRole = new Role();
                newRole.setName(normalizedName);
                if (dto.getDescription() != null && !dto.getDescription().isBlank()) {
                        newRole.setDescription(dto.getDescription().trim());
                }
                newRole.setPermissions(permissions);

                Role savedRole = roleRepository.save(newRole);

                // Auditoría
                String currentAdmin = getCurrentUsername();
                String permissionsStr = permissions.stream()
                                .map(Permission::getName)
                                .collect(Collectors.joining(", "));

                auditLogService.logRoleCreation(savedRole.getId(), savedRole.getName(), currentAdmin);
                auditLogService.logRolePermissionsUpdate(savedRole.getId(), savedRole.getName(),
                                permissionsStr, currentAdmin);

                logger.info("createRole — rol '{}' (ID: {}) creado con {} permisos por '{}'",
                                savedRole.getName(), savedRole.getId(), permissions.size(), currentAdmin);

                return convertToResponseDTO(savedRole);
        }

        /**
         * Reemplazar completamente los permisos de un rol existente.
         * Regla de oro 1: los roles inmutables del sistema no pueden ser modificados.
         */
        @Transactional
        public RoleResponseDTO updateRolePermissions(Long roleId, RoleUpdatePermissionsDTO dto) {
                Role role = roleRepository.findById(roleId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Rol no encontrado con ID: " + roleId));

                // Regla de oro 1: proteger roles inmutables
                if (IMMUTABLE_ROLES.contains(role.getName())) {
                        throw new IllegalArgumentException(
                                        "El rol '" + role.getName()
                                                        + "' es un rol base del sistema y sus permisos no pueden modificarse.");
                }

                // SECURITY: Usar AdminHierarchyService para proteger roles marcados como is_system_role
                String currentAdmin = getCurrentUsername();
                User actor = null;
                if (currentAdmin != null && !"SYSTEM".equals(currentAdmin)) {
                        actor = userRepository.findByEmail(currentAdmin).orElse(null);
                        if (actor != null) {
                                adminHierarchyService.assertCanModifyRole(actor, role);
                        }
                }

                // Obtener nuevos permisos
                Set<Permission> newPermissions = dto.getPermissionIds().stream()
                                .map(permId -> permissionRepository.findById(permId)
                                                .orElseThrow(() -> new ResourceNotFoundException(
                                                                "Permiso no encontrado con ID: " + permId)))
                                .collect(Collectors.toSet());

                if (newPermissions.isEmpty()) {
                        throw new IllegalArgumentException("Debe asignar al menos un permiso al rol.");
                }

                // Regla de seguridad: permisos sensibles solo pueden ser asignados por
                // SUPER_ADMIN
                validateAdminOnlyPermissions(newPermissions);
                
                if (actor != null) {
                        rolePolicyService.assertCanAssignPermissions(actor, newPermissions.stream().map(Permission::getName).collect(Collectors.toSet()));
                        
                        Set<Permission> permissionsToRemove = new java.util.HashSet<>(role.getPermissions());
                        permissionsToRemove.removeAll(newPermissions);
                        rolePolicyService.assertCanAssignPermissions(actor, permissionsToRemove.stream().map(Permission::getName).collect(Collectors.toSet()));
                }

                String oldPermissions = role.getPermissions().stream()
                                .map(Permission::getName)
                                .collect(Collectors.joining(", "));

                role.clearPermissions();
                role.setPermissions(newPermissions);
                Role updatedRole = roleRepository.save(role);

                // String currentAdmin = getCurrentUsername(); Ya fue definido arriba
                String newPermissionsStr = newPermissions.stream()
                                .map(Permission::getName)
                                .collect(Collectors.joining(", "));

                auditLogService.logRolePermissionsUpdate(updatedRole.getId(), updatedRole.getName(),
                                newPermissionsStr, currentAdmin);

                logger.info("updateRolePermissions — rol '{}' (ID: {}) actualizado por '{}'. Antes: [{}] -> Ahora: [{}]",
                                updatedRole.getName(), updatedRole.getId(), currentAdmin,
                                oldPermissions, newPermissionsStr);

                return convertToResponseDTO(updatedRole);
        }

        /**
         * Agregar permisos adicionales a un rol sin eliminar los existentes.
         * Regla de oro 1: los roles inmutables no pueden ser modificados.
         */
        @Transactional
        public RoleResponseDTO addPermissionsToRole(Long roleId, Set<Long> permissionIds) {
                Role role = roleRepository.findById(roleId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Rol no encontrado con ID: " + roleId));

                if (IMMUTABLE_ROLES.contains(role.getName())) {
                        throw new IllegalArgumentException(
                                        "El rol '" + role.getName()
                                                        + "' es un rol base del sistema y no puede ser modificado.");
                }

                // SECURITY: Usar AdminHierarchyService para proteger roles marcados como is_system_role
                String currentAdmin = getCurrentUsername();
                User actor = null;
                if (currentAdmin != null && !"SYSTEM".equals(currentAdmin)) {
                        actor = userRepository.findByEmail(currentAdmin).orElse(null);
                        if (actor != null) {
                                adminHierarchyService.assertCanModifyRole(actor, role);
                        }
                }

                Set<Permission> permissionsToAdd = permissionIds.stream()
                                .map(permId -> permissionRepository.findById(permId)
                                                .orElseThrow(() -> new ResourceNotFoundException(
                                                                "Permiso no encontrado con ID: " + permId)))
                                .collect(Collectors.toSet());

                // Regla de seguridad: permisos sensibles solo pueden ser asignados por
                // SUPER_ADMIN
                validateAdminOnlyPermissions(permissionsToAdd);
                
                if (actor != null) {
                        rolePolicyService.assertCanAssignPermissions(actor, permissionsToAdd.stream().map(Permission::getName).collect(Collectors.toSet()));
                }

                permissionsToAdd.forEach(role::addPermission);
                Role updatedRole = roleRepository.save(role);

                // String currentAdmin = getCurrentUsername(); Ya fue definido arriba
                String addedPermissions = permissionsToAdd.stream()
                                .map(Permission::getName)
                                .collect(Collectors.joining(", "));

                auditLogService.logAction("ADD_PERMISSIONS_TO_ROLE", "ROLE", updatedRole.getId(),
                                String.format("Permisos agregados al rol '%s' por '%s': %s",
                                                updatedRole.getName(), currentAdmin, addedPermissions));

                logger.info("addPermissionsToRole — permisos agregados al rol '{}' (ID: {}): [{}] por '{}'",
                                updatedRole.getName(), updatedRole.getId(), addedPermissions, currentAdmin);

                return convertToResponseDTO(updatedRole);
        }

        /**
         * Remover permisos específicos de un rol.
         * Regla de oro 1: los roles inmutables no pueden ser modificados.
         * Valida que el rol no se quede sin permisos.
         */
        @Transactional
        public RoleResponseDTO removePermissionsFromRole(Long roleId, Set<Long> permissionIds) {
                Role role = roleRepository.findById(roleId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Rol no encontrado con ID: " + roleId));

                if (IMMUTABLE_ROLES.contains(role.getName())) {
                        throw new IllegalArgumentException(
                                        "El rol '" + role.getName()
                                                        + "' es un rol base del sistema y no puede ser modificado.");
                }

                // SECURITY: Usar AdminHierarchyService para proteger roles marcados como is_system_role
                String currentAdmin = getCurrentUsername();
                User actor = null;
                if (currentAdmin != null && !"SYSTEM".equals(currentAdmin)) {
                        actor = userRepository.findByEmail(currentAdmin).orElse(null);
                        if (actor != null) {
                                adminHierarchyService.assertCanModifyRole(actor, role);
                        }
                }

                Set<Permission> permissionsToRemove = permissionIds.stream()
                                .map(permId -> permissionRepository.findById(permId)
                                                .orElseThrow(() -> new ResourceNotFoundException(
                                                                "Permiso no encontrado con ID: " + permId)))
                                .collect(Collectors.toSet());

                if (actor != null) {
                        rolePolicyService.assertCanAssignPermissions(actor, permissionsToRemove.stream().map(Permission::getName).collect(Collectors.toSet()));
                }

                if (role.getPermissions().size() <= permissionsToRemove.size()) {
                        throw new IllegalArgumentException(
                                        "No se puede dejar un rol sin permisos. Debe tener al menos uno.");
                }

                permissionsToRemove.forEach(role::removePermission);
                Role updatedRole = roleRepository.save(role);

                // String currentAdmin = getCurrentUsername(); Ya fue definido arriba
                String removedPermissions = permissionsToRemove.stream()
                                .map(Permission::getName)
                                .collect(Collectors.joining(", "));

                auditLogService.logAction("REMOVE_PERMISSIONS_FROM_ROLE", "ROLE", updatedRole.getId(),
                                String.format("Permisos removidos del rol '%s' por '%s': %s",
                                                updatedRole.getName(), currentAdmin, removedPermissions));

                logger.info("removePermissionsFromRole — permisos removidos del rol '{}' (ID: {}): [{}] por '{}'",
                                updatedRole.getName(), updatedRole.getId(), removedPermissions, currentAdmin);

                return convertToResponseDTO(updatedRole);
        }

        /**
         * Eliminar un rol del sistema.
         * Regla de oro 1: los roles base del sistema (ROLE_SUPER_ADMIN, ROLE_ADMIN,
         * ROLE_USER)
         * nunca pueden ser eliminados.
         * Regla de oro 2 (safe-delete): no se puede eliminar un rol que tenga usuarios
         * asignados.
         * El llamador recibirá el conteo de usuarios afectados para informar al
         * administrador.
         */
        @Transactional
        public void deleteRole(Long roleId) {
                Role role = roleRepository.findById(roleId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Rol no encontrado con ID: " + roleId));

                // Regla de oro 1: roles inmutables
                if (IMMUTABLE_ROLES.contains(role.getName())) {
                        throw new IllegalArgumentException(
                                        "El rol '" + role.getName()
                                                        + "' es un rol base del sistema y no puede ser eliminado.");
                }

                // SECURITY: Usar AdminHierarchyService para proteger roles marcados como is_system_role
                String currentAdmin = getCurrentUsername();
                User actor = null;
                if (currentAdmin != null && !"SYSTEM".equals(currentAdmin)) {
                        actor = userRepository.findByEmail(currentAdmin).orElse(null);
                        if (actor != null) {
                                adminHierarchyService.assertCanDeleteRole(actor, role);
                        }
                }

                // Regla de oro 2: safe-delete
                long usersCount = roleRepository.countUsersByRoleId(roleId);
                if (usersCount > 0) {
                        throw new IllegalStateException(
                                        "No se puede eliminar el rol '" + role.getName() + "' porque tiene "
                                                        + usersCount + " usuario(s) asignado(s). "
                                                        + "Primero reasigna o elimina esos usuarios.");
                }

                // String currentAdmin = getCurrentUsername(); Ya fue definido arriba
                String roleName = role.getName();
                Long id = role.getId();

                roleRepository.delete(role);

                auditLogService.logAction("DELETE_ROLE", "ROLE", id,
                                String.format("Rol '%s' eliminado por '%s'", roleName, currentAdmin));

                logger.info("deleteRole — rol '{}' (ID: {}) eliminado por '{}'",
                                roleName, id, currentAdmin);
        }

        /**
         * Obtener cantidad de usuarios que tienen este rol asignado.
         */
        @Transactional(readOnly = true)
        public Long countUsersWithRole(Long roleId) {
                roleRepository.findById(roleId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Rol no encontrado con ID: " + roleId));

                return roleRepository.countUsersByRoleId(roleId);
        }

        /**
         * Obtener usuarios paginados que tienen un rol específico asignado.
         * GET /api/admin/roles/{roleId}/users
         */
        @Transactional(readOnly = true)
        public Page<AdminUserListDTO> getUsersByRole(Long roleId, int page, int size) {
                // Validate role exists
                roleRepository.findById(roleId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Rol no encontrado con ID: " + roleId));

                PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
                Page<User> usersPage = userRepository.findByRolesId(roleId, pageable);

                return usersPage.map(this::convertUserToListDTO);
        }

        /**
         * Convierte un User en AdminUserListDTO (versión ligera para la tabla de
         * auditoría).
         */
        private AdminUserListDTO convertUserToListDTO(User user) {
                AdminUserListDTO dto = new AdminUserListDTO();
                dto.setId(user.getId());
                dto.setFirstName(user.getFirstName());
                dto.setLastName(user.getLastName());
                dto.setEmail(user.getEmail());
                dto.setEnabled(user.getEnabled());
                dto.setAccountNonLocked(user.getAccountNonLocked());
                dto.setCreatedAt(user.getCreatedAt());
                String rolesStr = user.getRoles().stream()
                                .map(Role::getName)
                                .collect(Collectors.joining(", "));
                dto.setRoles(rolesStr);
                return dto;
        }

        /**
         * Indica si un rol es inmutable (rol base del sistema).
         * Usado como helper por el controlador para exponer esta lógica al frontend.
         */
        public boolean isImmutableRole(String roleName) {
                return IMMUTABLE_ROLES.contains(roleName);
        }

        // ==================== Métodos Helper ====================

        /**
         * Convertir Role a RoleResponseDTO.
         * Marca el campo 'immutable' si el rol es un rol base del sistema.
         */
        private RoleResponseDTO convertToResponseDTO(Role role) {
                RoleResponseDTO dto = new RoleResponseDTO();
                dto.setId(role.getId());
                dto.setName(role.getName());
                dto.setDescription(role.getDescription());
                dto.setCreatedAt(role.getCreatedAt());
                dto.setUpdatedAt(role.getUpdatedAt());
                dto.setImmutable(IMMUTABLE_ROLES.contains(role.getName()));

                Set<PermissionDTO> permissionsDTO = role.getPermissions().stream()
                                .map(this::convertToPermissionDTO)
                                .collect(Collectors.toSet());
                dto.setPermissions(permissionsDTO);

                dto.setUserCount(roleRepository.countUsersByRoleId(role.getId()));

                return dto;
        }

        /**
         * Convertir Permission a PermissionDTO
         */
        private PermissionDTO convertToPermissionDTO(Permission permission) {
                PermissionDTO dto = new PermissionDTO();
                dto.setId(permission.getId());
                dto.setName(permission.getName());
                dto.setDescription(permission.getDescription());
                dto.setCategory(permission.getCategory());
                dto.setCreatedAt(permission.getCreatedAt());
                if (rolePolicyService != null) {
                        dto.setOwnerOnly(rolePolicyService.isOwnerOnlyPermission(permission.getName()));
                        dto.setCritical(rolePolicyService.isCriticalPermission(permission.getName()));
                }
                return dto;
        }

        /**
         * Obtener el username del usuario autenticado actual
         */
        private String getCurrentUsername() {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                return auth != null ? auth.getName() : "SYSTEM";
        }

        /**
         * Verifica si el usuario autenticado actual tiene el rol SUPER_ADMIN.
         */
        private boolean isCurrentUserSuperAdmin() {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null)
                        return false;
                return auth.getAuthorities().stream()
                                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
        }

        /**
         * Valida que los permisos solicitados no incluyan permisos sensibles
         * (ADMIN_ONLY_PERMISSIONS) a menos que el usuario actual sea SUPER_ADMIN.
         * 
         * @param permissions conjunto de permisos a validar
         * @throws IllegalArgumentException si un no-SUPER_ADMIN intenta asignar
         *                                  permisos sensibles
         */
        private void validateAdminOnlyPermissions(Set<Permission> permissions) {
                if (isCurrentUserSuperAdmin()) {
                        return; // SUPER_ADMIN puede asignar cualquier permiso
                }

                Set<String> restrictedFound = permissions.stream()
                                .map(Permission::getName)
                                .filter(ADMIN_ONLY_PERMISSIONS::contains)
                                .collect(Collectors.toSet());

                if (!restrictedFound.isEmpty()) {
                        throw new IllegalArgumentException(
                                        "Los siguientes permisos solo pueden ser asignados por un SUPER_ADMIN: "
                                                        + String.join(", ", restrictedFound));
                }
        }
}
