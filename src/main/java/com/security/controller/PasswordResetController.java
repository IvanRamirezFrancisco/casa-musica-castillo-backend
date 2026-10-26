package com.security.controller;

import com.security.service.PasswordResetService;
import com.security.dto.request.ResetPasswordRequest;
import com.security.exception.RateLimitExceededException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;


@RestController
@RequestMapping("/api/auth")
// CORS se maneja globalmente en SecurityConfig
public class PasswordResetController {
    private static final Logger logger = LoggerFactory.getLogger(PasswordResetController.class);

    @Autowired
    private PasswordResetService passwordResetService;

    /**
     * Solicitar reset de contraseña con limitación de intentos (3 cada 5 minutos)
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestParam String email, HttpServletRequest request) {
        try {
            // Siempre ejecuta la lógica pero no revela si existe
            passwordResetService.requestPasswordReset(email, request);

            // Mensaje genérico independiente del resultado
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message",
                    "Si el email está registrado, recibirás un enlace de recuperación en tu bandeja de entrada."));

        } catch (RateLimitExceededException e) {
            // Manejo específico para rate limiting con información de tiempo
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "message", e.getMessage(),
                    "remainingTimeSeconds", e.getTotalSecondsLeft(),
                    "minutesLeft", e.getMinutesLeft(),
                    "secondsLeft", e.getSecondsLeft(),
                    "attemptCount", e.getAttemptCount(),
                    "maxAttempts", e.getMaxAttempts()));

        } catch (IllegalStateException e) {
            // Manejo para otros errores de estado
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "message", e.getMessage()));

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error interno del servidor. Intenta nuevamente más tarde."));
        }
    }

    /**
     * Validar token de reset
     */
    @GetMapping("/validate-reset-token")
    public ResponseEntity<?> validateResetToken(@RequestParam String token) {
        try {
            boolean isValid = passwordResetService.validateResetToken(token);

            if (isValid) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Token válido"));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "El enlace de reset ha expirado o es inválido. Solicita uno nuevo."));
            }

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error al validar el token"));
        }
    }

    /**
     * Resetear contraseña con token (desde email)
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        try {
            // Validar que los datos no estén vacíos
            if (request.getToken() == null || request.getToken().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Token requerido"));
            }

            if (request.getNewPassword() == null || request.getNewPassword().trim().length() < 8) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "La contraseña debe tener al menos 8 caracteres"));
            }

            boolean success = passwordResetService.resetPassword(request.getToken(), request.getNewPassword());

            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message",
                        "¡Contraseña actualizada exitosamente! Ya puedes iniciar sesión con tu nueva contraseña."));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "El enlace de reset ha expirado o es inválido. Solicita uno nuevo."));
            }

        } catch (Exception e) {
            logger.error("Error en reset-password: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error al actualizar la contraseña. Intenta nuevamente."));
        }
    }

}