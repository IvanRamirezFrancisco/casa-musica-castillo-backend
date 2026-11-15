package com.security.dto.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO del cuerpo del webhook de Mercado Pago.
 *
 * Mercado Pago envía esta estructura al URL de notificación configurado.
 * Solo se mapean los campos necesarios para identificar y deduplicar el evento.
 * El estado real del pago se consulta posteriormente vía PaymentClient.get(id).
 *
 * Referencia oficial:
 * https://www.mercadopago.com.mx/developers/es/docs/your-integrations/notifications/webhooks
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MercadoPagoWebhookRequest {

    /**
     * ID del evento de notificación generado por Mercado Pago.
     * Usado como provider_event_id para deduplicación.
     */
    @JsonProperty("id")
    private Long id;

    /** true si viene de producción, false si es Sandbox. */
    @JsonProperty("live_mode")
    private Boolean liveMode;

    /**
     * Tipo de recurso notificado. Ej: "payment".
     */
    @JsonProperty("type")
    private String type;

    /**
     * Acción ejecutada. Ej: "payment.created", "payment.updated".
     */
    @JsonProperty("action")
    private String action;

    @JsonProperty("api_version")
    private String apiVersion;

    @JsonProperty("date_created")
    private String dateCreated;

    /** ID del usuario vendedor en Mercado Pago. */
    @JsonProperty("user_id")
    private Long userId;

    /**
     * Datos del recurso afectado. Solo contiene el ID del pago/recurso.
     */
    @JsonProperty("data")
    private MercadoPagoWebhookData data;

    /**
     * Devuelve el ID del pago desde data.id.
     * Puede retornar null si el body no trae data o data.id.
     */
    public String getDataId() {
        return (data != null) ? data.getId() : null;
    }
}
