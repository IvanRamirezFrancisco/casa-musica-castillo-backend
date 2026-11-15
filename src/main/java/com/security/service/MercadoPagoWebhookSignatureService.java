package com.security.service;

import com.security.config.MercadoPagoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Servicio de validación de firma HMAC para webhooks de Mercado Pago.
 *
 * Implementa el protocolo de verificación de firma descrito en la documentación oficial
 * de Mercado Pago para webhooks (x-signature header).
 *
 * Referencia oficial:
 * https://www.mercadopago.com.mx/developers/es/docs/your-integrations/notifications/webhooks
 *
 * Flujo de validación:
 * 1. Extraer ts y v1 del header x-signature.
 * 2. Construir el manifest: "id:{dataId};request-id:{xRequestId};ts:{ts};"
 *    (cada parte se omite si el valor no está disponible)
 * 3. Firmar con HMAC-SHA256 usando el webhookSecret.
 * 4. Comparar contra v1 usando comparación en tiempo constante (MessageDigest.isEqual).
 *
 * SEGURIDAD:
 * - No se imprime el webhookSecret en ningún log.
 * - No se imprime x-signature completo en logs de producción.
 * - La comparación de cadenas usa tiempo constante para evitar timing attacks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoWebhookSignatureService {

    private final MercadoPagoProperties properties;

    private static final String HMAC_ALGO = "HmacSHA256";

    /**
     * Valida la firma HMAC de un webhook de Mercado Pago.
     *
     * @param xSignature    Valor del header x-signature (ej: "ts=123456,v1=abc123...")
     * @param xRequestId    Valor del header x-request-id (puede ser null)
     * @param dataIdFromQuery ID del pago extraído del query param "data.id"
     * @return true si la firma es válida; false en caso contrario o si falta configuración
     */
    public boolean isValidSignature(String xSignature, String xRequestId, String dataIdFromQuery) {
        // Validar configuración
        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            if (properties.isEnabled()) {
                log.error("[WebhookSignature] MERCADO_PAGO_WEBHOOK_SECRET no está configurado pero MP está habilitado. Rechazando webhook.");
            }
            return false;
        }

        // Validar presencia del header
        if (xSignature == null || xSignature.isBlank()) {
            log.warn("[WebhookSignature] Header x-signature ausente. Rechazando solicitud.");
            return false;
        }

        // Extraer ts y v1 del header x-signature
        // Formato esperado: "ts=<timestamp>,v1=<hmac>"
        String ts = null;
        String v1 = null;

        for (String part : xSignature.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("ts=")) {
                ts = trimmed.substring(3).trim();
            } else if (trimmed.startsWith("v1=")) {
                v1 = trimmed.substring(3).trim();
            }
        }

        if (ts == null || ts.isBlank()) {
            log.warn("[WebhookSignature] No se encontró 'ts' en x-signature. Rechazando.");
            return false;
        }
        if (v1 == null || v1.isBlank()) {
            log.warn("[WebhookSignature] No se encontró 'v1' en x-signature. Rechazando.");
            return false;
        }

        // Construir manifest según documentación oficial:
        // id:{data.id};request-id:{x-request-id};ts:{ts};
        // Si algún valor no está presente, se omite ese par.
        StringBuilder manifest = new StringBuilder();

        if (dataIdFromQuery != null && !dataIdFromQuery.isBlank()) {
            manifest.append("id:").append(dataIdFromQuery).append(";");
        } else {
            log.warn("[WebhookSignature] data.id no disponible, omitido del manifest.");
        }

        if (xRequestId != null && !xRequestId.isBlank()) {
            manifest.append("request-id:").append(xRequestId).append(";");
        } else {
            log.warn("[WebhookSignature] x-request-id no disponible, omitido del manifest.");
        }

        manifest.append("ts:").append(ts).append(";");

        String manifestStr = manifest.toString();
        log.debug("[WebhookSignature] Manifest construido (longitud={})", manifestStr.length());

        // Calcular HMAC-SHA256
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), HMAC_ALGO);
            mac.init(keySpec);
            byte[] calculatedHmac = mac.doFinal(manifestStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Convertir el HMAC calculado a hex
            String calculatedHex = HexFormat.of().formatHex(calculatedHmac);

            // Comparación en tiempo constante (evitar timing attacks)
            boolean valid = MessageDigest.isEqual(
                    calculatedHex.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    v1.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );

            if (!valid) {
                log.warn("[WebhookSignature] Firma HMAC inválida. ts={} dataId=[redacted]", ts);
            } else {
                log.debug("[WebhookSignature] Firma HMAC válida. ts={}", ts);
            }

            return valid;

        } catch (Exception e) {
            log.error("[WebhookSignature] Error al calcular HMAC: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Calcula el hash SHA-256 de una cadena (para payload_hash en PaymentEvent).
     * No expone el contenido, solo su huella.
     *
     * @param payload Cadena de texto (ej: raw body del webhook)
     * @return Hash hex SHA-256 o null si falla
     */
    public String sha256Hex(String payload) {
        if (payload == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("[WebhookSignature] Error calculando SHA-256: {}", e.getMessage());
            return null;
        }
    }
}
