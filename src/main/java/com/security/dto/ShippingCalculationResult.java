package com.security.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShippingCalculationResult {
    private String deliveryMethod;
    private String deliveryLabel;
    private BigDecimal shippingCost;
    private boolean requiresAddress;
    private boolean requiresQuote;
    private boolean canProceedToPayment;
    private String message;
    private String contactWhatsapp;
    private String contactPhone;
    private String quoteMessage;
    private String businessHours;
}
