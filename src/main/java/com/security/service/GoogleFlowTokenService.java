package com.security.service;

import com.security.config.GoogleAuthProperties;
import com.security.entity.User;
import com.security.exception.AuthFlowException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * Atomic single-use flow tokens (nonce challenges, pending federated registrations, and recent reauth step-ups)
 * plus TOTP anti-replay slot checks without injecting UserService (prevents circular bean dependencies).
 */
@Service
public class GoogleFlowTokenService {
    private static final String COL_PURPOSE = "purpose";
    private static final String COL_USER_ID = "user_id";
    private static final String COL_SESSION_JTI = "session_jti";
    private static final String COL_EXPIRES_AT = "expires_at";
    private static final String COL_CONSUMED_AT = "consumed_at";
    private static final String FLOW_TOKEN_PATTERN = "[A-Za-z0-9_-]{24,128}";

    private final JdbcTemplate jdbc;
    private final GoogleAuthProperties properties;
    private final TotpService totpService;
    private final TotpSecretStorage secretStorage;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public record PendingRegistrationFlow(
            long flowId,
            String subject,
            String email,
            String givenName,
            String familyName,
            String pictureUrl
    ) {}

    public record PendingLinkConfirmFlow(
            long flowId,
            long userId,
            String sessionJti,
            String subject,
            String email,
            String givenName,
            String familyName,
            String pictureUrl
    ) {}

    @Autowired
    public GoogleFlowTokenService(
            JdbcTemplate jdbc,
            GoogleAuthProperties properties,
            TotpService totpService,
            TotpSecretStorage secretStorage,
            @Autowired(required = false) Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.totpService = totpService;
        this.secretStorage = secretStorage;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional
    public String createNonceChallenge(String purpose) {
        return createNonceChallengeInternal(purpose, null, null);
    }

    @Transactional
    public String createBoundNonceChallenge(String purpose, Long userId, String sessionJti) {
        if (userId == null || sessionJti == null || sessionJti.isBlank()) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Sesión inválida.");
        }
        return createNonceChallengeInternal(purpose, userId, sessionJti.trim());
    }

    private String createNonceChallengeInternal(String purpose, Long userId, String sessionJti) {
        String raw = generateToken();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expires = now.plusSeconds(Math.max(60, properties.getChallengeTtlSeconds()));
        jdbc.update("""
                INSERT INTO auth.google_auth_flows(
                    token_hash, flow_type, user_id, session_jti, provider, purpose, created_at, expires_at)
                VALUES (?, 'NONCE', ?, ?, 'GOOGLE', ?, ?, ?)
                """, sha256(raw), userId, sessionJti, purpose != null ? purpose : "AUTH", now, expires);
        return raw;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consumeNonceChallenge(
            String rawNonce,
            String expectedPurpose,
            Long expectedUserId,
            String expectedSessionJti,
            boolean requireBoundContext) {
        if (rawNonce == null || !rawNonce.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "El desafío de seguridad no es válido.");
        }
        String hash = sha256(rawNonce);
        var rows = jdbc.queryForList("""
                SELECT id, user_id, session_jti, purpose, expires_at, consumed_at
                FROM auth.google_auth_flows
                WHERE token_hash = ? AND flow_type = 'NONCE'
                FOR UPDATE
                """, hash);
        if (rows.isEmpty()) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "El desafío de seguridad expiró o no existe.");
        }
        Map<String, Object> row = rows.get(0);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = ((Timestamp) row.get(COL_EXPIRES_AT)).toLocalDateTime();
        if (row.get(COL_CONSUMED_AT) != null || !expiresAt.isAfter(now)) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "El desafío de seguridad ya fue utilizado o expiró.");
        }
        String rowPurpose = (String) row.get(COL_PURPOSE);
        if (expectedPurpose == null || !expectedPurpose.equals(rowPurpose)) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "El desafío de seguridad no corresponde a esta operación.");
        }

        Long rowUserId = row.get(COL_USER_ID) instanceof Number number ? number.longValue() : null;
        String rowSessionJti = (String) row.get(COL_SESSION_JTI);
        assertNonceContext(expectedPurpose, expectedUserId, expectedSessionJti, requireBoundContext, rowUserId, rowSessionJti);

        jdbc.update("UPDATE auth.google_auth_flows SET consumed_at = ? WHERE id = ?", now, ((Number) row.get("id")).longValue());
        return rowUserId != null && rowSessionJti != null;
    }

    private static void assertNonceContext(
            String expectedPurpose,
            Long expectedUserId,
            String expectedSessionJti,
            boolean requireBoundContext,
            Long rowUserId,
            String rowSessionJti) {
        boolean hasBoundContext = rowUserId != null || rowSessionJti != null;
        if ("AUTH".equals(expectedPurpose)) {
            if (hasBoundContext) {
                throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "El desafío de seguridad no es válido para iniciar sesión.");
            }
            return;
        }
        if (hasBoundContext) {
            boolean matchingOwner = expectedUserId != null && expectedUserId.equals(rowUserId);
            boolean matchingSession = expectedSessionJti != null && !expectedSessionJti.isBlank()
                    && expectedSessionJti.equals(rowSessionJti);
            if (!matchingOwner || !matchingSession) {
                throw new AuthFlowException(HttpStatus.UNAUTHORIZED,
                        "El desafío de seguridad no corresponde a esta sesión o cuenta.");
            }
            return;
        }
        if (requireBoundContext) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED,
                    "El desafío de seguridad no corresponde a esta sesión autenticada.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markGoogleTokenConsumed(String idToken, Instant tokenExpiry) {
        String digest = sha256(idToken.trim());
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expires = tokenExpiry != null
                ? LocalDateTime.ofInstant(tokenExpiry, clock.getZone()).plusMinutes(5)
                : now.plusHours(1);
        try {
            jdbc.update("""
                    INSERT INTO auth.consumed_google_tokens(token_digest, expires_at, consumed_at)
                    VALUES (?, ?, ?)
                    """, digest, expires, now);
        } catch (DuplicateKeyException ex) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED,
                    "Esta credencial de Google ya fue utilizada. Solicita un nuevo inicio de sesión.");
        }
    }

    @Transactional
    public String createPendingRegistration(GoogleIdTokenVerifierService.VerifiedGoogleIdentity verified) {
        String raw = generateToken();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expires = now.plusSeconds(Math.max(120, properties.getRegistrationTtlSeconds()));
        jdbc.update("""
                INSERT INTO auth.google_auth_flows(
                    token_hash, flow_type, provider, provider_subject, email,
                    given_name, family_name, picture_url, purpose, created_at, expires_at)
                VALUES (?, 'REGISTRATION', 'GOOGLE', ?, ?, ?, ?, ?, 'REGISTER', ?, ?)
                """,
                sha256(raw),
                verified.subject(),
                verified.email(),
                truncate(verified.givenName(), 100),
                truncate(verified.familyName(), 100),
                truncate(verified.pictureUrl(), 500),
                now,
                expires);
        return raw;
    }

    public PendingRegistrationFlow lockPendingRegistration(String rawToken) {
        if (rawToken == null || !rawToken.matches(FLOW_TOKEN_PATTERN)) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "El registro con Google expiró o no es válido.");
        }
        String hash = sha256(rawToken);
        var rows = jdbc.queryForList("""
                SELECT id, provider_subject, email, given_name, family_name, picture_url, expires_at, consumed_at
                FROM auth.google_auth_flows
                WHERE token_hash = ? AND flow_type = 'REGISTRATION'
                FOR UPDATE
                """, hash);
        if (rows.isEmpty()) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "La sesión de registro con Google no es válida o expiró.");
        }
        Map<String, Object> row = rows.get(0);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = ((Timestamp) row.get(COL_EXPIRES_AT)).toLocalDateTime();
        if (row.get(COL_CONSUMED_AT) != null || !expiresAt.isAfter(now)) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "La sesión de registro con Google ya fue usada o expiró.");
        }
        return new PendingRegistrationFlow(
                ((Number) row.get("id")).longValue(),
                (String) row.get("provider_subject"),
                (String) row.get("email"),
                (String) row.get("given_name"),
                (String) row.get("family_name"),
                (String) row.get("picture_url"));
    }

    public void markFlowConsumed(long flowId) {
        jdbc.update("UPDATE auth.google_auth_flows SET consumed_at = ? WHERE id = ?", LocalDateTime.now(clock), flowId);
    }

    @Transactional
    public String createPendingLinkConfirmation(
            Long userId,
            String sessionJti,
            GoogleIdTokenVerifierService.VerifiedGoogleIdentity verified) {
        if (userId == null || sessionJti == null || sessionJti.isBlank()) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Sesión inválida.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("""
                UPDATE auth.google_auth_flows
                SET consumed_at = ?
                WHERE user_id = ? AND flow_type = 'LINK_CONFIRM' AND consumed_at IS NULL
                """, now, userId);

        String raw = generateToken();
        LocalDateTime expires = now.plusSeconds(Math.max(60, properties.getLinkConfirmTtlSeconds()));
        jdbc.update("""
                INSERT INTO auth.google_auth_flows(
                    token_hash, flow_type, user_id, session_jti, provider, provider_subject, email,
                    given_name, family_name, picture_url, purpose, created_at, expires_at)
                VALUES (?, 'LINK_CONFIRM', ?, ?, 'GOOGLE', ?, ?, ?, ?, ?, 'LINK_GOOGLE', ?, ?)
                """,
                sha256(raw),
                userId,
                sessionJti,
                verified.subject(),
                verified.email(),
                truncate(verified.givenName(), 100),
                truncate(verified.familyName(), 100),
                truncate(verified.pictureUrl(), 500),
                now,
                expires);
        return raw;
    }

    public PendingLinkConfirmFlow lockPendingLinkConfirmation(Long userId, String sessionJti, String rawToken) {
        if (rawToken == null || !rawToken.matches(FLOW_TOKEN_PATTERN)) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST,
                    "La confirmación de vinculación expiró o no es válida. Vuelve a iniciar el proceso.");
        }
        String hash = sha256(rawToken);
        var rows = jdbc.queryForList("""
                SELECT id, user_id, session_jti, provider_subject, email, given_name, family_name,
                       picture_url, purpose, expires_at, consumed_at
                FROM auth.google_auth_flows
                WHERE token_hash = ? AND flow_type = 'LINK_CONFIRM'
                FOR UPDATE
                """, hash);
        if (rows.isEmpty()) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST,
                    "La confirmación de vinculación expiró o no es válida. Vuelve a iniciar el proceso.");
        }
        Map<String, Object> row = rows.get(0);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = ((Timestamp) row.get(COL_EXPIRES_AT)).toLocalDateTime();
        Long rowUserId = row.get(COL_USER_ID) instanceof Number number ? number.longValue() : null;
        String rowSessionJti = (String) row.get(COL_SESSION_JTI);
        String rowPurpose = (String) row.get(COL_PURPOSE);

        if (row.get(COL_CONSUMED_AT) != null
                || !expiresAt.isAfter(now)
                || rowUserId == null || !rowUserId.equals(userId)
                || rowSessionJti == null || !rowSessionJti.equals(sessionJti)
                || !"LINK_GOOGLE".equals(rowPurpose)) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST,
                    "La confirmación de vinculación ya fue usada, expiró o no corresponde a esta sesión.");
        }
        return new PendingLinkConfirmFlow(
                ((Number) row.get("id")).longValue(),
                rowUserId,
                rowSessionJti,
                (String) row.get("provider_subject"),
                (String) row.get("email"),
                (String) row.get("given_name"),
                (String) row.get("family_name"),
                (String) row.get("picture_url"));
    }

    @Transactional
    public boolean cancelPendingLinkConfirmation(Long userId, String sessionJti, String rawToken) {
        if (userId == null || sessionJti == null || sessionJti.isBlank()
                || rawToken == null || !rawToken.matches(FLOW_TOKEN_PATTERN)) {
            return false;
        }
        int updated = jdbc.update("""
                UPDATE auth.google_auth_flows
                SET consumed_at = ?
                WHERE token_hash = ? AND flow_type = 'LINK_CONFIRM'
                  AND user_id = ? AND session_jti = ? AND consumed_at IS NULL
                """, LocalDateTime.now(clock), sha256(rawToken), userId, sessionJti);
        return updated > 0;
    }

    public void acquireTransactionLock(String namespace, String key) {
        if (namespace == null || key == null) return;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))", rs -> null, namespace, key);
    }

    @Transactional
    public String createReauthToken(Long userId, String sessionJti, String purpose) {
        String raw = generateToken();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expires = now.plusSeconds(Math.max(60, properties.getReauthTtlSeconds()));
        jdbc.update("""
                INSERT INTO auth.google_auth_flows(
                    token_hash, flow_type, user_id, session_jti, provider, purpose, created_at, expires_at)
                VALUES (?, 'REAUTH', ?, ?, 'GOOGLE', ?, ?, ?)
                """, sha256(raw), userId, sessionJti, purpose, now, expires);
        return raw;
    }

    @Transactional
    public void consumeReauthToken(Long userId, String sessionJti, String rawToken, String expectedPurpose) {
        if (rawToken == null || !rawToken.matches(FLOW_TOKEN_PATTERN)) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "Se requiere verificación reciente con Google.");
        }
        String hash = sha256(rawToken);
        var rows = jdbc.queryForList("""
                SELECT id, user_id, session_jti, purpose, expires_at, consumed_at
                FROM auth.google_auth_flows
                WHERE token_hash = ? AND flow_type = 'REAUTH'
                FOR UPDATE
                """, hash);
        if (rows.isEmpty()) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "La verificación reciente expiró o no es válida.");
        }
        Map<String, Object> row = rows.get(0);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = ((Timestamp) row.get(COL_EXPIRES_AT)).toLocalDateTime();
        Long rowUserId = ((Number) row.get(COL_USER_ID)).longValue();
        String rowSessionJti = (String) row.get(COL_SESSION_JTI);
        String rowPurpose = (String) row.get(COL_PURPOSE);

        if (row.get(COL_CONSUMED_AT) != null
                || !expiresAt.isAfter(now)
                || !userId.equals(rowUserId)
                || (sessionJti != null && rowSessionJti != null && !sessionJti.equals(rowSessionJti))
                || (expectedPurpose != null && !expectedPurpose.equals(rowPurpose) && !"SECURITY_STEP_UP".equals(rowPurpose))) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "La verificación reciente no es válida para esta operación.");
        }
        jdbc.update("UPDATE auth.google_auth_flows SET consumed_at = ? WHERE id = ?", now, ((Number) row.get("id")).longValue());
    }

    @Transactional
    public boolean acceptTotpSlot(User user, String code) {
        if (!Boolean.TRUE.equals(user.getGoogleAuthEnabled()) || user.getGoogleAuthSecret() == null) {
            return false;
        }
        var matched = totpService.findMatchingSlot(secretStorage.decode(user.getId(), user.getGoogleAuthSecret()), code);
        if (matched.isEmpty()) {
            return false;
        }
        long slot = matched.getAsLong();
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("INSERT INTO auth.login_factor_limits(user_id, window_start) VALUES (?, ?) ON CONFLICT DO NOTHING",
                user.getId(), now);
        return jdbc.update("""
                UPDATE auth.login_factor_limits SET last_verified_totp_slot = ?
                WHERE user_id = ? AND (last_verified_totp_slot IS NULL OR last_verified_totp_slot < ?)
                """, slot, user.getId(), slot) == 1;
    }

    @Transactional
    public void cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusHours(2);
        jdbc.update("DELETE FROM auth.google_auth_flows WHERE expires_at < ?", cutoff);
        jdbc.update("DELETE FROM auth.consumed_google_tokens WHERE expires_at < ?", cutoff);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
