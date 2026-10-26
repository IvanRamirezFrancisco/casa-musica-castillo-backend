package com.security.controller;

import java.util.Optional;

import com.security.dto.response.ApiResponse;
import com.security.dto.response.JwtAuthResponse;
import com.security.dto.response.UserResponse;
import com.security.security.JwtTokenProvider;
import com.security.entity.User;
import com.security.security.CurrentUser;
import com.security.security.UserPrincipal;
import com.security.service.TwoFactorService;
import com.security.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.security.service.BackupCodeService;

@RestController
@RequestMapping("/api/2fa")
public class TwoFactorController {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorController.class);

    @Autowired
    private TwoFactorService twoFactorService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private UserService userService;

    // ===== GOOGLE AUTHENTICATOR =====

    @PostMapping("/google/enable")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse> enableGoogleAuthenticator(@AuthenticationPrincipal UserPrincipal userPrincipal) {
        try {
            Map<String, Object> setupInfo = twoFactorService.setupGoogleAuthenticatorComplete(userPrincipal.getId());
            return ResponseEntity
                    .ok(new ApiResponse(true, "Google Authenticator setup initiated. Scan QR code.", setupInfo));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación"));
        }
    }

    @PostMapping("/google/disable")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> disableGoogleAuthenticator(@CurrentUser UserPrincipal userPrincipal) {
        try {
            twoFactorService.disableSpecificTwoFactor(userPrincipal.getId(), "GOOGLE_AUTHENTICATOR");
            return ResponseEntity.ok(new ApiResponse(true,
                    "Google Authenticator desactivado exitosamente"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    // ===== EMAIL 2FA =====

    @PostMapping("/email/enable")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> enableEmail2FA(@CurrentUser UserPrincipal userPrincipal) {
        try {
            twoFactorService.enableEmailTwoFactor(userPrincipal.getId());
            return ResponseEntity.ok(new ApiResponse(true,
                    "Verificación por Email activada exitosamente"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    @PostMapping("/email/disable")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> disableEmail2FA(@CurrentUser UserPrincipal userPrincipal) {
        try {
            twoFactorService.disableSpecificTwoFactor(userPrincipal.getId(), "EMAIL");
            return ResponseEntity.ok(new ApiResponse(true,
                    "Verificación por Email desactivada exitosamente"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    @PostMapping("/google/setup")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> setupGoogleAuthenticator(@CurrentUser UserPrincipal userPrincipal) {
        try {
            Map<String, Object> setupData = twoFactorService.setupGoogleAuthenticatorComplete(userPrincipal.getId());
            return ResponseEntity.ok(new ApiResponse(true,
                    "Google Authenticator configurado exitosamente. Escanea el QR con tu app y confirma con un código de 6 dígitos.",
                    setupData));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación"));
        }
    }

    @GetMapping("/google/qrcode")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getQRCode(@CurrentUser UserPrincipal userPrincipal) {
        return ResponseEntity.ok(new ApiResponse(true, "QR de enrollment pendiente", twoFactorService.getPendingGoogleEnrollment(userPrincipal.getId())));
    }

    @GetMapping("/google/manual-code")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getManualCode(@CurrentUser UserPrincipal userPrincipal) {
        return ResponseEntity.ok(new ApiResponse(true, "Clave de enrollment pendiente", twoFactorService.getPendingGoogleManualEntry(userPrincipal.getId())));
    }

    @Autowired
    private com.security.service.LoginChallengeService loginChallenges;
    @Autowired
    private com.security.service.LoginSecurityService loginSecurity;

    @PostMapping("/send-login-code")
    public ResponseEntity<?> sendLoginCode(@jakarta.validation.Valid @RequestBody com.security.dto.request.LoginFactorRequest request) {
        loginChallenges.send(request);
        return ResponseEntity.ok(new ApiResponse(true, "Código enviado"));
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verifyTwoFactor(@jakarta.validation.Valid @RequestBody com.security.dto.request.LoginFactorRequest request,
            HttpServletRequest httpRequest) {
        JwtAuthResponse response = loginChallenges.verify(request, httpRequest);
        loginSecurity.clearAfterSuccessfulLogin(response.getUser().getEmail());
        return ResponseEntity.ok(new ApiResponse(true, "Autenticación exitosa", response));
    }

    @PostMapping("/google/confirm")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> confirmGoogleAuthenticator(
            @CurrentUser UserPrincipal userPrincipal,
            @RequestBody Map<String, String> request) {
        try {
            String code = request.get("code");
            if (code == null || code.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Código de verificación requerido"));
            }

            if (!code.matches("\\d{6}")) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "El código debe tener 6 dígitos"));
            }

            boolean isValid = twoFactorService.confirmGoogleAuthenticator(userPrincipal.getId(), code);

            if (isValid) {
                Map<String, Object> result = new HashMap<>();
                result.put("message", "Google Authenticator habilitado exitosamente!");
                result.put("googleAuthEnabled", true);
                result.put("info", "Puedes generar códigos de respaldo por separado si lo deseas desde el dashboard.");

                return ResponseEntity.ok(new ApiResponse(true,
                        "Google Authenticator habilitado exitosamente!", result));
            } else {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false,
                                "Código de verificación inválido. Verifica que la hora de tu dispositivo sea correcta."));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación"));
        }
    }

    // ===== EMAIL 2FA =====

    @PostMapping("/email/send")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> sendEmailCode(@CurrentUser UserPrincipal userPrincipal) {
        try {
            twoFactorService.sendEmailCode(userPrincipal.getId());
            return ResponseEntity.ok(new ApiResponse(true,
                    "Verification code sent to your email"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    @PostMapping("/email/verify")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> verifyEmailCode(
            @CurrentUser UserPrincipal userPrincipal,
            @RequestBody Map<String, String> request) {
        try {
            String code = request.get("code");
            if (code == null || code.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Verification code is required"));
            }

            boolean isValid = twoFactorService.verifyEmailCode(userPrincipal.getId(), code);

            if (isValid) {
                return ResponseEntity.ok(new ApiResponse(true,
                        "Email verification successful!"));
            } else {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Invalid or expired verification code"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    // ===== GENERAL =====

    @PostMapping("/disable")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> disableTwoFactor(@CurrentUser UserPrincipal userPrincipal) {
        try {
            twoFactorService.disableTwoFactor(userPrincipal.getId());
            return ResponseEntity.ok(new ApiResponse(true,
                    "Two-factor authentication disabled successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getTwoFactorStatus(@CurrentUser UserPrincipal userPrincipal) {
        try {
            User user = userService.getUserById(userPrincipal.getId());

            Map<String, Object> status = new HashMap<>();
            status.put("enabled", user.getTwoFactorEnabled() != null ? user.getTwoFactorEnabled() : false);
            status.put("type", user.getTwoFactorType() != null ? user.getTwoFactorType().toString() : "none");
            status.put("hasSecret", user.getTwoFactorSecret() != null);

            return ResponseEntity.ok(new ApiResponse(true,
                    "Two-factor status retrieved", status));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    // ===== NUEVOS ENDPOINTS PARA MÚLTIPLES MÉTODOS 2FA =====

    @PostMapping("/disable/{method}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> disableSpecificTwoFactor(
            @PathVariable String method,
            @CurrentUser UserPrincipal userPrincipal) {
        try {
            twoFactorService.disableSpecificTwoFactor(userPrincipal.getId(), method);
            return ResponseEntity.ok(new ApiResponse(true,
                    method + " two-factor authentication disabled successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    @GetMapping("/methods")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getAvailableTwoFactorMethods(@CurrentUser UserPrincipal userPrincipal) {
        try {
            Map<String, Boolean> methods = twoFactorService.getAvailableTwoFactorMethods(userPrincipal.getId());
            return ResponseEntity.ok(new ApiResponse(true,
                    "Available 2FA methods retrieved", methods));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    @GetMapping("/dashboard-summary")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getDashboardSummary(@CurrentUser UserPrincipal userPrincipal) {
        try {
            User user = userService.getUserById(userPrincipal.getId());
            Map<String, Boolean> methods = twoFactorService.getAvailableTwoFactorMethods(userPrincipal.getId());

            Map<String, Object> summary = new HashMap<>();
            summary.put("methods", methods);
            summary.put("twoFactorEnabled", user.getTwoFactorEnabled() != null ? user.getTwoFactorEnabled() : false);

            Map<String, String> methodInfo = new HashMap<>();
            methodInfo.put("GOOGLE_AUTHENTICATOR", "Autenticación con app móvil (Google Authenticator, Authy, etc.)");
            methodInfo.put("SMS", "Códigos por mensaje de texto");
            methodInfo.put("EMAIL", "Códigos por correo electrónico");
            methodInfo.put("BACKUP_CODES", "Códigos de respaldo de un solo uso");
            summary.put("methodDescriptions", methodInfo);

            return ResponseEntity.ok(new ApiResponse(true,
                    "Dashboard 2FA summary retrieved", summary));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    @Autowired
    private BackupCodeService backupCodeService;

    // ===== BACKUP CODES =====

    @GetMapping("/backup-codes/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getBackupCodesStatus(@CurrentUser UserPrincipal userPrincipal) {
        try {
            Map<String, Object> status = backupCodeService.getBackupCodeStats(userPrincipal.getId());
            return ResponseEntity.ok(new ApiResponse(true,
                    "Backup codes status retrieved", status));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    @PostMapping("/backup-codes/generate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> generateBackupCodes(@CurrentUser UserPrincipal userPrincipal) {
        try {
            List<String> backupCodes = backupCodeService.generateBackupCodes(userPrincipal.getId());

            Map<String, Object> result = new HashMap<>();
            result.put("codes", backupCodes);
            result.put("count", backupCodes.size());
            result.put("createdAt", System.currentTimeMillis());
            result.put("warning",
                    "Guarda estos códigos en un lugar seguro. No se pueden recuperar y solo se pueden usar una vez.");
            result.put("instructions",
                    "Cada código solo se puede usar una vez. Guárdalos en un lugar seguro y accesible.");

            return ResponseEntity.ok(new ApiResponse(true,
                    "Backup codes generated successfully. Save them securely!", result));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    @PostMapping("/backup-codes/verify")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> verifyBackupCode(@CurrentUser UserPrincipal userPrincipal,
            @RequestBody Map<String, String> request) {
        try {
            String code = request.get("code");
            if (code == null || code.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Backup code is required"));
            }

            boolean isValid = backupCodeService.verifyBackupCode(userPrincipal.getId(), code);

            if (isValid) {
                Map<String, Object> stats = backupCodeService.getBackupCodeStats(userPrincipal.getId());

                Map<String, Object> result = new HashMap<>();
                result.put("valid", true);
                result.put("remainingCodes", stats.get("available"));

                if ((Long) stats.get("available") == 0) {
                    result.put("warning",
                            "¡Este era tu último código de backup! Genera nuevos códigos inmediatamente.");
                } else if ((Long) stats.get("available") <= 2) {
                    result.put("warning", "Te quedan pocos códigos de backup. Considera generar nuevos.");
                }

                return ResponseEntity.ok(new ApiResponse(true,
                        "Backup code verified successfully", result));
            } else {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Invalid or already used backup code"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }

    @PostMapping("/backup-codes/disable")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> disableBackupCodes(@CurrentUser UserPrincipal userPrincipal) {
        try {
            backupCodeService.disableBackupCodes(userPrincipal.getId());
            return ResponseEntity.ok(new ApiResponse(true,
                    "Backup codes disabled successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No se pudo completar la operación 2FA"));
        }
    }
}
