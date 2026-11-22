package com.security.dto.request;

import jakarta.validation.constraints.Size;

public record RefreshSessionRequest(
        @Size(max = 256)
        String refreshToken
) {
    @Override public String toString() { return "RefreshSessionRequest[redacted]"; }
}
