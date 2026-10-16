package com.security.dto;

import lombok.Data;

@Data
public class PricePreviewRequest {
    private String deliveryOption;
    private String state;
    private String city;
    private String postalCode;
}
