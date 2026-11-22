package com.security.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PrepareLinkGoogleRequest(
        @NotBlank(message = "La credencial de Google es obligatoria")
        @Size(max = 4096)
        String idToken,

        @NotBlank(message = "El desafío de seguridad es obligatorio")
        @Size(min = 16, max = 128)
        String nonce,

        @NotBlank(message = "Confirma tu contraseña actual")
        @Size(max = 255)
        String currentPassword,

        @Pattern(regexp = "EMAIL|GOOGLE_AUTHENTICATOR|BACKUP_CODE")
        String mfaMethod,

        @Size(max = 32)
        String mfaCode
) {
    @Override public String toString() { return "PrepareLinkGoogleRequest[redacted]"; }
}
