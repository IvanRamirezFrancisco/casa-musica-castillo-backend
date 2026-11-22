package com.security.dto.request;

import jakarta.validation.constraints.Pattern;

public record GoogleChallengeRequest(
        @Pattern(regexp = "AUTH|LINK|REAUTH") String purpose
) {}
