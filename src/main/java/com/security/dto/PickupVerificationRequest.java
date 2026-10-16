package com.security.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PickupVerificationRequest {
    @NotBlank(message = "El código es requerido")
    private String code;
    
    @jakarta.validation.constraints.NotNull(message = "Debe seleccionar una persona autorizada")
    private Long pickupAuthorizationId;
    
    private String notes;
}
