package com.security.controller;

import com.security.service.SecurePasswordResetService;
import com.security.dto.request.PasswordResetRequest;
import com.security.dto.request.ResetPasswordRequest;
import com.security.dto.response.ApiResponse;
import com.security.exception.AuthFlowException;
import com.security.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class SecurePasswordResetController {

    private static final Logger logger = LoggerFactory.getLogger(SecurePasswordResetController.class);

    @Autowired
    private SecurePasswordResetService passwordResetService;

    @PostMapping("/password-reset/request")
    public ResponseEntity<ApiResponse> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request,
            HttpServletRequest httpRequest) {

        try {
            String clientIp = getClientIpAddress(httpRequest);
            String userAgent = httpRequest.getHeader("User-Agent");

            logger.info("Password reset request from IP: {} for email pattern: {}",
                    LogSanitizer.sanitize(clientIp), LogSanitizer.maskEmail(request.getEmail()));

            passwordResetService.requestPasswordResetFromController(
                    request.getEmail(),
                    clientIp,
                    userAgent);

            return ResponseEntity.ok(new ApiResponse(
                    true,
                    "Si tu email está registrado, recibirás un enlace de recuperación dentro de unos minutos."));

        } catch (AuthFlowException e) {
            return ResponseEntity.status(e.getStatus()).body(new ApiResponse(
                    false,
                    e.getMessage()));

        } catch (SecurityException e) {
            logger.warn("Security restriction for password reset: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(429).body(new ApiResponse(
                    false,
                    "Has excedido el límite de intentos de recuperación. Inténtalo más tarde."));

        } catch (Exception e) {
            logger.error("Error processing password reset request: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(new ApiResponse(
                    false,
                    "Error interno del servidor. Inténtalo más tarde."));
        }
    }

    @GetMapping("/password-reset/verify/{token}")
    public ResponseEntity<ApiResponse> verifyResetToken(@PathVariable String token) {
        try {
            boolean isValid = passwordResetService.isTokenValid(token);

            if (isValid) {
                return ResponseEntity.ok(new ApiResponse(
                        true,
                        "Token válido. Puedes proceder a cambiar tu contraseña."));
            } else {
                return ResponseEntity.badRequest().body(new ApiResponse(
                        false,
                        "Token inválido o expirado. Solicita un nuevo enlace de recuperación."));
            }

        } catch (Exception e) {
            logger.error("Error verifying reset token: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(new ApiResponse(
                    false,
                    "Error interno del servidor."));
        }
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<ApiResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest) {

        try {
            String clientIp = getClientIpAddress(httpRequest);
            String userAgent = httpRequest.getHeader("User-Agent");

            logger.info("Password reset confirmation from IP: {}", LogSanitizer.sanitize(clientIp));

            boolean result = passwordResetService.resetPasswordFromController(
                    request.getToken(),
                    request.getNewPassword(),
                    clientIp,
                    userAgent);

            if (result) {
                return ResponseEntity.ok(new ApiResponse(
                        true,
                        "Contraseña restablecida exitosamente. Ya puedes iniciar sesión."));
            } else {
                return ResponseEntity.badRequest().body(new ApiResponse(
                        false,
                        "Token inválido, expirado o ya utilizado. Solicita un nuevo enlace."));
            }

        } catch (SecurityException e) {
            logger.warn("Security restriction for password reset confirmation: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(429).body(new ApiResponse(
                    false,
                    "Demasiados intentos de restablecimiento. Inténtalo más tarde."));

        } catch (Exception e) {
            logger.error("Error confirming password reset: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(new ApiResponse(
                    false,
                    "Error interno del servidor. Inténtalo más tarde."));
        }
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        String xRealIp = request.getHeader("X-Real-IP");
        String xClientIp = request.getHeader("X-Client-IP");
        String cfConnectingIp = request.getHeader("CF-Connecting-IP");

        if (isValidIp(cfConnectingIp)) {
            return cfConnectingIp;
        }
        if (isValidIp(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        if (isValidIp(xRealIp)) {
            return xRealIp;
        }
        if (isValidIp(xClientIp)) {
            return xClientIp;
        }
        return request.getRemoteAddr();
    }

    private boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            return false;
        }
        return ip.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$") ||
                ip.matches("^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$");
    }
}
