package com.security.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PricePreviewResponse {
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal shipping;
    private BigDecimal total;
    private String deliveryMethod;
    private String deliveryLabel;
    private boolean requiresAddress;
    private boolean requiresQuote;
    private boolean canProceedToPayment;
    private String message;
    private String contactWhatsapp;
    private String contactPhone;
    private String quoteMessage;
    private String businessHours;
}
