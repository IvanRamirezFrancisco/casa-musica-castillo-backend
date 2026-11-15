package com.security.service;

import com.security.entity.User;
import com.security.util.LogSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Servicio de email especializado en flujos de recuperacion de contrasena.
 * Delega el envio real a {@link EmailService} para reutilizar la cadena
 * Brevo API -> Resend API -> SMTP y no duplicar logica de transporte.
 */
@Service
public class PasswordResetEmailService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetEmailService.class);

    @Autowired
    private EmailService emailService;

    // ============================================================================
    // API PUBLICA
    // ============================================================================

    /**
     * Envia el enlace de restablecimiento de contrasena al usuario.
     *
     * @return true si el envio fue exitoso, false en caso contrario.
     */
    public boolean sendPasswordResetEmail(User user, String token) {
        try {
            emailService.sendPasswordResetEmail(user, token);
            logger.info("Email de reset enviado a: {}", LogSanitizer.maskEmail(user.getEmail()));
            return true;
        } catch (Exception e) {
            logger.error("Error enviando email de reset a {}: {}",
                    LogSanitizer.maskEmail(user.getEmail()), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Envia una notificacion de confirmacion cuando la contrasena ha sido cambiada.
     *
     * @return true si el envio fue exitoso, false en caso contrario.
     */
    public boolean sendPasswordChangedNotification(User user) {
        try {
            emailService.sendPasswordChangedEmail(user);
            logger.info("Notificacion de cambio de contrasena enviada a: {}",
                    LogSanitizer.maskEmail(user.getEmail()));
            return true;
        } catch (Exception e) {
            logger.error("Error enviando notificacion de cambio de contrasena a {}: {}",
                    LogSanitizer.maskEmail(user.getEmail()), e.getClass().getSimpleName());
            return false;
        }
    }
}
