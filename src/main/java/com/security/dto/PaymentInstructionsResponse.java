package com.security.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentInstructionsResponse {

    private boolean configured;
    private String bankName;
    private String accountHolder;
    private String clabe;
    private String accountNumber;
    private String concept;
    private BigDecimal amount;
    private String orderNumber;
    private String referenceInstructions;
    private String additionalInstructions;
    private String paymentDeadline;
    private boolean isPaymentDeadlineExpired;

}
