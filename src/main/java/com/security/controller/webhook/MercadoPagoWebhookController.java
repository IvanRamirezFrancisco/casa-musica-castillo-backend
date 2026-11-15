package com.security.controller.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.dto.webhook.MercadoPagoWebhookRequest;
import com.security.service.MercadoPagoWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Controlador de webhooks de Mercado Pago.
 *
 * Endpoint público (sin JWT) protegido por validación HMAC del header x-signature.
 * No confiar en ningún dato del body sin antes validar la firma.
 *
 * Ruta: POST /api/webhooks/mercado-pago
 *
 * SEGURIDAD:
 * - Ningún dato del cuerpo se procesa antes de validar x-signature.
 * - La fuente de verdad del estado del pago es la API de Mercado Pago, no este body.
 * - Este endpoint NO marca ningún pago como PAID directamente.
 *
 * Respuestas HTTP:
 * - 200: Webhook procesado exitosamente, duplicado ignorado, o evento no-payment.
 * - 400: Payload inválido (sin data.id).
 * - 401: Firma HMAC inválida.
 * - 500: Error temporal al consultar API de Mercado Pago (permite reintento).
 */
@RestController
@RequestMapping("/api/webhooks/mercado-pago")
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoWebhookController {

    private final MercadoPagoWebhookService webhookService;
    private final ObjectMapper objectMapper;

    /**
     * Recibe y procesa notificaciones de Mercado Pago.
     *
     * Mercado Pago puede enviar el paymentId en:
     * - Query param:  ?data.id=12345&type=payment
     * - Body JSON:    { "data": { "id": "12345" }, "type": "payment" }
     * - Query param:  ?topic=payment (formato legacy)
     */
    @PostMapping(consumes = "application/json")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @RequestHeader(value = "x-signature", required = false) String xSignature,
            @RequestHeader(value = "x-request-id", required = false) String xRequestId,
            @RequestParam(value = "data.id", required = false) String dataIdQuery,
            @RequestParam(value = "type", required = false) String typeQuery,
            @RequestParam(value = "topic", required = false) String topicQuery,
            HttpServletRequest httpRequest) {

        // Leer raw body una sola vez para hash y deserialización
        String rawBody = readRawBody(httpRequest);

        // Deserializar body (tolerante a errores; puede ser null si body vacío)
        MercadoPagoWebhookRequest requestBody = null;
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                requestBody = objectMapper.readValue(rawBody, MercadoPagoWebhookRequest.class);
            } catch (Exception e) {
                log.warn("[WebhookController] Error deserializando body del webhook: {}", e.getMessage());
                // Continuamos — el controller puede operar con solo query params + firma
            }
        }

        log.info("[WebhookController] Webhook MP recibido. dataId={} type={} topic={} requestId={}",
                dataIdQuery, typeQuery, topicQuery, safeLog(xRequestId));

        try {
            webhookService.processWebhook(
                    requestBody,
                    xSignature,
                    xRequestId,
                    dataIdQuery,
                    typeQuery,
                    topicQuery,
                    rawBody
            );
            return ResponseEntity.ok(Map.of("status", "OK"));

        } catch (SecurityException e) {
            if ("WEBHOOK_SIGNATURE_INVALID".equals(e.getMessage())) {
                return ResponseEntity.status(401)
                        .body(Map.of("error", "Unauthorized", "message", "Firma inválida"));
            }
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Unauthorized"));

        } catch (IllegalArgumentException e) {
            if ("WEBHOOK_NO_DATA_ID".equals(e.getMessage())) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Bad Request", "message", "data.id es requerido"));
            }
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Bad Request", "message", e.getMessage()));

        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.startsWith("MP_API_ERROR_") || msg.startsWith("MP_SDK_ERROR"))) {
                // Error temporal de la API de Mercado Pago → 500 para permitir reintento
                log.error("[WebhookController] Error temporal consultando API MP: {}. MP reintentará.", msg);
                return ResponseEntity.status(502)
                        .body(Map.of("error", "Bad Gateway", "message", "Error temporal comunicando con Mercado Pago"));
            }
            log.error("[WebhookController] Error inesperado procesando webhook: {}", e.getMessage());
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Internal Server Error"));

        } catch (Exception e) {
            log.error("[WebhookController] Error inesperado: {}", e.getMessage());
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Internal Server Error"));
        }
    }

    /**
     * Lee el raw body de la petición.
     * Funciona con ContentCachingRequestWrapper (configurado en WebhookRequestCacheConfig)
     * para permitir que Spring también deserialice el body via Jackson.
     */
    private String readRawBody(HttpServletRequest request) {
        try {
            // Si el filtro envolvió la petición con ContentCachingRequestWrapper
            if (request instanceof org.springframework.web.util.ContentCachingRequestWrapper cached) {
                byte[] body = cached.getContentAsByteArray();
                if (body.length > 0) {
                    return new String(body, StandardCharsets.UTF_8);
                }
                // Si el cache está vacío, leer del stream (primera lectura)
            }
            // Fallback: leer directamente (solo funciona si Jackson no procesó primero)
            byte[] body = request.getInputStream().readAllBytes();
            return new String(body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[WebhookController] No se pudo leer el raw body: {}", e.getMessage());
            return null;
        }
    }

    private String safeLog(String value) {
        if (value == null) return "null";
        if (value.length() <= 8) return "[redacted]";
        return value.substring(0, 4) + "...[redacted]";
    }
}
