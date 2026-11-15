package com.security.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectPaymentProofRequest {
    @NotBlank(message = "El motivo de rechazo es obligatorio")
    private String reason;
}
