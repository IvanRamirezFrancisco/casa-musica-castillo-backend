package com.security.service;

import com.security.entity.User;
import com.security.entity.VerificationToken;
import com.security.enums.TokenType;
import com.security.repository.UserRepository;
import com.security.repository.VerificationTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.LoggerFactory;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Service
@Transactional
public class VerificationService {
    private static final TokenType TYPE = TokenType.EMAIL_VERIFICATION;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_CODE_ATTEMPTS = 5;

    private final VerificationTokenRepository tokens;
    private final UserRepository users;
    private final EmailService emailService;
    private final TransactionTemplate issuance;
    private final byte[] codeKey;
    private final Clock clock;
    private final ActivationPolicy policy;
    private final ActivationCrypto crypto;
    private final VerificationRequestLimit limits;
    private final JdbcTemplate jdbc;

    record PendingVerification(User recipient, String linkToken, String code) {}

    public VerificationService(VerificationTokenRepository tokens, UserRepository users, EmailService emailService,
                               PlatformTransactionManager manager, @Value("${app.security.jwt.secret}") String secret,
                               Clock clock, ActivationPolicy policy, ActivationCrypto crypto,
                               VerificationRequestLimit limits, JdbcTemplate jdbc) {
        this.tokens = tokens;
        this.users = users;
        this.emailService = emailService;
        this.issuance = new TransactionTemplate(manager);
        this.issuance.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.codeKey = mac(secret.getBytes(StandardCharsets.UTF_8), "cmc:email-activation:v1");
        this.clock = clock; this.policy = policy; this.crypto = crypto; this.limits = limits; this.jdbc = jdbc;
    }

    // The caller holds the account lock, or is inserting the new account in this transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    PendingVerification issue(User user) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (tokens.findFirstByUserIdAndTokenTypeOrderByCreatedAtDescIdDesc(user.getId(), TYPE)
                .filter(row -> row.getCreatedAt().isAfter(now.minusSeconds(policy.cooldown))).isPresent()
                || tokens.countByUserIdAndTokenTypeAndCreatedAtAfter(user.getId(), TYPE, now.minusSeconds(policy.window)) >= policy.emissions)
            return null;
        String address = normalize(user.getEmail());
        // Per-account checks precede shared budgets; no counters for nonexistent recipients.
        if (!limits.reserveDelivery(address)) return null;
        String token = randomPart(32);
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        VerificationToken row = new VerificationToken(linkDigest(token), user, now.plusSeconds(policy.linkSeconds), TYPE);
        row.bindRecipient(user.getEmail());
        row.setCodeDigest(codeDigest(user.getId(), row.getToken(), code));
        row.setCodeExpiresAt(now.plusSeconds(policy.otpSeconds));
        row.setCreatedAt(now);
        row.setDeliveryState("PENDING");
        row.setDeliveryPayload(crypto.seal(token + ":" + code, row.getToken()));
        tokens.save(row);
        User recipient = new User();
        recipient.setEmail(user.getEmail());
        recipient.setFirstName(user.getFirstName());
        return new PendingVerification(recipient, token, code);
    }

    public String generateVerificationToken(User user) {
        var prepared = issue(users.findByIdForAuthUpdate(user.getId()).orElseThrow());
        return prepared == null ? null : prepared.linkToken();
    }

    public boolean verifyToken(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{32,43}")) return false;
        var candidate = tokens.findByToken(linkDigest(raw)).or(() -> tokens.findByToken(raw)).orElse(null);
        if (candidate == null || candidate.getTokenType() != TYPE) return false;
        User user = users.findByIdForAuthUpdate(candidate.getUser().getId()).orElse(null);
        // Recheck after acquiring the shared account lock, including a concurrent resend.
        var current = user == null ? null : tokens.findValidToken(candidate.getToken(), TYPE, LocalDateTime.now(clock)).orElse(null);
        if (!eligible(user, current)) return false;
        activate(user, current);
        return true;
    }

    public boolean verifyCode(String email, String code) {
        User user = users.findIdByEmailForVerification(normalize(email)).flatMap(users::findByIdForAuthUpdate).orElse(null);
        if (user == null) return false;
        var candidates = tokens.findByUserId(user.getId()).stream()
                .filter(row -> row.getTokenType() == TYPE && !Boolean.TRUE.equals(row.getUsed())
                    && !java.util.Set.of("PENDING", "FAILED", "CANCELLED").contains(row.getDeliveryState())
                    && row.getCodeExpiresAt() != null && row.getCodeExpiresAt().isAfter(LocalDateTime.now(clock)))
                .filter(row -> eligible(user, row) && row.getCodeDigest() != null && row.getCodeAttempts() < MAX_CODE_ATTEMPTS)
                .toList();
        VerificationToken matched = null;
        for (var row : candidates) {
            byte[] expected = HexFormat.of().parseHex(row.getCodeDigest());
            byte[] supplied = HexFormat.of().parseHex(codeDigest(user.getId(), row.getToken(), code));
            if (MessageDigest.isEqual(expected, supplied)) matched = row;
        }
        if (matched == null) {
            candidates.forEach(VerificationToken::recordCodeAttempt);
            return false;
        }
        activate(user, matched);
        return true;
    }

    private static boolean eligible(User user, VerificationToken token) {
        return user != null && token != null && !Boolean.TRUE.equals(user.getEnabled())
                && Boolean.TRUE.equals(user.getAccountNonLocked()) && !user.isEmailVerified()
                && (user.isPendingSelfRegistration() || token.getCodeDigest() == null)
                && !java.util.Set.of("FAILED", "CANCELLED").contains(token.getDeliveryState())
                && token.matchesRecipient(user.getEmail());
    }

    private void activate(User user, VerificationToken row) {
        LocalDateTime now = LocalDateTime.now(clock);
        user.setEnabled(true);
        user.recordEmailVerification(now);
        user.setUpdatedAt(now);
        tokens.invalidateForUser(user.getId(), TYPE);
        jdbc.update("UPDATE auth.verification_tokens SET delivery_payload = NULL WHERE user_id = ? AND token_type = ?",
                user.getId(), TYPE.name());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void resendVerificationEmail(String email) {
        String address = normalize(email);
        issuance.executeWithoutResult(status -> {
            User user = users.findIdByEmailForVerification(address).flatMap(users::findByIdForAuthUpdate).orElse(null);
            if (user == null || Boolean.TRUE.equals(user.getEnabled()) || !Boolean.TRUE.equals(user.getAccountNonLocked())
                    || !user.isPendingSelfRegistration() || user.isEmailVerified() || !address.equalsIgnoreCase(user.getEmail())) return;
            issue(user);
        });
    }

    /** One claimed attempt. No transaction or account lock spans SMTP/HTTP. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void dispatchPending() {
        for (int batch = 0; batch < 10; batch++) {
            Long id = issuance.execute(status -> {
                var ids = jdbc.queryForList("SELECT id FROM auth.verification_tokens WHERE delivery_state = 'PENDING' " +
                    "AND used = false ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", Long.class);
                if (ids.isEmpty()) return null;
                jdbc.update("UPDATE auth.verification_tokens SET delivery_state = 'SENDING', delivery_started_at = ? WHERE id = ?",
                    LocalDateTime.now(clock), ids.getFirst());
                return ids.getFirst();
            });
            if (id == null) break;
            dispatchClaimed(id);
        }
        // A crash after claiming is uncertain: never automatically send that payload again.
        issuance.executeWithoutResult(status -> {
            jdbc.update("UPDATE auth.verification_tokens SET delivery_state = 'UNKNOWN', delivery_payload = NULL " +
                "WHERE delivery_state = 'SENDING' AND delivery_started_at < ?", LocalDateTime.now(clock).minusMinutes(5));
            jdbc.update("UPDATE auth.verification_tokens SET delivery_state = 'CANCELLED', delivery_payload = NULL, used = true " +
                "WHERE delivery_state = 'PENDING' AND (used = true OR code_expires_at <= ?)", LocalDateTime.now(clock));
        });
    }

    private void dispatchClaimed(long id) {
        String[] material = issuance.execute(status -> {
            var row = tokens.findById(id).orElseThrow();
            User user = users.findByIdForAuthUpdate(row.getUser().getId()).orElseThrow();
            if (Boolean.TRUE.equals(row.getUsed()) || Boolean.TRUE.equals(user.getEnabled()) || !user.isPendingSelfRegistration()
                    || !Boolean.TRUE.equals(user.getAccountNonLocked()) || !row.matchesRecipient(user.getEmail()) || row.getDeliveryPayload() == null
                    || !row.getCodeExpiresAt().isAfter(LocalDateTime.now(clock))) return null;
            return new String[]{row.getRecipientEmail(), row.getToken(), row.getDeliveryPayload()};
        });
        EmailService.ActivationDelivery outcome = EmailService.ActivationDelivery.FAILED;
        if (material != null) {
            try {
                String[] payload = crypto.open(material[2], material[1]).split(":", 2);
                User recipient = new User(); recipient.setEmail(material[0]); recipient.setFirstName("");
                outcome = emailService.deliverActivation(recipient, payload[0], payload[1]);
            } catch (RuntimeException ex) {
                // Do not include exception messages/payloads in logs.
                LoggerFactory.getLogger(getClass()).warn("activation_delivery_preparation_failed operation={}", id);
            }
        }
        EmailService.ActivationDelivery result = outcome;
        issuance.executeWithoutResult(status -> {
            var candidate = tokens.findById(id).orElseThrow();
            User user = users.findByIdForAuthUpdate(candidate.getUser().getId()).orElseThrow();
            // Account state is rechecked after its shared lock; terminal rows never reopen an account.
            boolean canComplete = !Boolean.TRUE.equals(user.getEnabled()) && user.isPendingSelfRegistration()
                && Boolean.TRUE.equals(user.getAccountNonLocked()) && candidate.matchesRecipient(user.getEmail());
            if (canComplete && result == EmailService.ActivationDelivery.ACCEPTED) {
                jdbc.update("UPDATE auth.verification_tokens SET used = true, delivery_payload = NULL " +
                    "WHERE user_id = ? AND token_type = ? AND id < ?", user.getId(), TYPE.name(), id);
            }
            jdbc.update("UPDATE auth.verification_tokens SET delivery_state = ?, delivery_payload = NULL, " +
                "used = CASE WHEN ? OR ? OR EXISTS (SELECT 1 FROM auth.verification_tokens newer " +
                "WHERE newer.user_id = verification_tokens.user_id AND newer.token_type = verification_tokens.token_type " +
                "AND newer.id > verification_tokens.id AND newer.delivery_state = 'ACCEPTED') THEN true ELSE used END WHERE id = ?",
                result.name(), !canComplete, result == EmailService.ActivationDelivery.FAILED, id);
            LoggerFactory.getLogger(getClass()).info("activation_delivery operation={} outcome={}", id, result);
        });
    }

    public void cleanupExpiredTokens() {
        tokens.deleteExpiredTokens(LocalDateTime.now(clock));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForUser(User user) {
        tokens.invalidateForUser(user.getId(), TYPE);
        jdbc.update("UPDATE auth.verification_tokens SET delivery_payload = NULL, delivery_state = " +
            "CASE WHEN delivery_state IN ('PENDING','SENDING') THEN 'CANCELLED' ELSE delivery_state END " +
            "WHERE user_id = ? AND token_type = ?", user.getId(), TYPE.name());
    }

    public boolean hasValidVerificationToken(User user) {
        return tokens.countActiveTokensByUserId(user.getId(), LocalDateTime.now(clock)) > 0;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String randomPart(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String linkDigest(String token) {
        try {
            return "v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo proteger el enlace de verificación.", ex);
        }
    }

    private String codeDigest(long userId, String tokenDigest, String code) {
        return HexFormat.of().formatHex(mac(codeKey, userId + ":" + tokenDigest + ":" + code));
    }

    private static byte[] mac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo proteger el código de verificación.", ex);
        }
    }
}
