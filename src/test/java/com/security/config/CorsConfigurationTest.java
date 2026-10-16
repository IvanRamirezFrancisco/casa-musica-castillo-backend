package com.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica que CORS_ORIGINS (cors.allowed-origins) es la única fuente de orígenes
 * y que el preflight OPTIONS se resuelve con origen explícito (nunca "*").
 */
class CorsConfigurationTest {

    private static final String VERCEL = "https://frontend.vercel.app";
    private static final String LOCAL = "http://localhost:4200";

    private CorsConfigurationSource sourceFor(List<String> origins) {
        SecurityConfig securityConfig = new SecurityConfig();
        ReflectionTestUtils.setField(securityConfig, "allowedOrigins", origins);
        return securityConfig.corsConfigurationSource();
    }

    private MockHttpServletResponse preflight(CorsConfigurationSource source, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/auth/login");
        request.addHeader(HttpHeaders.ORIGIN, origin);
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CorsConfiguration config = source.getCorsConfiguration(request);
        new DefaultCorsProcessor().processRequest(config, request, response);
        return response;
    }

    @Test
    void preflightFromConfiguredOriginIsAllowedWithExplicitOriginAndCredentials() throws Exception {
        CorsConfigurationSource source = sourceFor(List.of(VERCEL, LOCAL));

        MockHttpServletResponse response = preflight(source, VERCEL);

        assertEquals(200, response.getStatus());
        assertEquals(VERCEL, response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertNotEquals("*", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflightFromRailwayOriginIsRejectedWhenNotConfigured() throws Exception {
        CorsConfigurationSource source = sourceFor(List.of(VERCEL, LOCAL));

        MockHttpServletResponse response = preflight(source, "https://fronlogin-production.up.railway.app");

        assertEquals(403, response.getStatus());
        assertNull(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void commaSeparatedValuesAreTrimmedAndTrailingSlashIgnored() throws Exception {
        CorsConfigurationSource source = sourceFor(List.of(" " + VERCEL + "/ ", LOCAL));

        assertEquals(200, preflight(source, VERCEL).getStatus());
        assertEquals(200, preflight(source, LOCAL).getStatus());
    }

    @Test
    void wildcardOriginIsRejectedBecauseCredentialsAreEnabled() {
        assertThrows(IllegalStateException.class, () -> sourceFor(List.of("*")));
    }

    @Test
    void emptyOriginsFailFast() {
        assertThrows(IllegalStateException.class, () -> sourceFor(List.of(" ", "")));
    }
}
