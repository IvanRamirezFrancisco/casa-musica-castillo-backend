package com.security.controller;

import com.security.dto.response.ApiResponse;
import com.security.dto.response.UserResponse;
import com.security.entity.User;
import com.security.security.CurrentUser;
import com.security.security.UserPrincipal;
import com.security.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controlador para funciones de administración
 * Requiere rol ADMIN para acceder
 */
@RestController
@RequestMapping("/api/admin")
// CORS se maneja globalmente en SecurityConfig
public class AdminController {

    @Autowired
    private UserService userService;

    /**
     * Dashboard de administrador — Requiere DASHBOARD_VIEW
     */
    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ResponseEntity<?> getDashboard(@CurrentUser UserPrincipal currentUser) {
        try {
            // Estadísticas básicas
            long totalUsers = userService.getTotalUsersCount();
            long activeUsers = userService.getActiveUsersCount();
            long verifiedUsers = userService.getVerifiedUsersCount();
            long usersWithMFA = userService.getUsersWithMFACount();

            Map<String, Object> stats = Map.of(
                    "totalUsers", totalUsers,
                    "activeUsers", activeUsers,
                    "verifiedUsers", verifiedUsers,
                    "usersWithMFA", usersWithMFA,
                    "adminUser", currentUser.getEmail());

            return ResponseEntity.ok(new ApiResponse(true, "Dashboard data retrieved", stats));

        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(new ApiResponse(false, "Error retrieving dashboard data: " + e.getMessage()));
        }
    }

    /**
     * Listar todos los usuarios — Requiere USER_READ
     */
    @GetMapping("/users")
    @PreAuthorize("hasAuthority('USER_READ')")
    public ResponseEntity<?> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Pageable pageable = PageRequest.of(page, size);
            Page<User> usersPage = userService.getAllUsersPaginated(pageable);

            List<UserResponse> users = usersPage.getContent().stream()
                    .map(userService::convertToUserResponse)
                    .collect(Collectors.toList());

            Map<String, Object> response = Map.of(
                    "users", users,
                    "currentPage", usersPage.getNumber(),
                    "totalPages", usersPage.getTotalPages(),
                    "totalElements", usersPage.getTotalElements());

            return ResponseEntity.ok(new ApiResponse(true, "Users retrieved", response));

        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(new ApiResponse(false, "Error retrieving users: " + e.getMessage()));
        }
    }

    /**
     * Desactivar usuario — Requiere USER_UPDATE
     */
    @PutMapping("/users/{userId}/disable")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<?> disableUser(@PathVariable Long userId) {
        try {
            userService.disableUser(userId);
            return ResponseEntity.ok(new ApiResponse(true, "User disabled successfully"));

        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(new ApiResponse(false, "Error disabling user: " + e.getMessage()));
        }
    }

    /**
     * Activar usuario — Requiere USER_UPDATE
     */
    @PutMapping("/users/{userId}/enable")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<?> enableUser(@PathVariable Long userId) {
        try {
            userService.enableUser(userId);
            return ResponseEntity.ok(new ApiResponse(true, "User enabled successfully"));

        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(new ApiResponse(false, "Error enabling user: " + e.getMessage()));
        }
    }

    /**
     * Obtener logs de seguridad — Solo SUPER_ADMIN
     */
    @GetMapping("/security-logs")
    @PreAuthorize("hasAuthority('SYSTEM_SETTINGS')")
    public ResponseEntity<?> getSecurityLogs() {
        try {
            // Implementar cuando tengas servicio de logs
            return ResponseEntity.ok(new ApiResponse(true, "Security logs feature coming soon"));

        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(new ApiResponse(false, "Error retrieving security logs: " + e.getMessage()));
        }
    }

    /**
     * Endpoint de prueba para verificar acceso admin
     */
    @GetMapping("/test")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ResponseEntity<?> testAdminAccess(@CurrentUser UserPrincipal currentUser) {
        return ResponseEntity.ok(Map.of(
                "message", "¡Acceso ADMIN concedido!",
                "user", currentUser.getEmail(),
                "roles", currentUser.getAuthorities().stream()
                        .map(authority -> authority.getAuthority())
                        .collect(Collectors.toList()),
                "timestamp", System.currentTimeMillis()));
    }
}