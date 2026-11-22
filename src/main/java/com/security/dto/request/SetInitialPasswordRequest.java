package com.security.dto.request;

import com.security.validation.BcryptPassword;
import com.security.validation.SecurePassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetInitialPasswordRequest(
        @NotBlank(message = "La verificación de identidad reciente es obligatoria")
        @Size(min = 24, max = 128)
        String reauthToken,

        @NotBlank(message = "Nueva contraseña es requerida")
        @Size(min = 8, max = 255, message = "La contraseña debe tener entre 8 y 255 caracteres")
        @SecurePassword
        @BcryptPassword
        String newPassword
) {
    @Override public String toString() { return "SetInitialPasswordRequest[redacted]"; }
}
