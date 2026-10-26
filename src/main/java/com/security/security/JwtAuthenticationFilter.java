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

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) || PublicAuthRoutes.matches(path);
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


                        // Proceder con autenticación normal
                        Long userId = tokenProvider.getUserIdFromJWT(jwt);
                        UserDetails userDetails = customUserDetailsService.loadUserById(userId);

                        if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()
                                || !userDetails.isAccountNonExpired() || !userDetails.isCredentialsNonExpired()
                                || !sessionManagementService.belongsToUser(jti, userId)) {
                            SecurityContextHolder.clearContext();
                            filterChain.doFilter(request, response);
                            return;
                        }
                        sessionManagementService.updateSessionActivity(jti);
                        request.setAttribute("authenticatedJti", jti);
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
            logger.warn("Error procesando autenticación JWT: {}", ex.getClass().getSimpleName());
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