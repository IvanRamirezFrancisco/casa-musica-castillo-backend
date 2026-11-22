package com.security.service;

import com.security.config.GoogleAuthProperties;
import com.security.exception.AuthFlowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class NimbusGoogleIdTokenVerifierService implements GoogleIdTokenVerifierService {
    private static final Logger log = LoggerFactory.getLogger(NimbusGoogleIdTokenVerifierService.class);
    private static final Set<String> VALID_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final GoogleAuthProperties properties;
    private final Clock clock;
    private final AtomicReference<JwtDecoder> jwtDecoderRef;

    @Autowired
    public NimbusGoogleIdTokenVerifierService(
            GoogleAuthProperties properties,
            @Autowired(required = false) @Qualifier("googleJwtDecoder") JwtDecoder jwtDecoder,
            @Autowired(required = false) Clock clock) {
        this.properties = properties;
        this.jwtDecoderRef = new AtomicReference<>(jwtDecoder);
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public VerifiedGoogleIdentity verify(String idToken, String expectedNonce) {
        if (!properties.isConfigured()) {
            throw new AuthFlowException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El acceso con Google no está configurado en este entorno.");
        }
        if (idToken == null || idToken.isBlank() || idToken.length() > 4096
                || idToken.chars().filter(ch -> ch == '.').count() != 2) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "La credencial de Google no tiene un formato válido.");
        }
        if (expectedNonce == null || expectedNonce.isBlank()) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "El desafío de verificación es obligatorio.");
        }

        Jwt jwt;
        try {
            jwt = decoder().decode(idToken.trim());
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Google ID token rejected: {}", ex.getClass().getSimpleName());
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "No se pudo validar la credencial de Google.");
        }

        String issuer = jwt.getClaimAsString("iss");
        if (issuer == null || !VALID_ISSUERS.contains(issuer)) {
            log.warn("Google ID token rejected due to invalid issuer");
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "El emisor de la credencial no está autorizado.");
        }

        Set<String> allowedClients = properties.getEffectiveAllowedClientIds();
        List<String> audiences = jwt.getAudience();
        boolean audienceMatches = audiences != null && audiences.stream().anyMatch(allowedClients::contains);
        if (!audienceMatches) {
            log.warn("Google ID token rejected due to audience mismatch");
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "La credencial no corresponde a esta aplicación.");
        }
        if (audiences.size() > 1) {
            String azp = jwt.getClaimAsString("azp");
            if (azp == null || !allowedClients.contains(azp)) {
                log.warn("Google ID token rejected due to unauthorized azp");
                throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Cliente autorizado incompatible.");
            }
        }

        Instant now = clock.instant();
        Duration skew = Duration.ofSeconds(Math.max(0, properties.getClockSkewSeconds()));
        Instant exp = jwt.getExpiresAt();
        if (exp == null || !exp.isAfter(now.minus(skew))) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "La credencial de Google ha expirado.");
        }
        Instant nbf = jwt.getNotBefore();
        if (nbf != null && nbf.isAfter(now.plus(skew))) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "La credencial de Google aún no es válida.");
        }
        Instant iat = jwt.getIssuedAt();
        if (iat != null && iat.isAfter(now.plus(skew))) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "La fecha de emisión de la credencial no es válida.");
        }

        String subject = sanitizeText(jwt.getSubject(), 255);
        if (subject == null) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "La credencial no incluye un identificador válido.");
        }

        String email = sanitizeText(jwt.getClaimAsString("email"), 100);
        if (email == null || !email.contains("@")) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Google no proporcionó un correo electrónico válido.");
        }
        email = email.toLowerCase(Locale.ROOT);

        Object rawEmailVerified = jwt.getClaim("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(rawEmailVerified)
                || (rawEmailVerified instanceof String str && "true".equalsIgnoreCase(str));
        if (!emailVerified) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED,
                    "Tu correo de Google debe estar verificado para continuar.");
        }

        String tokenNonce = jwt.getClaimAsString("nonce");
        if (tokenNonce == null || !MessageDigest.isEqual(
                tokenNonce.getBytes(StandardCharsets.UTF_8),
                expectedNonce.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED,
                    "La verificación de seguridad (nonce) no coincide o expiró.");
        }

        String givenName = sanitizeText(jwt.getClaimAsString("given_name"), 50);
        String familyName = sanitizeText(jwt.getClaimAsString("family_name"), 50);
        String fullName = sanitizeText(jwt.getClaimAsString("name"), 100);
        String pictureUrl = sanitizeHttpsUrl(jwt.getClaimAsString("picture"));

        return new VerifiedGoogleIdentity(subject, email, true, givenName, familyName, fullName, pictureUrl, exp);
    }

    private JwtDecoder decoder() {
        JwtDecoder current = this.jwtDecoderRef.get();
        if (current != null) return current;
        synchronized (this) {
            current = this.jwtDecoderRef.get();
            if (current == null) {
                // NimbusJwtDecoder caches Google's JWKS keys internally and enforces RS256 by default.
                current = NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
                this.jwtDecoderRef.set(current);
            }
            return current;
        }
    }

    private static String sanitizeText(String value, int maxLength) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.isEmpty()) return null;
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private static String sanitizeHttpsUrl(String value) {
        String clean = sanitizeText(value, 500);
        if (clean == null) return null;
        return clean.startsWith("https://") ? clean : null;
    }
}
