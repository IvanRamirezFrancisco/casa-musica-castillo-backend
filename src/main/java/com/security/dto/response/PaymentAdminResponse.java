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
 * DTO de respuesta para administradores.
 * Incluye campos sensibles de auditoría que no se exponen al cliente.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentAdminResponse {

    private Long id;
    private Long orderId;
    private String orderNumber;
    private Long userId;
    private String userEmail;

    private PaymentProvider provider;
    private String method;
    private PaymentTransactionStatus status;
    private BigDecimal amount;
    private String currency;

    private String providerPaymentId;
    private String providerPreferenceId;
    private String providerOrderId;
    private String externalReference;
    private String idempotencyKey;
    private String payerEmail;
    private String rawProviderStatus;
    private String checkoutUrl;
    private String adminNotes;

    private LocalDateTime paidAt;
    private LocalDateTime expiresAt;
    private LocalDateTime cancelledAt;
    private String failureReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
}
