package com.security.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginFactorRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String preAuthChallenge,
        @NotBlank @Pattern(regexp = "EMAIL|GOOGLE_AUTHENTICATOR|BACKUP_CODE") String method,
        @Size(max = 32) String code) {
    @Override public String toString() { return "LoginFactorRequest[redacted]"; }
}
