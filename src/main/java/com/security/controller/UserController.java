package com.security.controller;

import com.security.validation.BcryptPasswordValidator;

import com.security.dto.request.ChangePasswordRequest;
import com.security.dto.request.SetInitialPasswordRequest;
import com.security.dto.response.ApiResponse;
import com.security.dto.response.UserResponse;
import com.security.entity.User;
import com.security.entity.Role;
import com.security.repository.RoleRepository;
import com.security.security.CurrentUser;
import com.security.security.UserPrincipal;
import com.security.service.GoogleAuthService;
import com.security.service.RefreshTokenService;
import com.security.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;


@RestController
@RequestMapping("/api/users")
// CORS se maneja globalmente en SecurityConfig
public class UserController {
        private static final Logger logger = LoggerFactory.getLogger(UserController.class);

        @Autowired
        private UserService userService;

        @Autowired
        private PasswordEncoder passwordEncoder;

        @Autowired
        private RoleRepository roleRepository;

        @Autowired(required = false)
        private GoogleAuthService googleAuthService;

        @Autowired(required = false)
        private RefreshTokenService refreshTokenService;

        /**
         * Obtener perfil del usuario actual autenticado
         */
        @GetMapping("/profile")
        @PreAuthorize("isAuthenticated()")
        public ResponseEntity<?> getUserProfile(@CurrentUser UserPrincipal userPrincipal) {
                try {
                        User user = userService.getUserById(userPrincipal.getId());

                        // Crear respuesta con datos del perfil
                        Map<String, Object> profileData = new HashMap<>();
                        profileData.put("id", user.getId());
                        profileData.put("email", user.getEmail());
                        profileData.put("firstName", user.getFirstName());
                        profileData.put("lastName", user.getLastName());
                        profileData.put("phone", user.getPhone());
                        profileData.put("enabled", user.getEnabled());
                        profileData.put("emailVerified", user.isEmailVerified());

                        // Estados 2FA
                        profileData.put("twoFactorEnabled", user.getTwoFactorEnabled());
                        profileData.put("googleAuthEnabled", user.getGoogleAuthEnabled());
                        profileData.put("emailEnabled", user.getEmailEnabled());
                        profileData.put("backupCodesEnabled", user.getBackupCodesEnabled());

                        // Timestamps
                        profileData.put("createdAt", user.getCreatedAt());
                        profileData.put("updatedAt", user.getUpdatedAt());

                        return ResponseEntity.ok(new ApiResponse(true,
                                        "User profile retrieved successfully", profileData));

                } catch (Exception e) {
                        return ResponseEntity.badRequest()
                                        .body(new ApiResponse(false,
                                                        "Error retrieving user profile: " + e.getMessage()));
                }
        }

        /**
         * Obtener configuración de seguridad del usuario
         */
        @GetMapping("/security-settings")
        @PreAuthorize("isAuthenticated()")
        public ResponseEntity<?> getSecuritySettings(@CurrentUser UserPrincipal userPrincipal) {
                try {
                        User user = userService.getUserById(userPrincipal.getId());

                        Map<String, Object> securityData = new HashMap<>();
                        securityData.put("twoFactorEnabled",
                                        user.getTwoFactorEnabled() != null ? user.getTwoFactorEnabled() : false);
                        securityData.put("googleAuthEnabled",
                                        user.getGoogleAuthEnabled() != null ? user.getGoogleAuthEnabled() : false);
                        securityData.put("emailEnabled",
                                        user.getEmailEnabled() != null ? user.getEmailEnabled() : false);
                        securityData.put("backupCodesEnabled",
                                        user.getBackupCodesEnabled() != null ? user.getBackupCodesEnabled() : false);
                        securityData.put("twoFactorType",
                                        user.getTwoFactorType() != null ? user.getTwoFactorType().toString() : null);
                        securityData.put("emailVerified", user.isEmailVerified());
                        securityData.put("passwordChangeRevokesSessions", true);
                        if (googleAuthService != null) {
                                securityData.putAll(googleAuthService.buildSecurityCapabilities(user));
                        } else {
                                boolean hasPassword = user.hasLocalPassword();
                                securityData.put("hasLocalPassword", hasPassword);
                                securityData.put("googleLinked", false);
                                securityData.put("googleConfigured", false);
                                securityData.put("canSetInitialPassword", false);
                                securityData.put("canChangePassword", hasPassword);
                                securityData.put("canLinkGoogle", false);
                                securityData.put("canUnlinkGoogle", false);
                                securityData.put("hasValidAuthMethod", hasPassword);
                                securityData.put("mfaActive", Boolean.TRUE.equals(user.getGoogleAuthEnabled())
                                                || Boolean.TRUE.equals(user.getEmailEnabled()));
                                securityData.put("requiresRecentAuth", true);
                        }

                        return ResponseEntity.ok(new ApiResponse(true,
                                        "Security settings retrieved successfully", securityData));

                } catch (Exception e) {
                        return ResponseEntity.badRequest()
                                        .body(new ApiResponse(false,
                                                        "Error retrieving security settings: " + e.getMessage()));
                }
        }

        /**
         * Cambiar contraseña para usuarios autenticados
         * Nota: Cualquier usuario autenticado puede cambiar su propia contraseña
         */
        @PostMapping("/change-password")
        @PreAuthorize("isAuthenticated()")
        public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                        @CurrentUser UserPrincipal userPrincipal) {
                userService.changeOwnPassword(userPrincipal.getId(), request.getCurrentPassword(), request.getNewPassword());
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                if (refreshTokenService != null) {
                        headers.add(org.springframework.http.HttpHeaders.SET_COOKIE,
                                        refreshTokenService.clearRefreshCookie().toString());
                }
                return ResponseEntity.ok().headers(headers)
                                .body(new ApiResponse(true, "Contraseña actualizada correctamente"));
        }

        /**
         * Configurar contraseña inicial para cuentas creadas únicamente con Google.
         */
        @PostMapping("/set-initial-password")
        @PreAuthorize("isAuthenticated()")
        public ResponseEntity<?> setInitialPassword(@Valid @RequestBody SetInitialPasswordRequest request,
                        @CurrentUser UserPrincipal userPrincipal) {
                userService.setInitialPassword(userPrincipal.getId(), request.reauthToken(), request.newPassword());
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                if (refreshTokenService != null) {
                        headers.add(org.springframework.http.HttpHeaders.SET_COOKIE,
                                        refreshTokenService.clearRefreshCookie().toString());
                }
                return ResponseEntity.ok().headers(headers)
                                .body(new ApiResponse(true, "Contraseña configurada correctamente"));
        }

        /**
         * Endpoint para crear el primer usuario ADMIN del sistema.
         * SEGURIDAD: Solo un administrador existente puede crear otro administrador.
         * Si no existe ningún admin aún, usar el script Flyway o una herramienta
         * de BD directamente.
         */
        @PostMapping("/create-admin")
        @PreAuthorize("hasRole('ROLE_ADMIN')")
        public ResponseEntity<?> createAdminUser(@RequestBody Map<String, String> request) {
                String password = request.get("password");
                if (password == null || password.isBlank() || !BcryptPasswordValidator.isWithinLimit(password)) {
                        return ResponseEntity.badRequest().body(new ApiResponse(false, "Contraseña inválida"));
                }
                try {
                        // Verificar si ya existe un admin con ese email
                        if (userService.existsByEmail(request.get("email"))) {
                                return ResponseEntity.badRequest()
                                                .body(new ApiResponse(false, "Ya existe un usuario con ese email"));
                        }

                        // Buscar o crear rol ADMIN
                        Role adminRole = roleRepository.findByName("ROLE_ADMIN")
                                        .orElseThrow(() -> new RuntimeException(
                                                        "Error: Admin role not found. Please run database migrations."));

                        // Crear usuario admin
                        User admin = new User();
                        admin.setFirstName(request.get("firstName"));
                        admin.setLastName(request.get("lastName"));
                        admin.setEmail(request.get("email"));
                        admin.setPassword(passwordEncoder.encode(password));
                        admin.setPhone(request.get("phone"));
                        admin.setEnabled(true);
                        admin.setTwoFactorEnabled(false);

                        // IMPORTANTE: Asignar rol ADMIN
                        Set<Role> roles = new HashSet<>();
                        roles.add(adminRole);
                        admin.setRoles(roles);

                        // Guardar el usuario
                        User savedAdmin = userService.save(admin);

                        Map<String, Object> response = new HashMap<>();
                        response.put("id", savedAdmin.getId());
                        response.put("email", savedAdmin.getEmail());
                        response.put("firstName", savedAdmin.getFirstName());
                        response.put("lastName", savedAdmin.getLastName());
                        response.put("roles", savedAdmin.getRoles().stream()
                                        .map(role -> role.getName().toString())
                                        .toArray());
                        response.put("message", "Usuario administrador creado exitosamente");

                        logger.info("Nuevo administrador creado: ID={}", savedAdmin.getId());

                        return ResponseEntity.ok(new ApiResponse(true,
                                        "Admin user created successfully", response));

                } catch (Exception e) {
                        logger.error("Error creando usuario admin", e);
                        return ResponseEntity.status(500)
                                        .body(new ApiResponse(false,
                                                        "Error creating admin user: " + e.getMessage()));
                }
        }
}
