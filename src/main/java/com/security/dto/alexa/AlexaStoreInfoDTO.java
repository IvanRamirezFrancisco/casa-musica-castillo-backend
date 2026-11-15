package com.security.dto.alexa;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlexaStoreInfoDTO {
    private String name;
    private String address;
    private String phone;
    private String whatsapp;
    private String facebook;
    private String hours;
    private String paymentMethods;
    private String warrantyInfo;
}
