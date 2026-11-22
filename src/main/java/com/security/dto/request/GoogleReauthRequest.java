package com.security.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record GoogleReauthRequest(
        @NotBlank(message = "La credencial de Google es obligatoria")
        @Size(max = 4096)
        String idToken,

        @NotBlank(message = "El desafío de seguridad es obligatorio")
        @Size(min = 16, max = 128)
        String nonce,

        @NotBlank(message = "El propósito es obligatorio")
        @Pattern(regexp = "SET_INITIAL_PASSWORD|LINK_GOOGLE|UNLINK_GOOGLE|SECURITY_STEP_UP|DELETE_ACCOUNT")
        String purpose,

        @Pattern(regexp = "EMAIL|GOOGLE_AUTHENTICATOR|BACKUP_CODE")
        String mfaMethod,

        @Size(max = 32)
        String mfaCode
) {
    @Override public String toString() { return "GoogleReauthRequest[redacted]"; }
}
