package com.security.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UnlinkGoogleRequest(
        @Size(max = 255)
        String currentPassword,

        @Size(max = 128)
        String reauthToken,

        boolean confirmed,

        @Pattern(regexp = "EMAIL|GOOGLE_AUTHENTICATOR|BACKUP_CODE")
        String mfaMethod,

        @Size(max = 32)
        String mfaCode
) {
    @Override public String toString() { return "UnlinkGoogleRequest[redacted]"; }
}
