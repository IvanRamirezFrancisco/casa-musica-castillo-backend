package com.security.service;

import com.security.dto.response.JwtAuthResponse;
import com.security.dto.response.UserResponse;
import com.security.entity.RefreshToken;
import com.security.entity.User;
import com.security.exception.AuthFlowException;
import com.security.repository.ActiveSessionRepository;
import com.security.repository.RefreshTokenRepository;
import com.security.repository.UserRepository;
import com.security.security.JwtTokenProvider;
import com.security.security.UserPrincipal;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final String CLIENT_ANDROID = "ANDROID";
    private static final String CLIENT_WEB = "WEB";
    private static final String TOKEN_PREFIX = "rt1:";

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final ActiveSessionRepository activeSessions;
    private final JwtTokenProvider jwtTokenProvider;
    private final SessionManagementService sessionManagementService;
    private final UserService userService;
    private final AuditLogService auditLogService;
    private final ActivationClientAddress clientAddresses;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.security.jwt.refresh-inactivity-ms:604800000}")
    private long inactivityMs;

    @Value("${app.security.jwt.refresh-absolute-ms:2592000000}")
    private long absoluteMs;

    @Value("${app.security.jwt.refresh-cookie-name:cmc_refresh_token}")
    private String cookieName;

    @Value("${app.security.jwt.refresh-cookie-secure:false}")
    private boolean cookieSecure;

    @Value("${app.security.jwt.refresh-cookie-same-site:Lax}")
    private String cookieSameSite;

    @Value("${cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Value("${app.maintenance.enabled:true}")
    private boolean maintenanceEnabled = true;

    @Autowired
    public RefreshTokenService(
            RefreshTokenRepository refreshTokens,
            UserRepository users,
            ActiveSessionRepository activeSessions,
            JwtTokenProvider jwtTokenProvider,
            SessionManagementService sessionManagementService,
            UserService userService,
            AuditLogService auditLogService,
            ActivationClientAddress clientAddresses,
            PlatformTransactionManager txManager,
            @Autowired(required = false) Clock clock) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.activeSessions = activeSessions;
        this.jwtTokenProvider = jwtTokenProvider;
        this.sessionManagementService = sessionManagementService;
        this.userService = userService;
        this.auditLogService = auditLogService;
        this.clientAddresses = clientAddresses;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @PostConstruct
    void validateConfig() {
        if (inactivityMs <= 0 || absoluteMs <= 0 || inactivityMs > absoluteMs) {
            throw new IllegalStateException("La política de refresh tokens requiere TTL positivos con inactividad <= absoluto.");
        }
    }

    public record IssuedRefreshToken(String rawToken, String clientType) {
        @Override public String toString() { return "IssuedRefreshToken[redacted]"; }
    }

    public record RotatedSession(JwtAuthResponse jwtResponse, String rawRefreshToken, String clientType) {
        @Override public String toString() { return "RotatedSession[redacted]"; }
    }

    private record RotationAttempt(boolean success, boolean clearWebCookie, RotatedSession session) {}

    public String resolveClientType(HttpServletRequest request) {
        if (request == null) return CLIENT_WEB;
        String platform = request.getHeader("X-Client-Platform");
        return CLIENT_ANDROID.equalsIgnoreCase(platform != null ? platform.trim() : "")
                ? CLIENT_ANDROID
                : CLIENT_WEB;
    }

    @Transactional
    public IssuedRefreshToken issueForSession(User user, String sessionJti, HttpServletRequest request) {
        return createRefreshToken(user, sessionJti, request);
    }

    private IssuedRefreshToken createRefreshToken(User user, String sessionJti, HttpServletRequest request) {
        String clientType = resolveClientType(request);
        String rawToken = generateRawToken();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime absoluteExpiry = now.plus(Duration.ofMillis(absoluteMs));
        LocalDateTime inactivityExpiry = now.plus(Duration.ofMillis(inactivityMs));
        LocalDateTime expiry = inactivityExpiry.isBefore(absoluteExpiry) ? inactivityExpiry : absoluteExpiry;

        RefreshToken entity = new RefreshToken();
        entity.setToken(hashToken(rawToken));
        entity.setUser(user);
        entity.setFamilyId(UUID.randomUUID().toString());
        entity.setSessionJti(sessionJti);
        entity.setExpiryDate(expiry);
        entity.setAbsoluteExpiresAt(absoluteExpiry);
        entity.setRevoked(false);
        entity.setClientType(clientType);
        entity.setIpAddress(truncate(clientAddresses.resolve(request), 45));
        entity.setUserAgent(truncate(request != null ? request.getHeader("User-Agent") : null, 500));
        refreshTokens.save(entity);

        return new IssuedRefreshToken(rawToken, clientType);
    }

    @Transactional
    public HttpHeaders issueAndAttach(JwtAuthResponse jwtResponse, User user, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (jwtResponse == null || jwtResponse.getAccessToken() == null) return headers;
        String jti = jwtTokenProvider.getJtiFromJWT(jwtResponse.getAccessToken());
        IssuedRefreshToken issued = createRefreshToken(user, jti, request);
        if (CLIENT_ANDROID.equals(issued.clientType())) {
            jwtResponse.setRefreshToken(issued.rawToken());
        } else {
            jwtResponse.setRefreshToken(null);
            headers.add(HttpHeaders.SET_COOKIE, buildRefreshCookie(issued.rawToken()).toString());
        }
        return headers;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RotatedSession rotate(String bodyRefreshToken, HttpServletRequest request) {
        String clientType = resolveClientType(request);
        String rawToken;
        if (CLIENT_ANDROID.equals(clientType)) {
            rawToken = bodyRefreshToken;
        } else {
            enforceWebCsrf(request);
            rawToken = extractCookieToken(request);
        }
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{32,256}")) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Sesión de renovación inválida o ausente.");
        }

        String digest = hashToken(rawToken.trim());
        RotationAttempt attempt = requiresNewTx.execute(status -> rotateInTx(digest, clientType, request));
        if (attempt == null || !attempt.success()) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "La sesión expiró o fue revocada. Inicia sesión de nuevo.");
        }
        return attempt.session();
    }

    private RotationAttempt rotateInTx(String digest, String expectedClientType, HttpServletRequest request) {
        // Query scalar user_id first so Hibernate does NOT populate L1 PersistenceContext with an unlocked entity.
        Optional<Long> ownerId = refreshTokens.findUserIdByToken(digest);
        if (ownerId.isEmpty()) {
            return new RotationAttempt(false, true, null);
        }
        Long userId = ownerId.get();
        // Always lock user before refresh token row to preserve consistent lock order across auth services.
        User user = users.findByIdForAuthUpdate(userId).orElse(null);
        RefreshToken current = refreshTokens.findByTokenForUpdate(digest).orElse(null);
        if (user == null || current == null) {
            return new RotationAttempt(false, true, null);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (Boolean.TRUE.equals(current.getRevoked()) || current.getRotatedAt() != null) {
            // Token reuse detection: revoke the entire rotation family and its active sessions.
            revokeFamilyAndSessions(current.getFamilyId(), user.getId(), now, "REFRESH_TOKEN_REUSE");
            logSecurityEvent("SECURITY", "REFRESH_TOKEN_REUSE_DETECTED", user.getId(), "CRITICAL", false);
            return new RotationAttempt(false, true, null);
        }

        if (!expectedClientType.equals(current.getClientType())) {
            revokeFamilyAndSessions(current.getFamilyId(), user.getId(), now, "CLIENT_TYPE_MISMATCH");
            return new RotationAttempt(false, true, null);
        }

        if (!current.getExpiryDate().isAfter(now) || !current.getAbsoluteExpiresAt().isAfter(now)) {
            current.setRevoked(true);
            current.setRevokedAt(now);
            current.setRevokeReason("EXPIRED");
            refreshTokens.save(current);
            if (current.getSessionJti() != null) {
                sessionManagementService.invalidateSession(current.getSessionJti());
            }
            return new RotationAttempt(false, true, null);
        }

        if (!Boolean.TRUE.equals(user.getEnabled())
                || !Boolean.TRUE.equals(user.getAccountNonLocked())
                || !Boolean.TRUE.equals(user.getAccountNonExpired())
                || !Boolean.TRUE.equals(user.getCredentialsNonExpired())
                || user.isPendingSelfRegistration()) {
            revokeFamilyAndSessions(current.getFamilyId(), user.getId(), now, "ACCOUNT_UNAVAILABLE");
            return new RotationAttempt(false, true, null);
        }

        current.setRevoked(true);
        current.setRevokedAt(now);
        current.setRotatedAt(now);
        current.setLastUsedAt(now);
        current.setRevokeReason("ROTATED");
        refreshTokens.save(current);

        if (current.getSessionJti() != null && !current.getSessionJti().isBlank()) {
            sessionManagementService.invalidateSession(current.getSessionJti());
        }

        UserPrincipal principal = UserPrincipal.create(user);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        String newAccessToken = jwtTokenProvider.generateToken(auth, request);
        String newJti = jwtTokenProvider.getJtiFromJWT(newAccessToken);

        String newRawToken = generateRawToken();
        LocalDateTime inactivityExpiry = now.plus(Duration.ofMillis(inactivityMs));
        LocalDateTime nextExpiry = inactivityExpiry.isBefore(current.getAbsoluteExpiresAt())
                ? inactivityExpiry
                : current.getAbsoluteExpiresAt();

        RefreshToken replacement = new RefreshToken();
        replacement.setToken(hashToken(newRawToken));
        replacement.setUser(user);
        replacement.setFamilyId(current.getFamilyId());
        replacement.setSessionJti(newJti);
        replacement.setExpiryDate(nextExpiry);
        replacement.setAbsoluteExpiresAt(current.getAbsoluteExpiresAt());
        replacement.setRevoked(false);
        replacement.setClientType(expectedClientType);
        replacement.setIpAddress(truncate(clientAddresses.resolve(request), 45));
        replacement.setUserAgent(truncate(request != null ? request.getHeader("User-Agent") : null, 500));
        refreshTokens.save(replacement);

        UserResponse userResponse = userService.convertToUserResponse(user);
        JwtAuthResponse jwtResponse = new JwtAuthResponse(newAccessToken, jwtTokenProvider.getExpirationTime(), userResponse);
        if (CLIENT_ANDROID.equals(expectedClientType)) {
            jwtResponse.setRefreshToken(newRawToken);
        }

        log.debug("Refresh token rotated for account={} family={}", user.getId(), current.getFamilyId());
        return new RotationAttempt(true, false, new RotatedSession(jwtResponse, newRawToken, expectedClientType));
    }

    @Transactional
    public void revokeForLogout(String sessionJti, String rawRefreshToken, HttpServletRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (sessionJti != null && !sessionJti.isBlank()) {
            refreshTokens.revokeBySessionJti(sessionJti, now, "LOGOUT");
        }
        String tokenCandidate = rawRefreshToken != null && !rawRefreshToken.isBlank()
                ? rawRefreshToken
                : extractCookieToken(request);
        if (tokenCandidate != null && tokenCandidate.matches("[A-Za-z0-9_-]{32,256}")) {
            refreshTokens.findByToken(hashToken(tokenCandidate.trim())).ifPresent(found ->
                    revokeFamilyAndSessions(found.getFamilyId(), found.getUser().getId(), now, "LOGOUT"));
        }
    }

    @Transactional
    public void revokeAllForUser(Long userId, String reason) {
        if (userId == null) return;
        refreshTokens.revokeAllByUserId(userId, LocalDateTime.now(clock), reason);
    }

    private void revokeFamilyAndSessions(String familyId, Long userId, LocalDateTime now, String reason) {
        refreshTokens.revokeFamily(familyId, now, reason);
        List<RefreshToken> family = refreshTokens.findByFamilyId(familyId);
        for (RefreshToken member : family) {
            if (member.getSessionJti() != null && !member.getSessionJti().isBlank()) {
                sessionManagementService.invalidateSession(member.getSessionJti());
            }
        }
        log.warn("Refresh token family revoked account={} family={} reason={}", userId, familyId, reason);
    }

    public ResponseCookie buildRefreshCookie(String rawToken) {
        return ResponseCookie.from(cookieName, rawToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/api/auth")
                .maxAge(Duration.ofMillis(absoluteMs))
                .build();
    }

    public ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/api/auth")
                .maxAge(0)
                .build();
    }

    public String extractCookieToken(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public void enforceWebCsrf(HttpServletRequest request) {
        if (request == null) return;
        String xRequestedWith = request.getHeader("X-Requested-With");
        if (!"XMLHttpRequest".equalsIgnoreCase(xRequestedWith)) {
            throw new AuthFlowException(HttpStatus.FORBIDDEN, "Solicitud de renovación rechazada por política CSRF.");
        }
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank()) {
            String normalizedOrigin = normalizeOrigin(origin);
            boolean allowed = allowedOrigins != null && allowedOrigins.stream()
                    .map(RefreshTokenService::normalizeOrigin)
                    .anyMatch(normalizedOrigin::equalsIgnoreCase);
            if (!allowed) {
                throw new AuthFlowException(HttpStatus.FORBIDDEN, "Origen no autorizado para renovar sesión.");
            }
        }
    }

    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cleanupExpiredTokens() {
        if (!maintenanceEnabled) return;
        refreshTokens.deleteExpiredTokens(LocalDateTime.now(clock).minusDays(1));
    }

    public static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return TOKEN_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void logSecurityEvent(String action, String eventType, Long userId, String severity, boolean success) {
        try {
            auditLogService.log(action, eventType, "USER", userId, null, null, severity, success);
        } catch (RuntimeException ex) {
            log.warn("refresh_audit_unavailable account={}", userId);
        }
    }

    private static String normalizeOrigin(String origin) {
        String trimmed = origin.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
