package com.security.controller;

import com.security.dto.request.ResendVerificationRequest;
import com.security.dto.request.LoginRequest;
import com.security.dto.request.RegisterRequest;
import com.security.dto.request.VerifyEmailRequest;
import com.security.dto.response.ApiResponse;
import com.security.dto.response.JwtAuthResponse;
import com.security.dto.response.UserResponse;
import com.security.entity.User;
import com.security.exception.AuthFlowException;
import com.security.exception.BadRequestException;
import com.security.security.JwtTokenProvider;
import com.security.service.AuthService;
import com.security.service.EmailService;
import com.security.service.LoginSecurityService;
import com.security.service.PasswordResetService;
import com.security.service.SessionManagementService;
import com.security.service.UserService;
import com.security.service.VerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private AuthService authService;

    @Autowired
    private VerificationService verificationService;

    @Autowired
    private UserService userService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private LoginSecurityService loginSecurityService;

    @Autowired
    private SessionManagementService sessionManagementService;

    @Autowired
    private org.springframework.security.authentication.AuthenticationManager authenticationManager;

    @Autowired
    private com.security.service.LoginChallengeService loginChallenges;

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@org.springframework.security.core.annotation.AuthenticationPrincipal com.security.security.UserPrincipal principal) {
        return ResponseEntity.ok(userService.getUserResponseById(principal.getId()));
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@Valid @RequestBody RegisterRequest registerRequest) {
        try {
            UserResponse user = authService.registerUser(registerRequest);
            return ResponseEntity.ok(new ApiResponse(true,
                    "Registro completado. Hemos enviado un correo de verificación para activar tu cuenta.", user));
        } catch (AuthFlowException ex) {
            return ResponseEntity.status(ex.getStatus())
                    .body(new ApiResponse(false, ex.getMessage()));
        } catch (Exception e) {
            log.warn("Fallo en registro de usuario: {}", e.getClass().getSimpleName());
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la solicitud"));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request) {
        try {
            String email = loginRequest.getEmail() != null ? loginRequest.getEmail().trim() : "";
            String clientIp = getClientIP(request);

            // ===== VERIFICACIÓN DE BLOQUEO POR FUERZA BRUTA =====
            if (loginSecurityService.isAccountLocked(email)) {
                long remainingMinutes = loginSecurityService.getLockoutRemainingMinutes(email);
                long remainingSeconds = loginSecurityService.getLockoutRemainingSeconds(email);

                String message = String.format(
                        "Cuenta bloqueada temporalmente por múltiples intentos fallidos. " +
                                "Intenta de nuevo en %d minutos.",
                        remainingMinutes > 0 ? remainingMinutes : 1);

                Map<String, Object> errorData = new HashMap<>();
                errorData.put("message", message);
                errorData.put("remainingTimeSeconds", remainingSeconds);
                errorData.put("remainingTimeMinutes", remainingMinutes);

                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(errorData);
            }

            if (loginSecurityService.isAccountLocked(clientIp)) {
                long remainingMinutes = loginSecurityService.getLockoutRemainingMinutes(clientIp);
                long remainingSeconds = loginSecurityService.getLockoutRemainingSeconds(clientIp);

                String message = String.format(
                        "Demasiados intentos desde esta IP. Intenta de nuevo en %d minutos.",
                        remainingMinutes > 0 ? remainingMinutes : 1);

                Map<String, Object> errorData = new HashMap<>();
                errorData.put("message", message);
                errorData.put("remainingTimeSeconds", remainingSeconds);
                errorData.put("remainingTimeMinutes", remainingMinutes);

                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(errorData);
            }

            // ===== AUTENTICACIÓN =====
            org.springframework.security.core.Authentication authenticated;
            try {
                authenticated = authenticationManager.authenticate(
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(email, loginRequest.getPassword()));
            } catch (org.springframework.security.authentication.DisabledException disabledEx) {
                log.warn("Login denegado: cuenta no verificada o deshabilitada");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ApiResponse(false,
                                "Cuenta no verificada. Por favor verifica tu correo electrónico antes de iniciar sesión."));
            } catch (org.springframework.security.core.AuthenticationException ex) {
                loginSecurityService.recordFailedAttempt(email);
                loginSecurityService.recordFailedAttempt(clientIp);
                return ResponseEntity.status((loginSecurityService.isAccountLocked(email) || loginSecurityService.isAccountLocked(clientIp)) ? 429 : 401)
                        .body(new ApiResponse(false, "Credenciales inválidas"));
            }

            User user = userService.getUserByEmail(email);

            // ===== VERIFICAR SI LA CUENTA ESTÁ HABILITADA =====
            if (!Boolean.TRUE.equals(user.getEnabled())) {
                log.warn("Login denegado: cuenta no verificada para usuario ID {}", user.getId());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ApiResponse(false,
                                "Cuenta no verificada. Por favor verifica tu correo electrónico antes de iniciar sesión."));
            }

            UserResponse userResponse = userService.convertToUserResponse(user);

            boolean hasGoogleAuth = Boolean.TRUE.equals(user.getGoogleAuthEnabled());
            boolean hasEmail2FA = Boolean.TRUE.equals(user.getEmailEnabled());
            boolean requiresTwoFactor = hasGoogleAuth || hasEmail2FA;

            if (requiresTwoFactor) {
                return ResponseEntity.ok(new ApiResponse(true, "Two-factor authentication required", loginChallenges.create(user.getId())));
            }

            com.security.security.UserPrincipal userPrincipal = com.security.security.UserPrincipal.create(user);

            org.springframework.security.authentication.UsernamePasswordAuthenticationToken authToken = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    userPrincipal, null, userPrincipal.getAuthorities());

            String token = jwtTokenProvider.generateToken(authToken, request);
            loginSecurityService.clearFailedAttempts(email);
            loginSecurityService.clearFailedAttempts(clientIp);

            long activeSessions = sessionManagementService.getActiveSessionCount(user.getEmail());

            JwtAuthResponse jwtResponse = new JwtAuthResponse();
            jwtResponse.setAccessToken(token);
            jwtResponse.setTokenType("Bearer");
            jwtResponse.setUser(userResponse);

            Map<String, Object> sessionInfo = new HashMap<>();
            sessionInfo.put("activeSessions", activeSessions);
            sessionInfo.put("maxSessions", sessionManagementService.getMaxConcurrentSessions());
            sessionInfo.put("sessionInfo", "Sesiones activas: " + activeSessions + "/3");

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("jwtResponse", jwtResponse);
            responseData.put("sessionManagement", sessionInfo);

            return ResponseEntity.ok(new ApiResponse(true, "Inicio de sesión exitoso", responseData));
        } catch (AuthFlowException ex) {
            throw ex;
        } catch (Exception e) {
            String errorMessage = traducirError(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, errorMessage));
        }
    }

    private String traducirError(String mensaje) {
        if (mensaje == null) {
            return "Error interno del servidor. Por favor intenta de nuevo.";
        }
        if (mensaje.contains("Transaction silently rolled back")) {
            return "Error temporal en el servidor. Por favor intenta de nuevo.";
        }
        if (mensaje.contains("Connection refused") || mensaje.contains("connect")) {
            return "Error de conexión con el servidor. Por favor intenta más tarde.";
        }
        if (mensaje.contains("timeout") || mensaje.contains("Timeout")) {
            return "El servidor tardó demasiado en responder. Intenta de nuevo.";
        }
        if (mensaje.contains("Unauthorized") || mensaje.contains("401")) {
            return "Credenciales inválidas. Verifica tu correo y contraseña.";
        }
        if (mensaje.contains("locked") || mensaje.contains("bloqueada")) {
            return "Cuenta temporalmente bloqueada";
        }
        return "Ocurrió un error inesperado. Por favor intenta de nuevo.";
    }

    @PostMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        try {
            log.debug("Procesando verificación de email");
            userService.verifyEmailToken(request.getToken());
            log.info("Email verificado exitosamente");
            return ResponseEntity
                    .ok(new ApiResponse(true, "¡Email verificado exitosamente! Ya puedes iniciar sesión."));
        } catch (BadRequestException | IllegalArgumentException e) {
            log.warn("Token de verificación de email inválido o expirado");
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "El enlace de verificación es inválido o ha expirado."));
        } catch (Exception e) {
            log.error("Error verificando email: {}", e.getClass().getSimpleName());
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "El enlace de verificación es inválido o ha expirado."));
        }
    }

    @GetMapping("/verify")
    public ResponseEntity<?> verifyAccount(@RequestParam("token") String token) {
        String frontendBaseUrl = emailService != null
                ? emailService.getFrontendBaseUrl()
                : "http://localhost:4200";
        try {
            userService.verifyEmailToken(token);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendBaseUrl + "/login?verified=true"))
                    .build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendBaseUrl + "/login?error=verification_failed"))
                    .build();
        }
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        try {
            verificationService.resendVerificationEmail(request.getEmail());
            return ResponseEntity.ok(new ApiResponse(true,
                    "Si el correo está registrado y pendiente de verificación, recibirás un nuevo enlace de activación."));
        } catch (AuthFlowException ex) {
            return ResponseEntity.status(ex.getStatus())
                    .body(new ApiResponse(false, ex.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la solicitud"));
        }
    }

    @GetMapping("/check-username/{username}")
    public ResponseEntity<?> checkUsernameAvailability(@PathVariable("username") String username) {
        try {
            if (username == null || username.length() < 3 || username.length() > 50
                    || !username.matches("^[a-zA-Z0-9._-]+$")) {
                return ResponseEntity.ok(Map.of(
                        "available", false,
                        "message", "Formato de username inválido"));
            }

            boolean exists = userService.existsByUsername(username);
            Map<String, Object> response = new HashMap<>();
            response.put("available", !exists);
            response.put("message", !exists ? "Username is available" : "Username is already taken");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "No se pudo verificar la disponibilidad"));
        }
    }

    private String getClientIP(HttpServletRequest request) {
        String[] headersToCheck = {
                "X-Forwarded-For",
                "X-Real-IP",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP",
                "HTTP_X_FORWARDED_FOR",
                "HTTP_X_FORWARDED",
                "HTTP_X_CLUSTER_CLIENT_IP",
                "HTTP_CLIENT_IP",
                "HTTP_FORWARDED_FOR",
                "HTTP_FORWARDED",
                "HTTP_VIA",
                "REMOTE_ADDR"
        };

        for (String header : headersToCheck) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                if (ip.contains(",")) {
                    ip = ip.split(",")[0].trim();
                }
                return ip;
            }
        }

        return request.getRemoteAddr();
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        sessionManagementService.invalidateSession((String) request.getAttribute("authenticatedJti"));
        return ResponseEntity.ok(Map.of("message", "Sesión cerrada exitosamente"));
    }

    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutFromAllDevices(org.springframework.security.core.Authentication authentication) {
        sessionManagementService.invalidateAllUserSessions(authentication.getName());
        return ResponseEntity.ok(Map.of("message", "Todas las sesiones han sido cerradas"));
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> getActiveSessions(HttpServletRequest request, org.springframework.security.core.Authentication authentication) {
        String email = authentication.getName();
        String currentJti = (String) request.getAttribute("authenticatedJti");
        var sessions = sessionManagementService.getUserActiveSessions(email);
        Map<String, Object> response = new HashMap<>();
        response.put("activeSessions", sessions.size());
        response.put("maxAllowedSessions", sessionManagementService.getMaxConcurrentSessions());
        response.put("sessions", sessions.stream().map(session -> {
            Map<String, Object> info = new HashMap<>();
            info.put("id", session.getJwtTokenId());
            info.put("deviceInfo", extractDeviceInfo(session.getUserAgent()));
            info.put("ipAddress", session.getIpAddress());
            info.put("createdAt", session.getCreatedAt());
            info.put("lastActivity", session.getLastActivity());
            info.put("isCurrent", session.getJwtTokenId().equals(currentJti));
            return info;
        }).toList());
        return ResponseEntity.ok(new ApiResponse(true, "Sesiones obtenidas exitosamente", response));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> closeSpecificSession(@PathVariable String sessionId, org.springframework.security.core.Authentication authentication) {
        if (!sessionManagementService.revokeOwnedSession(sessionId, authentication.getName())) {
            return ResponseEntity.status(404).body(new ApiResponse(false, "Sesión no disponible"));
        }
        return ResponseEntity.ok(Map.of("message", "Sesión cerrada exitosamente"));
    }

    private String extractDeviceInfo(String userAgent) {
        if (userAgent == null)
            return "Desconocido";

        if (userAgent.contains("Mobile") || userAgent.contains("Android") || userAgent.contains("iPhone")) {
            return "Móvil";
        } else if (userAgent.contains("Tablet") || userAgent.contains("iPad")) {
            return "Tablet";
        } else {
            return "Escritorio";
        }
    }
}
