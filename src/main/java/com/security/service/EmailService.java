package com.security.service;

import com.security.entity.User;
import com.security.exception.AuthFlowException;
import com.security.util.LogSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.internet.MimeMessage;
import java.util.HashMap;
import java.util.Map;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    // ── Dependencias ────────────────────────────────────────────────────────────
    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private RestTemplate restTemplate;

    // ── Configuración base ──────────────────────────────────────────────────────
    @Value("${app.base-url:http://localhost:4200}")
    private String baseUrl;

    /** Nombre visible del remitente, configurable por entorno */
    @Value("${app.email.sender-name:Casa de Música Castillo}")
    private String senderName;

    /**
     * Dirección "From" verificada en el proveedor de correo.
     * Debe ser un remitente verificado en la cuenta Brevo/Resend (no el usuario SMTP técnico).
     */
    @Value("${app.email.sender-address:${spring.mail.username:noreply@casamusicacastillo.com}}")
    private String senderAddress;

    // ── API Keys de proveedores ─────────────────────────────────────────────────
    @Value("${app.email.brevo.api-key:#{null}}")
    private String brevoApiKey;

    @Value("${app.email.resend.api-key:#{null}}")
    private String resendApiKey;

    @Value("${app.email.mailgun.api-key:#{null}}")
    private String mailgunApiKey;

    @Value("${app.email.mailgun.domain:#{null}}")
    private String mailgunDomain;

    // ── Constantes ──────────────────────────────────────────────────────────────
    private static final String BREVO_API_URL = "https://api.brevo.com/v3/smtp/email";
    private static final String RESEND_API_URL = "https://api.resend.com/emails";
    private static final String DEFAULT_SENDER_ADDRESS = "noreply@casamusicacastillo.com";
    private static final String DEFAULT_SENDER_NAME = "Casa de Música Castillo";

    // ============================================================================
    // API PÚBLICA
    // ============================================================================

    /**
     * Obtiene la URL base del frontend normalizada (sin barra final y con esquema http/https).
     */
    public String getFrontendBaseUrl() {
        String normalized = normalizeBaseUrl(baseUrl);
        return normalized.isEmpty() ? "http://localhost:4200" : normalized;
    }

    /**
     * Envía el correo de verificación de cuenta al usuario recién registrado.
     * Orden de prioridad: Brevo API (HTTPS 443) → Resend API (HTTPS 443) → SMTP JavaMail (fallback).
     */
    public void sendVerificationEmail(User user, String verificationToken) {
        logger.info("Preparando envío de correo de verificación para: {}", LogSanitizer.maskEmail(user.getEmail()));

        String frontendUrl = getFrontendBaseUrl();
        String verificationUrl = frontendUrl + "/verify-account?token=" + verificationToken;
        String subject = "Verificación de cuenta - Casa de Música Castillo";
        String html = buildVerificationTemplate(user.getFirstName(), verificationUrl);

        dispatchEmail(user, subject, html, "VERIFICATION");
    }

    /**
     * Envía el correo de recuperación de contraseña.
     * Orden de prioridad: Brevo API (HTTPS 443) → Resend API (HTTPS 443) → SMTP JavaMail (fallback).
     */
    public void sendPasswordResetEmail(User user, String token) {
        logger.info("Preparando envío de correo de recuperación para: {}", LogSanitizer.maskEmail(user.getEmail()));

        String frontendUrl = getFrontendBaseUrl();
        String resetUrl = frontendUrl + "/reset-password?token=" + token;
        String subject = "Recuperación de contraseña - Casa de Música Castillo";
        String html = buildPasswordResetTemplate(user.getFirstName(), resetUrl);

        dispatchEmail(user, subject, html, "PASSWORD_RESET");
    }

    /**
     * Envía el código 2FA por email.
     * Orden de prioridad: Brevo API (HTTPS 443) → Resend API (HTTPS 443) → SMTP JavaMail (fallback).
     */
    public void send2FACodeEmail(User user, String code) {
        logger.info("Preparando envío de código 2FA para: {}", LogSanitizer.maskEmail(user.getEmail()));

        String subject = "Código de verificación 2FA - Casa de Música Castillo";
        String html = build2FATemplate(user.getFirstName(), code);

        dispatchEmail(user, subject, html, "TWO_FACTOR_CODE");
    }

    /**
     * Envía un email HTML genérico a una dirección, usando la cadena de proveedores.
     */
    public void sendHtmlEmail(String to, String subject, String htmlContent) {
        User stub = new User();
        stub.setEmail(to);
        stub.setFirstName("");
        dispatchEmail(stub, subject, htmlContent, "GENERIC_HTML");
    }

    /**
     * Envía el correo de invitación a un nuevo empleado.
     */
    public void sendStaffInvitation(String toEmail, String firstName,
            String invitedByName, String token, int hoursValid) {
        logger.info("Preparando invitación de empleado para: {}", LogSanitizer.maskEmail(toEmail));

        String frontendUrl = getFrontendBaseUrl();
        String activationUrl = frontendUrl + "/auth/accept-invitation/" + token;

        String subject = "Invitación al Panel de Administración - Casa de Música Castillo";
        String html = buildStaffInvitationTemplate(firstName, invitedByName, activationUrl, hoursValid);

        sendHtmlEmail(toEmail, subject, html);
    }

    // ============================================================================
    // DESPACHO Y PROVEEDORES DE ENVÍO (privados)
    // ============================================================================

    private void dispatchEmail(User user, String subject, String htmlContent, String category) {
        if (user == null || !hasValue(user.getEmail())) {
            throw new IllegalArgumentException("Destinatario de correo inválido");
        }
        if (sendViaBrevoApi(user, subject, htmlContent, category)) {
            return;
        }
        if (sendViaResendApi(user, subject, htmlContent, category)) {
            return;
        }
        sendViaSmtp(user, subject, htmlContent, category);
    }

    /**
     * Envía un email usando la API HTTP de Brevo (v3) por HTTPS (puerto 443).
     * Compatible con Render Free (que bloquea los puertos SMTP 25, 465 y 587).
     */
    private boolean sendViaBrevoApi(User user, String subject, String htmlContent, String category) {
        if (!hasValue(brevoApiKey)) {
            logger.debug("Brevo API Key no configurada, omitiendo proveedor Brevo para tipo={}.", category);
            return false;
        }

        String resolvedFromEmail = resolveSenderAddress();
        String resolvedFromName = resolveSenderName();
        String recipientName = hasValue(user.getFirstName()) ? user.getFirstName().trim() : user.getEmail();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("api-key", brevoApiKey.trim());

        Map<String, Object> body = new HashMap<>();
        body.put("sender", Map.of("name", resolvedFromName, "email", resolvedFromEmail));
        body.put("to", new Map[] { Map.of("email", user.getEmail().trim(), "name", recipientName) });
        body.put("subject", subject);
        body.put("htmlContent", htmlContent);

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(
                        BREVO_API_URL, new HttpEntity<>(body, headers), String.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    logger.info("Correo aceptado por Brevo API (status={}, tipo={}, destinatario={})",
                            response.getStatusCode().value(), category, LogSanitizer.maskEmail(user.getEmail()));
                    return true;
                }
                logger.warn("Brevo API respondió con status no exitoso: {} para tipo={}",
                        response.getStatusCode().value(), category);
                return false;

            } catch (HttpClientErrorException e) {
                logger.warn("Brevo API rechazó la solicitud (HTTP {} para tipo={}, remitente={}). Verifique API key y remitente verificado en Brevo.",
                        e.getStatusCode().value(), category, LogSanitizer.maskEmail(resolvedFromEmail));
                return false;
            } catch (HttpServerErrorException | ResourceAccessException e) {
                logger.warn("Fallo temporal en Brevo API (intento {}/2, tipo={}, causa={})",
                        attempt, category, e.getClass().getSimpleName());
                if (attempt == 1) {
                    sleepQuietly(250);
                }
            } catch (Exception e) {
                logger.warn("Error inesperado en Brevo API para tipo={}: {}", category, e.getClass().getSimpleName());
                return false;
            }
        }
        return false;
    }

    /**
     * Envía un email usando la API HTTP de Resend por HTTPS (puerto 443).
     */
    private boolean sendViaResendApi(User user, String subject, String htmlContent, String category) {
        if (!hasValue(resendApiKey)) {
            logger.debug("Resend API Key no configurada, omitiendo proveedor Resend para tipo={}.", category);
            return false;
        }

        String resolvedFromEmail = resolveSenderAddress();
        String resolvedFromName = resolveSenderName();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(resendApiKey.trim());

        Map<String, Object> body = new HashMap<>();
        body.put("from", resolvedFromName + " <" + resolvedFromEmail + ">");
        body.put("to", new String[] { user.getEmail().trim() });
        body.put("subject", subject);
        body.put("html", htmlContent);

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(
                        RESEND_API_URL, new HttpEntity<>(body, headers), String.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    logger.info("Correo aceptado por Resend API (status={}, tipo={}, destinatario={})",
                            response.getStatusCode().value(), category, LogSanitizer.maskEmail(user.getEmail()));
                    return true;
                }
                logger.warn("Resend API respondió con status no exitoso: {} para tipo={}",
                        response.getStatusCode().value(), category);
                return false;

            } catch (HttpClientErrorException e) {
                logger.warn("Resend API rechazó la solicitud (HTTP {} para tipo={}).",
                        e.getStatusCode().value(), category);
                return false;
            } catch (HttpServerErrorException | ResourceAccessException e) {
                logger.warn("Fallo temporal en Resend API (intento {}/2, tipo={}, causa={})",
                        attempt, category, e.getClass().getSimpleName());
                if (attempt == 1) {
                    sleepQuietly(250);
                }
            } catch (Exception e) {
                logger.warn("Error inesperado en Resend API para tipo={}: {}", category, e.getClass().getSimpleName());
                return false;
            }
        }
        return false;
    }

    /**
     * Fallback final: envío via JavaMail/SMTP.
     * Nota: En Render Free los puertos SMTP salientes (25, 465, 587) están bloqueados por infraestructura,
     * por lo que en producción debe configurarse Brevo API o Resend API sobre HTTPS (443).
     * En desarrollo/pruebas locales permite entregar a un servidor o capturador SMTP local.
     */
    private void sendViaSmtp(User user, String subject, String htmlContent, String category) {
        String resolvedFromEmail = resolveSenderAddress();
        String resolvedFromName = resolveSenderName();

        logger.info("Intentando envío via SMTP (tipo={}, destinatario={})",
                category, LogSanitizer.maskEmail(user.getEmail()));
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(resolvedFromEmail, resolvedFromName);
            helper.setTo(user.getEmail().trim());
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
            logger.info("Correo aceptado por servidor SMTP (tipo={}, destinatario={})",
                    category, LogSanitizer.maskEmail(user.getEmail()));
        } catch (Exception e) {
            logger.error("Fallo total de entrega de correo (tipo={}, destinatario={}, causa={})",
                    category, LogSanitizer.maskEmail(user.getEmail()), e.getClass().getSimpleName());
            throw new AuthFlowException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo enviar el correo electrónico en este momento. Por favor intenta nuevamente más tarde.");
        }
    }

    // ============================================================================
    // UTILIDADES PRIVADAS
    // ============================================================================

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private String resolveSenderAddress() {
        if (hasValue(senderAddress) && senderAddress.contains("@")) {
            return senderAddress.trim();
        }
        return DEFAULT_SENDER_ADDRESS;
    }

    private String resolveSenderName() {
        return hasValue(senderName) ? senderName.trim() : DEFAULT_SENDER_NAME;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank())
            return "";
        String result = url.trim();
        if (!result.startsWith("http://") && !result.startsWith("https://")) {
            result = "https://" + result;
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String escapeHtml(String value) {
        if (value == null || value.isBlank()) {
            return "Usuario";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // ============================================================================
    // TEMPLATES HTML
    // ============================================================================

    private String buildVerificationTemplate(String userName, String verificationUrl) {
        String safeName = escapeHtml(userName);
        return """
                <!DOCTYPE html>
                <html lang="es">
                <head><meta charset="UTF-8">
                    <style>
                        body { margin:0; padding:0; background:#f4f4f4; font-family:Arial,sans-serif; }
                        .container { max-width:600px; margin:40px auto; background:#fff; border-radius:8px; overflow:hidden; box-shadow:0 2px 8px rgba(0,0,0,.1); }
                        .header { background:#4CAF50; color:#fff; padding:28px 20px; text-align:center; }
                        .header h1 { margin:0; font-size:24px; }
                        .content { padding:32px 28px; color:#333; }
                        .btn { display:inline-block; background:#4CAF50; color:#fff; padding:14px 32px; border-radius:6px; text-decoration:none; font-weight:bold; margin:24px 0; }
                        .notice { background:#f9f9f9; border-left:4px solid #4CAF50; padding:12px 16px; font-size:13px; color:#555; border-radius:4px; }
                        .link-fallback { font-size:12px; color:#666; word-break:break-all; margin-top:16px; }
                        .footer { padding:16px 28px; font-size:12px; color:#888; border-top:1px solid #eee; text-align:center; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header"><h1>¡Bienvenido/a, %s!</h1></div>
                        <div class="content">
                            <h2>Verifica tu correo electrónico</h2>
                            <p>Gracias por registrarte en Casa de Música Castillo. Para activar tu cuenta, haz clic en el botón de abajo:</p>
                            <a href="%s" class="btn">Verificar mi cuenta</a>
                            <div class="notice">
                                <strong>⏰ Este enlace expira en 24 horas y es de un solo uso.</strong><br>
                                Si no creaste esta cuenta, puedes ignorar este correo con seguridad.
                            </div>
                            <p class="link-fallback">Si el botón no funciona, copia y pega este enlace en tu navegador:<br>%s</p>
                        </div>
                        <div class="footer">© 2026 Casa de Música Castillo · Este mensaje fue generado automáticamente.</div>
                    </div>
                </body>
                </html>
                """
                .formatted(safeName, verificationUrl, verificationUrl);
    }

    private String buildPasswordResetTemplate(String userName, String resetUrl) {
        String safeName = escapeHtml(userName);
        return """
                <!DOCTYPE html>
                <html lang="es">
                <head><meta charset="UTF-8">
                    <style>
                        body { margin:0; padding:0; background:#f4f4f4; font-family:Arial,sans-serif; }
                        .container { max-width:600px; margin:40px auto; background:#fff; border-radius:8px; overflow:hidden; box-shadow:0 2px 8px rgba(0,0,0,.1); }
                        .header { background:#e74c3c; color:#fff; padding:28px 20px; text-align:center; }
                        .header h1 { margin:0; font-size:24px; }
                        .content { padding:32px 28px; color:#333; text-align:center; }
                        .btn { display:inline-block; background:#e74c3c; color:#fff; padding:14px 32px; border-radius:6px; text-decoration:none; font-weight:bold; margin:24px 0; }
                        .warning { background:#fff5f5; border-left:4px solid #e74c3c; padding:12px 16px; font-size:13px; color:#555; border-radius:4px; text-align:left; }
                        .link-fallback { font-size:12px; color:#666; word-break:break-all; margin-top:16px; text-align:left; }
                        .footer { padding:16px 28px; font-size:12px; color:#888; border-top:1px solid #eee; text-align:center; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header"><h1>🔒 Recuperación de contraseña</h1></div>
                        <div class="content">
                            <h2>Hola, %s</h2>
                            <p>Recibimos una solicitud para restablecer la contraseña de tu cuenta en Casa de Música Castillo.</p>
                            <a href="%s" class="btn">Restablecer contraseña</a>
                            <p><strong>Este enlace expira en 1 hora y es de un solo uso.</strong></p>
                            <div class="warning">
                                ⚠️ Si no solicitaste este restablecimiento, ignora este correo.<br>
                                Tu contraseña actual permanece sin cambios.<br>
                                Nunca compartas este enlace con nadie.
                            </div>
                            <p class="link-fallback">Si el botón no funciona, copia y pega este enlace en tu navegador:<br>%s</p>
                        </div>
                        <div class="footer">© 2026 Casa de Música Castillo · Este mensaje fue generado automáticamente.</div>
                    </div>
                </body>
                </html>
                """
                .formatted(safeName, resetUrl, resetUrl);
    }

    private String build2FATemplate(String userName, String code) {
        String safeName = escapeHtml(userName);
        return """
                <!DOCTYPE html>
                <html lang="es">
                <head><meta charset="UTF-8">
                    <style>
                        body { margin:0; padding:0; background:#f4f4f4; font-family:Arial,sans-serif; }
                        .container { max-width:600px; margin:40px auto; background:#fff; border-radius:8px; overflow:hidden; box-shadow:0 2px 8px rgba(0,0,0,.1); }
                        .header { background:#667eea; color:#fff; padding:28px 20px; text-align:center; }
                        .header h1 { margin:0; font-size:24px; }
                        .content { padding:32px 28px; color:#333; text-align:center; }
                        .code { background:#f1f1f1; border:2px solid #667eea; border-radius:8px; padding:18px; font-family:monospace; font-size:32px; font-weight:bold; letter-spacing:8px; color:#333; margin:24px 0; }
                        .warning { background:#f9f9f9; border-left:4px solid #667eea; padding:12px 16px; font-size:13px; color:#555; border-radius:4px; text-align:left; }
                        .footer { padding:16px 28px; font-size:12px; color:#888; border-top:1px solid #eee; text-align:center; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header"><h1>🔐 Código de verificación</h1></div>
                        <div class="content">
                            <h2>Hola, %s</h2>
                            <p>Tu código de verificación de dos factores para Casa de Música Castillo es:</p>
                            <div class="code">%s</div>
                            <p>Este código expira en <strong>5 minutos</strong> y es de un solo uso.</p>
                            <div class="warning">
                                ⚠️ Si no solicitaste este código, ignora este correo.<br>
                                Nunca compartas este código con nadie.
                            </div>
                        </div>
                        <div class="footer">© 2026 Casa de Música Castillo · Este mensaje fue generado automáticamente.</div>
                    </div>
                </body>
                </html>
                """
                .formatted(safeName, code);
    }

    private String buildStaffInvitationTemplate(String firstName, String invitedByName,
            String activationUrl, int hoursValid) {
        String safeFirstName = escapeHtml(firstName);
        String safeInvitedBy = escapeHtml(invitedByName);
        return """
                <!DOCTYPE html>
                <html lang="es">
                <head><meta charset="UTF-8"></head>
                <body style="margin:0;padding:0;background:#f5f0eb;font-family:'Segoe UI',Arial,sans-serif;">
                  <div style="max-width:560px;margin:40px auto;background:#FDFCFB;border-radius:12px;border:1px solid #E8E2DC;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);">
                    <div style="background:linear-gradient(135deg,#722f37,#8a3545);padding:32px 40px;text-align:center;">
                      <h1 style="color:#fff;font-size:22px;margin:0;font-weight:600;">Casa de Música Castillo</h1>
                      <p style="color:rgba(255,255,255,0.8);margin:8px 0 0;font-size:14px;">Sistema de Administración</p>
                    </div>
                    <div style="padding:40px;">
                      <h2 style="color:#1A1A1A;font-size:18px;margin:0 0 16px;font-weight:600;">¡Hola, %s!</h2>
                      <p style="color:#6b7280;line-height:1.6;margin:0 0 24px;">
                        <strong style="color:#1A1A1A;">%s</strong>
                        te ha invitado a unirte al equipo de administración de Casa de Música Castillo.
                      </p>
                      <div style="text-align:center;margin:32px 0;">
                        <a href="%s" style="background:linear-gradient(135deg,#722f37,#8a3545);color:#fff;text-decoration:none;padding:14px 32px;border-radius:8px;font-size:15px;font-weight:600;display:inline-block;">
                          Activar mi cuenta
                        </a>
                      </div>
                      <div style="background:#f5f0eb;border-radius:8px;padding:16px;margin:24px 0;">
                        <p style="margin:0;font-size:13px;color:#6b7280;line-height:1.8;">
                          <span style="display:inline-block;width:18px;color:#722f37;font-weight:700;text-align:center;">&#9679;</span> Este enlace es válido por <strong>%d horas</strong>.<br>
                          <span style="display:inline-block;width:18px;color:#722f37;font-weight:700;text-align:center;">&#9679;</span> Solo puedes usar este enlace una vez.<br>
                          <span style="display:inline-block;width:18px;color:#722f37;font-weight:700;text-align:center;">&#9679;</span> Si no solicitaste esta invitación, ignora este correo.
                        </p>
                      </div>
                      <p style="font-size:12px;color:#9ca3af;margin:16px 0 0;word-break:break-all;">
                        Si el botón no funciona, copia este enlace:<br>
                        <a href="%s" style="color:#722f37;">%s</a>
                      </p>
                    </div>
                    <div style="padding:16px 40px;border-top:1px solid #E8E2DC;text-align:center;">
                      <p style="margin:0;font-size:11px;color:#9ca3af;">© 2026 Casa de Música Castillo · Mensaje automático</p>
                    </div>
                  </div>
                </body>
                </html>
                """
                .formatted(safeFirstName, safeInvitedBy, activationUrl, hoursValid, activationUrl, activationUrl);
    }
}
