package com.security.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelLinkGoogleRequest(
        @NotBlank(message = "El token de confirmación es obligatorio")
        @Size(min = 24, max = 128)
        String confirmationToken
) {
    @Override public String toString() { return "CancelLinkGoogleRequest[redacted]"; }
}
