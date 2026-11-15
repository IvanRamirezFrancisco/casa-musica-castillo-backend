package com.security.dto.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO que representa la data interna de un webhook de Mercado Pago.
 * Solo expone el campo id del pago; el resto lo consultamos directamente en la API MP.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MercadoPagoWebhookData {

    /**
     * ID del pago o recurso reportado por Mercado Pago.
     */
    @JsonProperty("id")
    private String id;
}
