package com.security.service;

import com.security.dto.request.LoginFactorRequest;
import com.security.dto.response.JwtAuthResponse;
import com.security.entity.User;
import com.security.exception.AuthFlowException;
import com.security.repository.UserRepository;
import com.security.security.JwtTokenProvider;
import com.security.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

@Service
@Transactional(noRollbackFor = AuthFlowException.class)
public class LoginChallengeService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final TwoFactorService factors;
    private final EmailService email;
    private final PasswordEncoder encoder;
    private final JwtTokenProvider jwt;
    private final UserService userService;
    private final TransactionTemplate emailPreparation;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LoginChallengeService.class);
    private final SecureRandom random = new SecureRandom();

    public LoginChallengeService(JdbcTemplate jdbc, UserRepository users, TwoFactorService factors,
            EmailService email, PasswordEncoder encoder, JwtTokenProvider jwt, UserService userService, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.users = users; this.factors = factors; this.email = email;
        this.encoder = encoder; this.jwt = jwt; this.userService = userService;
        this.emailPreparation = new TransactionTemplate(transactions);
        this.emailPreparation.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record PendingLogin(boolean twoFactorRequired, String preAuthChallenge,
                               Map<String, Boolean> availableMethods) {
        @Override public String toString() { return "PendingLogin[redacted]"; }
    }

    public PendingLogin create(Long userId) {
        User user = lockUser(userId);
        checkUser(user);
        requireAttemptCapacity(userId);
        budget(userId, "challenges", 5);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("DELETE FROM auth.login_challenges WHERE user_id=? AND expires_at < ?", userId, now);
        // A new password authentication supersedes the previous pending login.
        jdbc.update("UPDATE auth.login_challenges SET consumed_at=?,email_code_hash=NULL WHERE user_id=? AND consumed_at IS NULL", now, userId);
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO auth.login_challenges(challenge_hash,user_id,purpose,created_at,expires_at) VALUES (?,?,'LOGIN_2FA',?,?)",
                hash(token), userId, now, now.plusMinutes(5));
        return new PendingLogin(true, token, Map.of(
                "EMAIL", Boolean.TRUE.equals(user.getEmailEnabled()),
                "GOOGLE_AUTHENTICATOR", Boolean.TRUE.equals(user.getGoogleAuthEnabled()),
                "BACKUP_CODE", Boolean.TRUE.equals(user.getBackupCodesEnabled())));
    }

    /** Require no ambient transaction: suspended callers could still retain database locks. */
    @Transactional(propagation = Propagation.NEVER)
    public void send(LoginFactorRequest request) {
        PendingEmail delivery = emailPreparation.execute(status -> prepareEmail(request));
        if (delivery == null) throw new IllegalStateException("No se pudo preparar el envío 2FA");
        try {
            email.send2FACodeEmail(delivery.recipient(), delivery.code());
        } catch (Exception ex) {
            // Delivery may have succeeded despite a timeout. Never refund quota or invalidate the code automatically.
            log.warn("No se pudo confirmar el envío 2FA: {}", ex.getClass().getSimpleName());
            throw new AuthFlowException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private record PendingEmail(User recipient, String code) {
        @Override public String toString() { return "PendingEmail[redacted]"; }
    }

    private PendingEmail prepareEmail(LoginFactorRequest request) {
        Challenge challenge = lockChallenge(request.preAuthChallenge());
        User user = lockUser(challenge.userId());
        checkUser(user);
        requireMethod(user, request.method());
        if (!"EMAIL".equals(request.method())) throw invalid();
        requireAttemptCapacity(user.getId());
        budget(user.getId(), "sends", 3);
        String code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000));
        jdbc.update("UPDATE auth.login_challenges SET email_code_hash=? WHERE id=?", encoder.encode(code), challenge.id());
        // Carry only the recipient fields the mail transport needs, not the managed security entity.
        User recipient = new User();
        recipient.setId(user.getId()); recipient.setEmail(user.getEmail());
        recipient.setFirstName(user.getFirstName()); recipient.setLastName(user.getLastName());
        return new PendingEmail(recipient, code);
    }

    public JwtAuthResponse verify(LoginFactorRequest request, HttpServletRequest servletRequest) {
        Challenge challenge = lockChallenge(request.preAuthChallenge());
        User user = lockUser(challenge.userId());
        checkUser(user);
        budget(user.getId(), "attempts", 5);
        int attempts = challenge.attempts() + 1;
        jdbc.update("UPDATE auth.login_challenges SET attempt_count=? WHERE id=?", attempts, challenge.id());
        requireMethod(user, request.method());
        String code = request.code();
        boolean valid = code != null && switch (request.method()) {
            case "EMAIL" -> code.matches("[0-9]{6}") && challenge.emailHash() != null && encoder.matches(code, challenge.emailHash());
            case "GOOGLE_AUTHENTICATOR" -> code.matches("[0-9]{6}") && acceptTotpSlot(user.getId(), code);
            case "BACKUP_CODE" -> factors.verifyBackupCode(user.getId(), code);
            default -> false;
        };
        if (!valid) {
            if (attempts >= 5) jdbc.update("UPDATE auth.login_challenges SET consumed_at=?,email_code_hash=NULL WHERE id=?", LocalDateTime.now(), challenge.id());
            throw invalid();
        }
        jdbc.update("UPDATE auth.login_challenges SET consumed_at=?,email_code_hash=NULL WHERE id=?", LocalDateTime.now(), challenge.id());
        UserPrincipal principal = UserPrincipal.create(user);
        String token = jwt.generateToken(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()), servletRequest);
        return new JwtAuthResponse(token, jwt.getExpirationTime(), userService.convertToUserResponse(user));
    }

    private boolean acceptTotpSlot(Long userId, String code) {
        var matched = factors.findGoogleAuthenticatorLoginSlot(userId, code);
        if (matched.isEmpty()) return false;
        long slot = matched.getAsLong();
        return jdbc.update("""
                UPDATE auth.login_factor_limits SET last_verified_totp_slot=?
                WHERE user_id=? AND (last_verified_totp_slot IS NULL OR last_verified_totp_slot < ?)
                """, slot, userId, slot) == 1;
    }

    private record Challenge(long id, long userId, int attempts, String emailHash) {}

    private Challenge lockChallenge(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        // Always lock user before challenge: consistent order with create/session/backup operations.
        var ids = jdbc.queryForList("SELECT user_id FROM auth.login_challenges WHERE challenge_hash=?", Long.class, hash(token));
        if (ids.isEmpty()) throw invalid();
        lockUser(ids.get(0));
        var rows = jdbc.queryForList("SELECT * FROM auth.login_challenges WHERE challenge_hash=? FOR UPDATE", hash(token));
        if (rows.isEmpty()) throw invalid();
        var row = rows.get(0);
        if (!"LOGIN_2FA".equals(row.get("purpose")) || row.get("consumed_at") != null
                || !((Timestamp) row.get("expires_at")).toLocalDateTime().isAfter(LocalDateTime.now())
                || ((Number) row.get("attempt_count")).intValue() >= 5) throw invalid();
        return new Challenge(((Number) row.get("id")).longValue(), ids.get(0),
                ((Number) row.get("attempt_count")).intValue(), (String) row.get("email_code_hash"));
    }

    private User lockUser(Long id) {
        return users.findByIdForAuthUpdate(id).orElseThrow(LoginChallengeService::invalid);
    }

    private void checkUser(User user) {
        UserPrincipal principal = UserPrincipal.create(user);
        if (!principal.isEnabled() || !principal.isAccountNonLocked() || !principal.isAccountNonExpired()
                || !principal.isCredentialsNonExpired()
                || !(Boolean.TRUE.equals(user.getEmailEnabled()) || Boolean.TRUE.equals(user.getGoogleAuthEnabled()))) throw invalid();
    }

    private void requireMethod(User user, String method) {
        boolean allowed = switch (method == null ? "" : method) {
            case "EMAIL" -> Boolean.TRUE.equals(user.getEmailEnabled());
            case "GOOGLE_AUTHENTICATOR" -> Boolean.TRUE.equals(user.getGoogleAuthEnabled());
            case "BACKUP_CODE" -> Boolean.TRUE.equals(user.getBackupCodesEnabled());
            default -> false;
        };
        if (!allowed) throw invalid();
    }

    private void prepareLimitWindow(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO auth.login_factor_limits(user_id,window_start) VALUES (?,?) ON CONFLICT DO NOTHING", userId, now);
        // Deliberately preserve last_verified_totp_slot across quota windows.
        jdbc.update("UPDATE auth.login_factor_limits SET window_start=?,challenges=0,sends=0,attempts=0 WHERE user_id=? AND window_start <= ?", now, userId, now.minusMinutes(15));
    }

    private void requireAttemptCapacity(Long userId) {
        prepareLimitWindow(userId);
        Integer attempts = jdbc.queryForObject("SELECT attempts FROM auth.login_factor_limits WHERE user_id=?", Integer.class, userId);
        if (attempts == null || attempts >= 5) throw new AuthFlowException(HttpStatus.TOO_MANY_REQUESTS);
    }

    private void budget(Long userId, String column, int maximum) {
        // Column is selected exclusively by internal callers, under the user's row lock.
        prepareLimitWindow(userId);
        int changed = jdbc.update("UPDATE auth.login_factor_limits SET " + column + "=" + column + "+1 WHERE user_id=? AND " + column + " < ?", userId, maximum);
        if (changed != 1) throw new AuthFlowException(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Scheduled(fixedDelay = 3600000)
    public void cleanup() {
        jdbc.update("DELETE FROM auth.login_challenges WHERE expires_at < ?", LocalDateTime.now().minusDays(1));
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static AuthFlowException invalid() { return new AuthFlowException(HttpStatus.UNAUTHORIZED); }
}
