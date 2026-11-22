package com.security.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AuthenticatedGoogleChallengeRequest(
        @NotBlank(message = "El propósito del desafío es obligatorio")
        @Pattern(regexp = "LINK|REAUTH")
        String purpose
) {}
