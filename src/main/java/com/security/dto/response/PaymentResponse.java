package com.security.dto.response;

import com.security.enums.PaymentProvider;
import com.security.enums.PaymentTransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO de respuesta para cliente.
 * Expone solo campos seguros; excluye datos sensibles (adminNotes,
 * rawPayload, providerPayloadHash, payerId, metadataJson, etc.).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private Long id;
    private Long orderId;
    private String orderNumber;
    private PaymentProvider provider;
    private String method;
    private PaymentTransactionStatus status;
    private BigDecimal amount;
    private String currency;

    /** URL de pago externo — null en Fase 7A (BANK_TRANSFER), poblado en 7B */
    private String checkoutUrl;

    private String externalReference;

    private LocalDateTime paidAt;
    private LocalDateTime expiresAt;
    private LocalDateTime cancelledAt;
    private String failureReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
