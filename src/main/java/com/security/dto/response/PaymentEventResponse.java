package com.security.dto.response;

import com.security.enums.PaymentProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO de respuesta para eventos de pago.
 * No incluye rawPayload por defecto (puede ser muy grande / sensible).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEventResponse {

    private Long id;
    private Long paymentId;
    private Long orderId;
    private PaymentProvider provider;
    private String eventType;
    private String providerEventId;
    private Boolean signatureValid;
    private Boolean processed;
    private LocalDateTime processedAt;
    private String processedBy;
    private String errorMessage;
    private LocalDateTime createdAt;
}
