package com.security.dto;

import com.security.enums.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CheckoutRequest {

    private String shippingAddress;

    private String deliveryOption;
    
    private String state;
    
    private String city;
    
    private String postalCode;
    
    private java.util.List<AuthorizedPersonDTO> authorizedPersons;

    @NotBlank(message = "La dirección de facturación es obligatoria")
    private String billingAddress;

    @NotNull(message = "El método de pago es obligatorio")
    private PaymentMethod paymentMethod;

    private String notes;
}
