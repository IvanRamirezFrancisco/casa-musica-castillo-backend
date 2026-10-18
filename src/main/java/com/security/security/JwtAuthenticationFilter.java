package com.security.security;

import com.security.service.SessionManagementService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    @Autowired
    private SessionManagementService sessionManagementService;

    // Rutas públicas que NO requieren validación de JWT
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/api/public/",
            "/api/auth/",
            "/api/2fa/verify",
            "/api/2fa/send-login-code",
            "/api/test/public",
            "/api/test/health",
            "/actuator/",
            "/error");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // No filtrar OPTIONS (CORS preflight)
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }

        // No filtrar rutas públicas
        for (String publicPath : PUBLIC_PATHS) {
            if (path.startsWith(publicPath) || path.equals(publicPath)) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Para rutas que llegan aquí, intentar autenticar con JWT
        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt)) {
                // ✅ VALIDAR TOKEN JWT y SESIÓN EN BD
                if (tokenProvider.validateToken(jwt)) {
                    // ✅ OBTENER JTI del token
                    String jti = tokenProvider.getJtiFromJWT(jwt);

                    // ✅ VALIDAR que la sesión esté activa en BD
                    if (jti != null && sessionManagementService.isSessionValid(jti)) {
                        // ✅ ACTUALIZAR actividad de la sesión
                        sessionManagementService.updateSessionActivity(jti);

                        // Proceder con autenticación normal
                        Long userId = tokenProvider.getUserIdFromJWT(jwt);
                        UserDetails userDetails = customUserDetailsService.loadUserById(userId);

                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                        SecurityContextHolder.getContext().setAuthentication(authentication);

                        logger.debug("✅ Sesión válida y activa para JTI: {}",
                                jti.length() > 8 ? jti.substring(0, 8) + "…" : jti);
                    } else {
                        // ✅ SESIÓN INVÁLIDA o REVOCADA: Limpiar contexto de seguridad
                        SecurityContextHolder.clearContext();
                        logger.warn("🔒 Sesión inválida/revocada para JTI: {}",
                                jti != null && jti.length() > 8 ? jti.substring(0, 8) + "…" : jti);
                    }
                } else {
                    // Token JWT inválido
                    SecurityContextHolder.clearContext();
                    logger.warn("🔒 Token JWT inválido");
                }
            }
            // Si no hay JWT, simplemente continuar sin autenticación

        } catch (Exception ex) {
            // Limpiar contexto en caso de error
            SecurityContextHolder.clearContext();
            logger.error("❌ Error procesando JWT: {}", ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}