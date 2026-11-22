package com.security.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GoogleAuthenticateRequest(
        @NotBlank(message = "La credencial de Google es obligatoria")
        @Size(max = 4096, message = "Credencial inválida")
        String idToken,
        @NotBlank(message = "El desafío de seguridad es obligatorio")
        @Size(min = 16, max = 128, message = "Desafío inválido")
        String nonce
) {
    @Override public String toString() { return "GoogleAuthenticateRequest[redacted]"; }
}
