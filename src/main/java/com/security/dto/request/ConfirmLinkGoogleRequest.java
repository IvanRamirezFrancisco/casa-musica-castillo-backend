package com.security.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmLinkGoogleRequest(
        @NotBlank(message = "El token de confirmación es obligatorio")
        @Size(min = 24, max = 128)
        String confirmationToken,

        boolean confirmed
) {
    @Override public String toString() { return "ConfirmLinkGoogleRequest[redacted]"; }
}
