package com.security.security;

/** Explicit authentication routes shared by authorization and the JWT filter. */
public final class PublicAuthRoutes {
    private PublicAuthRoutes() {}
    public static final String[] EXACT = {
        "/api/auth/login", "/api/auth/register", "/api/auth/verify-email", "/api/auth/verify",
        "/api/auth/resend-verification", "/api/auth/forgot-password",
        "/api/auth/validate-reset-token", "/api/auth/reset-password",
        "/api/auth/password-reset/request", "/api/auth/password-reset/confirm",
        "/api/2fa/send-login-code", "/api/2fa/verify"
    };
    public static final String[] PATTERNS = {
        "/api/auth/check-username/*", "/api/auth/accept-invitation/*",
        "/api/auth/accept-invitation/validate/*", "/api/auth/password-reset/verify/*"
    };
    public static boolean matches(String path) {
        if (java.util.Arrays.asList(EXACT).contains(path)) return true;
        return java.util.Arrays.stream(PATTERNS).anyMatch(pattern ->
                new org.springframework.util.AntPathMatcher().match(pattern, path));
    }
}
