package com.security.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import com.security.service.SessionManagementService;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    @Value("${app.security.jwt.expiration:86400000}")
    private int jwtExpirationInMs;

    private SecretKey key;

    @Autowired
    private SessionManagementService sessionManagementService;

    @PostConstruct
    public void init() {
        if (jwtSecret == null || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 64) {
            throw new IllegalStateException("JWT_SECRET requiere al menos 64 bytes generados criptográficamente");
        }
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String generateToken(Authentication authentication, HttpServletRequest request) {
        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();
        Date expiryDate = new Date(System.currentTimeMillis() + jwtExpirationInMs);

        Set<String> roles = userPrincipal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        // Crear sesión y obtener JTI
        String jti = sessionManagementService.createSession(
                userPrincipal.getEmail(),
                expiryDate.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(),
                request);
        if (jti == null || jti.isBlank()) throw new IllegalStateException("No se pudo crear la sesión JWT");

        return Jwts.builder()
                .setSubject(Long.toString(userPrincipal.getId()))
                .setId(jti) // ✅ Incluir JTI único para sesión
                .claim("email", userPrincipal.getEmail())
                .claim("roles", roles)
                .setIssuedAt(new Date())
                .setExpiration(expiryDate)
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();
    }

    // Nuevo método para extraer JTI del token
    public String getJtiFromJWT(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();

        return claims.getId();
    }

    public Long getUserIdFromJWT(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();

        return Long.parseLong(claims.getSubject());
    }

    public String getEmailFromJWT(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();

        return claims.get("email", String.class);
    }

    public boolean validateToken(String authToken) {
        String jti;
        try {
            Jws<Claims> parsed = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(authToken);
            Claims claims = parsed.getBody();
            String subject = claims.getSubject();
            if (!"HS512".equals(parsed.getHeader().getAlgorithm()) || claims.getExpiration() == null
                    || claims.getIssuedAt() == null || subject == null || !subject.matches("[0-9]+")
                    || Long.parseLong(subject) <= 0) return false;
            jti = claims.getId();
        } catch (JwtException | IllegalArgumentException ex) {
            logger.warn("JWT rechazado: {}", ex.getClass().getSimpleName());
            return false;
        }
        return jti != null && !jti.isBlank() && sessionManagementService.isSessionValid(jti);
    }

    public Date getExpirationDateFromJWT(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();

        return claims.getExpiration();
    }

    public long getExpirationTime() {
        return jwtExpirationInMs / 1000; // Return in seconds
    }

    // Método utilitario para extraer token de la request
    public String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
