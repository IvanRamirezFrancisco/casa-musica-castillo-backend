package com.security.security;

import com.security.service.SessionManagementService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;

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

        String jwt = getJwtFromRequest(request);
        if (StringUtils.hasText(jwt)) {
            SecurityContextHolder.clearContext();
            try {
                authenticate(request, jwt);
            } catch (JwtException | UsernameNotFoundException ex) {
                // A token can expire between checks, or the account can have been removed.
                SecurityContextHolder.clearContext();
            } catch (RuntimeException ex) {
                SecurityContextHolder.clearContext();
                String reference = UUID.randomUUID().toString();
                logger.error("Fallo de validación JWT ref={} tipo={}", reference, ex.getClass().getSimpleName());
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"No se pudo validar la sesión. Referencia: " + reference + "\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String jwt) {
        if (!tokenProvider.validateToken(jwt)) return;
        String jti = tokenProvider.getJtiFromJWT(jwt);
        if (jti == null || !sessionManagementService.isSessionValid(jti)) return;

        Long userId = tokenProvider.getUserIdFromJWT(jwt);
        UserDetails user = customUserDetailsService.loadUserById(userId);
        if (!user.isEnabled() || !user.isAccountNonLocked() || !user.isAccountNonExpired()
                || !user.isCredentialsNonExpired() || !sessionManagementService.belongsToUser(jti, userId)) return;

        sessionManagementService.updateSessionActivity(jti);
        request.setAttribute("authenticatedJti", jti);
        var authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}