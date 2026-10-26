package com.security.repository;

import com.security.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
        @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT u FROM User u WHERE u.id = :id")
        Optional<User> findByIdForAuthUpdate(@Param("id") Long id);


        // Métodos para autenticación — @EntityGraph carga roles + permisos en un solo
        // JOIN
        @EntityGraph(attributePaths = { "roles", "roles.permissions" })
        Optional<User> findByEmail(String email);

        // Verificar existencia
        boolean existsByEmail(String email);

        boolean existsByUsername(String username);

        // Búsqueda por username — también necesita roles + permisos para auth
        @EntityGraph(attributePaths = { "roles", "roles.permissions" })
        Optional<User> findByUsername(String username);

        // Métodos para estado del usuario
        List<User> findByEnabled(boolean enabled);

        List<User> findByAccountNonLocked(boolean accountNonLocked);

        // Métodos para autenticación de dos factores
        Optional<User> findByTwoFactorSecret(String twoFactorSecret);

        List<User> findByTwoFactorEnabledTrue();

        // Búsquedas personalizadas
        @Query("SELECT u FROM User u WHERE u.email LIKE %:searchTerm% OR u.firstName LIKE %:searchTerm% OR u.lastName LIKE %:searchTerm%")
        List<User> findByEmailOrNameContaining(@Param("searchTerm") String searchTerm);

        @EntityGraph(attributePaths = { "roles" })
        @Query("SELECT u FROM User u JOIN u.roles r WHERE r.name = :roleName")
        List<User> findByRoleName(@Param("roleName") String roleName);

        // Métodos para gestión de cuentas
        @Query("SELECT u FROM User u WHERE u.createdAt BETWEEN :startDate AND :endDate")
        List<User> findByCreatedAtBetween(@Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate);

        // Contadores para administración
        long countByEnabled(boolean enabled);

        long countByTwoFactorEnabled(boolean twoFactorEnabled);

        // Métodos específicos para contadores de administración
        long countByEnabledTrue();

        long countByTwoFactorEnabledTrue();

        // Métodos para gestión de clientes (usuarios que han comprado)
        long countByIsCustomerTrueAndEnabledTrue();

        List<User> findByIsCustomerTrue();

        @Query("SELECT u FROM User u WHERE u.isCustomer = true AND u.enabled = true ORDER BY u.totalSpent DESC")
        List<User> findTopCustomersBySpending(org.springframework.data.domain.Pageable pageable);

        // ==================== Metodos para gestion de Staff (is_customer = false)
        // ====================

        /**
         * Buscar usuarios Staff paginados
         */
        @EntityGraph(attributePaths = { "roles" })
        @Query("SELECT u FROM User u WHERE u.isCustomer = false ORDER BY u.createdAt DESC")
        org.springframework.data.domain.Page<User> findAllStaff(org.springframework.data.domain.Pageable pageable);

        /**
         * Buscar usuarios Staff con filtros
         */
        @EntityGraph(attributePaths = { "roles" })
        @Query("SELECT u FROM User u WHERE u.isCustomer = false AND " +
                        "(:enabled IS NULL OR u.enabled = :enabled) AND " +
                        "(:accountNonLocked IS NULL OR u.accountNonLocked = :accountNonLocked) AND " +
                        "(LOWER(u.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
                        "LOWER(u.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
                        "LOWER(u.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
                        "ORDER BY u.createdAt DESC")
        org.springframework.data.domain.Page<User> findStaffWithFilters(
                        @Param("searchTerm") String searchTerm,
                        @Param("enabled") Boolean enabled,
                        @Param("accountNonLocked") Boolean accountNonLocked,
                        org.springframework.data.domain.Pageable pageable);

        /**
         * Buscar usuarios Staff paginados (exclusivo para Store Manager: oculta roles técnicos)
         */
        @EntityGraph(attributePaths = { "roles" })
        @Query("SELECT u FROM User u WHERE u.isCustomer = false AND NOT EXISTS (SELECT r FROM u.roles r WHERE r.name IN ('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_PROJECT_ADMIN', 'ROLE_STORE_MANAGER')) ORDER BY u.createdAt DESC")
        org.springframework.data.domain.Page<User> findOperationalStaff(org.springframework.data.domain.Pageable pageable);

        /**
         * Buscar usuarios Staff operativos con filtros (exclusivo para Store Manager)
         */
        @EntityGraph(attributePaths = { "roles" })
        @Query("SELECT u FROM User u WHERE u.isCustomer = false AND NOT EXISTS (SELECT r FROM u.roles r WHERE r.name IN ('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_PROJECT_ADMIN', 'ROLE_STORE_MANAGER')) AND " +
                        "(:enabled IS NULL OR u.enabled = :enabled) AND " +
                        "(:accountNonLocked IS NULL OR u.accountNonLocked = :accountNonLocked) AND " +
                        "(LOWER(u.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
                        "LOWER(u.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
                        "LOWER(u.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
                        "ORDER BY u.createdAt DESC")
        org.springframework.data.domain.Page<User> findOperationalStaffWithFilters(
                        @Param("searchTerm") String searchTerm,
                        @Param("enabled") Boolean enabled,
                        @Param("accountNonLocked") Boolean accountNonLocked,
                        org.springframework.data.domain.Pageable pageable);

        /**
         * Contar usuarios Staff activos
         */
        @Query("SELECT COUNT(u) FROM User u WHERE u.isCustomer = false AND u.enabled = true")
        Long countStaffEnabled();

        /**
         * Verificar si un usuario tiene un rol especifico (por nombre de enum)
         */
        @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END " +
                        "FROM User u JOIN u.roles r WHERE u.id = :userId AND r.name = :roleName")
        boolean hasRole(@Param("userId") Long userId, @Param("roleName") String roleName);

        // ==================== Métodos para gestión de Clientes (is_customer = true)
        // ====================

        /**
         * Listar todos los clientes (is_customer = true) paginados, ordenados por
         * fecha de registro descendente.
         */
        @EntityGraph(attributePaths = { "roles" })
        @Query("SELECT u FROM User u WHERE u.isCustomer = true AND u.enabled = true ORDER BY u.createdAt DESC")
        org.springframework.data.domain.Page<User> findAllCustomers(org.springframework.data.domain.Pageable pageable);

        /**
         * Buscar clientes con filtros de búsqueda, estado habilitado y estado de
         * bloqueo. Los parámetros opcionales se ignoran cuando son NULL.
         */
        @EntityGraph(attributePaths = { "roles" })
        @Query("SELECT u FROM User u WHERE u.isCustomer = true AND " +
                        "(:enabled IS NULL OR u.enabled = :enabled) AND " +
                        "(:accountNonLocked IS NULL OR u.accountNonLocked = :accountNonLocked) AND " +
                        "(LOWER(u.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
                        "LOWER(u.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
                        "LOWER(u.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
                        "LOWER(u.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
                        "ORDER BY u.createdAt DESC")
        org.springframework.data.domain.Page<User> findCustomersWithFilters(
                        @Param("searchTerm") String searchTerm,
                        @Param("enabled") Boolean enabled,
                        @Param("accountNonLocked") Boolean accountNonLocked,
                        org.springframework.data.domain.Pageable pageable);

        /**
         * Contar clientes activos (habilitados)
         */
        @Query("SELECT COUNT(u) FROM User u WHERE u.isCustomer = true AND u.enabled = true")
        Long countCustomersEnabled();

        /**
         * Contar total de clientes
         */
        @Query("SELECT COUNT(u) FROM User u WHERE u.isCustomer = true")
        Long countAllCustomers();

        /**
         * Listar todos los usuarios (staff y clientes) que tienen el rol indicado,
         * paginados.
         * Usado por el endpoint de auditoría GET /api/admin/roles/{id}/users.
         */
        @EntityGraph(attributePaths = { "roles" })
        @Query("SELECT u FROM User u JOIN u.roles r WHERE r.id = :roleId ORDER BY u.createdAt DESC")
        org.springframework.data.domain.Page<User> findByRolesId(
                        @Param("roleId") Long roleId,
                        org.springframework.data.domain.Pageable pageable);

        /**
         * Buscar un usuario con sus roles Y los permisos de cada rol (doble fetch).
         * Usado para construir respuestas detalladas como AdminUserResponseDTO.
         */
        @EntityGraph(attributePaths = { "roles", "roles.permissions" })
        @Query("SELECT u FROM User u WHERE u.id = :id")
        Optional<User> findByIdWithRolesAndPermissions(@Param("id") Long id);

        /**
         * Buscar un usuario con sus roles (sin permisos).
         * Usado por servicios que solo necesitan verificar roles del usuario (ej:
         * RBACService).
         */
        @EntityGraph(attributePaths = { "roles" })
        @Query("SELECT u FROM User u WHERE u.id = :id")
        Optional<User> findByIdWithRoles(@Param("id") Long id);

        /**
         * Obtener empleados activos (habilitados y no bloqueados) para el selector
         * de destinatarios de notificaciones en automatizaciones.
         * Excluye clientes (isCustomer = false).
         */
        @EntityGraph(attributePaths = { "roles" })
        @Query("SELECT u FROM User u WHERE u.isCustomer = false AND u.enabled = true AND u.accountNonLocked = true ORDER BY u.firstName ASC")
        List<User> findActiveStaffMembers();

        // ═══════════════════════════════════════════════════════════════════════
        // Ghost Users — Cleanup Job
        // ═══════════════════════════════════════════════════════════════════════

        /**
         * Encontrar IDs de "usuarios fantasma": cuentas no verificadas (enabled=false)
         * creadas antes del punto de corte, sin pedidos realizados.
         * Se excluyen usuarios con totalOrders > 0 para evitar borrar cuentas con
         * actividad real.
         */
        @Query("SELECT u.id FROM User u WHERE u.enabled = false AND u.createdAt < :cutoff")
        List<Long> findGhostUserIds(@Param("cutoff") LocalDateTime cutoff);

        /**
         * Eliminar las filas de la tabla intermedia user_roles para los IDs indicados.
         * Se usa una query nativa porque @ManyToMany no tiene CascadeType.REMOVE.
         */
        @Modifying
        @Query(value = "DELETE FROM auth.user_roles WHERE user_id IN :userIds", nativeQuery = true)
        void deleteUserRolesByUserIds(@Param("userIds") List<Long> userIds);

        /**
         * Eliminar usuarios por sus IDs (batch delete).
         * PRECONDICIÓN: todas las FK hijas (tokens, user_roles) deben haberse
         * eliminado previamente.
         */
        @Modifying
        @Query("DELETE FROM User u WHERE u.id IN :userIds")
        void deleteByIdIn(@Param("userIds") List<Long> userIds);
}
