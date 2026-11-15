package com.security.dto.response;

import com.security.enums.PaymentProvider;
import com.security.enums.PaymentTransactionStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PaymentReconciliationItemResponse {
    private Long orderId;
    private String orderNumber;
    private Long paymentId;
    private PaymentProvider provider;
    private PaymentTransactionStatus previousStatus;
    private PaymentTransactionStatus newStatus;
    private BigDecimal amount;
    private String action;
    private String reason;
}
