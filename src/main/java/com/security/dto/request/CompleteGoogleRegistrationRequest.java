package com.security.dto.request;

import com.security.validation.SafeInput;
import com.security.validation.ValidName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CompleteGoogleRegistrationRequest(
        @NotBlank(message = "El token de registro es obligatorio")
        @Size(min = 24, max = 128, message = "Token de registro inválido")
        String registrationToken,

        @NotBlank(message = "El nombre de usuario es obligatorio")
        @Size(min = 3, max = 30, message = "El nombre de usuario debe tener entre 3 y 30 caracteres")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Usa solo letras, números, punto, guion y guion bajo")
        @SafeInput(type = SafeInput.SanitizationType.TEXT, message = "Nombre de usuario inválido")
        String username,

        @NotBlank(message = "El nombre es obligatorio")
        @Size(min = 2, max = 50, message = "El nombre debe tener entre 2 y 50 caracteres")
        @ValidName(message = "El nombre contiene caracteres no permitidos")
        String firstName,

        @NotBlank(message = "Los apellidos son obligatorios")
        @Size(min = 2, max = 50, message = "Los apellidos deben tener entre 2 y 50 caracteres")
        @ValidName(message = "Los apellidos contienen caracteres no permitidos")
        String lastName,

        @Valid
        RegisterRequest.LegalAcceptance legalAcceptance
) {
    @Override public String toString() { return "CompleteGoogleRegistrationRequest[redacted]"; }
}
