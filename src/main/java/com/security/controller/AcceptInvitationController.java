package com.security.controller;

import com.security.dto.admin.AcceptInvitationRequest;
import com.security.dto.admin.InvitationInfoDto;
import com.security.service.admin.StaffInvitationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Controller PÚBLICO (sin JWT) para que el empleado acepte su invitación.
 * Está en la whitelist de Spring Security.
 *
 * SEGURIDAD:
 * - Valida formato del token antes de consultar BD.
 * - Respuestas uniformes para prevenir enumeración.
 * - Logs sanitizados (no se loguea el token completo).
 */
@RestController
@RequestMapping("/api/auth/accept-invitation")
@Slf4j
public class AcceptInvitationController {

    @Autowired
    private StaffInvitationService invitationService;

    /**
     * Patrón válido para tokens Base64 URL-safe generados por SecureRandom (32
     * bytes → ~43 chars).
     * Acepta a-z, A-Z, 0-9, - y _ (Base64 URL-safe sin padding), longitud 20-80.
     */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{20,80}$");

    /**
     * GET /api/auth/accept-invitation/validate/{token}
     * Valida el token y devuelve info para pre-poblar el formulario.
     * SEGURIDAD: Valida formato del token; respuesta uniforme en caso de error.
     */
    @GetMapping("/validate/{token}")
    public ResponseEntity<InvitationInfoDto> validate(@PathVariable String token) {
        // Sanitizar: rechazar tokens con formato inválido sin golpear la BD
        if (!TOKEN_PATTERN.matcher(token).matches()) {
            log.warn("Token con formato inválido recibido en validate (longitud={})", token.length());
            // Respuesta idéntica a "no encontrado" para no revelar que el formato es malo
            return ResponseEntity.badRequest().build();
        }

        try {
            InvitationInfoDto info = invitationService.validateToken(token);
            // Enmascarar email parcialmente antes de enviar al frontend
            InvitationInfoDto safeInfo = new InvitationInfoDto(
                    info.firstName(),
                    info.lastName(),
                    maskEmail(info.email()),
                    info.roleNames());
            return ResponseEntity.ok(safeInfo);
        } catch (Exception ex) {
            log.warn("Validación de token de invitación fallida: {}", ex.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * POST /api/auth/accept-invitation/{token}
     * Acepta la invitación: crea usuario con la contraseña del empleado.
     * SEGURIDAD: Valida formato del token; respuesta uniforme en caso de error.
     */
    @PostMapping("/{token}")
    public ResponseEntity<Map<String, String>> accept(
            @PathVariable String token,
            @RequestBody @Valid AcceptInvitationRequest req) {

        if (!TOKEN_PATTERN.matcher(token).matches()) {
            log.warn("Token con formato inválido recibido en accept (longitud={})", token.length());
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Enlace de invitación inválido o expirado."));
        }

        try {
            invitationService.acceptInvitation(token, req);
            return ResponseEntity.ok(Map.of(
                    "message", "Cuenta activada exitosamente. Ya puedes iniciar sesión."));
        } catch (Exception ex) {
            log.warn("Aceptación de invitación fallida: {}", ex.getMessage());
            // Respuesta genérica — no revelar si el token existe, expiró, o ya fue usado
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Enlace de invitación inválido o expirado."));
        }
    }

    /**
     * Enmascara un email para evitar exposición innecesaria: j***@gm***.com
     */
    private String maskEmail(String email) {
        if (email == null)
            return "***";
        int at = email.indexOf('@');
        if (at <= 0)
            return "***";

        String localPart = email.substring(0, at);
        String domainPart = email.substring(at + 1);

        String maskedLocal = localPart.charAt(0) + "***";

        int dot = domainPart.lastIndexOf('.');
        String maskedDomain;
        if (dot > 2) {
            maskedDomain = domainPart.substring(0, 2) + "***" + domainPart.substring(dot);
        } else {
            maskedDomain = "***" + (dot >= 0 ? domainPart.substring(dot) : "");
        }

        return maskedLocal + "@" + maskedDomain;
    }
}
