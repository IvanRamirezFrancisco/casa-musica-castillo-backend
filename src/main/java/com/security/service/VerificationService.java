package com.security.service;

import com.security.entity.User;
import com.security.entity.VerificationToken;
import com.security.enums.TokenType;
import com.security.exception.AuthFlowException;
import com.security.repository.UserRepository;
import com.security.repository.VerificationTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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

    record PendingVerification(User recipient, String linkToken, String code) {}

    public VerificationService(VerificationTokenRepository tokens, UserRepository users, EmailService emailService,
                               PlatformTransactionManager manager, @Value("${app.security.jwt.secret}") String secret) {
        this.tokens = tokens;
        this.users = users;
        this.emailService = emailService;
        this.issuance = new TransactionTemplate(manager);
        this.issuance.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.codeKey = mac(secret.getBytes(StandardCharsets.UTF_8), "cmc:email-activation:v1");
    }

    // The caller holds the account lock, or is inserting the new account in this transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    PendingVerification issue(User user) {
        tokens.invalidateForUser(user.getId(), TYPE);
        String token = randomPart(32);
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        VerificationToken row = new VerificationToken(linkDigest(token), user, LocalDateTime.now().plusHours(24), TYPE);
        row.bindRecipient(user.getEmail());
        row.setCodeDigest(codeDigest(user.getId(), row.getToken(), code));
        tokens.save(row);
        User recipient = new User();
        recipient.setEmail(user.getEmail());
        recipient.setFirstName(user.getFirstName());
        return new PendingVerification(recipient, token, code);
    }

    public String generateVerificationToken(User user) {
        return issue(users.findByIdForAuthUpdate(user.getId()).orElseThrow()).linkToken();
    }

    public boolean verifyToken(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{32,43}")) return false;
        var candidate = tokens.findByToken(linkDigest(raw)).or(() -> tokens.findByToken(raw)).orElse(null);
        if (candidate == null || candidate.getTokenType() != TYPE) return false;
        User user = users.findByIdForAuthUpdate(candidate.getUser().getId()).orElse(null);
        // Recheck after acquiring the shared account lock, including a concurrent resend.
        var current = user == null ? null : tokens.findValidToken(candidate.getToken(), TYPE, LocalDateTime.now()).orElse(null);
        if (!eligible(user, current)) return false;
        activate(user, current);
        return true;
    }

    public boolean verifyCode(String email, String code) {
        User user = users.findIdByEmailForVerification(normalize(email)).flatMap(users::findByIdForAuthUpdate).orElse(null);
        if (user == null) return false;
        var current = tokens.findFirstByUserIdAndTokenTypeAndUsedFalseOrderByCreatedAtDescIdDesc(user.getId(), TYPE)
                .filter(row -> row.getExpiryDate().isAfter(LocalDateTime.now())).orElse(null);
        if (!eligible(user, current) || current.getCodeDigest() == null || current.getCodeAttempts() >= MAX_CODE_ATTEMPTS) {
            return false;
        }
        byte[] expected = HexFormat.of().parseHex(current.getCodeDigest());
        byte[] supplied = HexFormat.of().parseHex(codeDigest(user.getId(), current.getToken(), code));
        if (!MessageDigest.isEqual(expected, supplied)) {
            current.recordCodeAttempt();
            return false;
        }
        activate(user, current);
        return true;
    }

    private static boolean eligible(User user, VerificationToken token) {
        return user != null && token != null && !Boolean.TRUE.equals(user.getEnabled())
                && Boolean.TRUE.equals(user.getAccountNonLocked()) && !user.isEmailVerified()
                && token.matchesRecipient(user.getEmail());
    }

    private void activate(User user, VerificationToken row) {
        LocalDateTime now = LocalDateTime.now();
        user.setEnabled(true);
        user.recordEmailVerification(now);
        user.setUpdatedAt(now);
        row.setUsed(true);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void resendVerificationEmail(String email) {
        String address = normalize(email);
        PendingVerification prepared = issuance.execute(status -> {
            User user = users.findIdByEmailForVerification(address).flatMap(users::findByIdForAuthUpdate).orElse(null);
            if (user == null || Boolean.TRUE.equals(user.getEnabled()) || !Boolean.TRUE.equals(user.getAccountNonLocked())
                    || user.isEmailVerified() || !address.equalsIgnoreCase(user.getEmail())) return null;
            LocalDateTime now = LocalDateTime.now();
            boolean justIssued = tokens.findFirstByUserIdAndTokenTypeAndUsedFalseOrderByCreatedAtDescIdDesc(user.getId(), TYPE)
                    .filter(row -> row.getExpiryDate().isAfter(now) && row.getCreatedAt().isAfter(now.minusSeconds(30)))
                    .isPresent();
            if (justIssued) return null;
            if (tokens.countByUserIdAndTokenTypeAndCreatedAtAfter(user.getId(), TYPE,
                    LocalDateTime.now().minusMinutes(5)) >= 3) return null;
            user.setUpdatedAt(now);
            return issue(user);
        });
        if (prepared != null) deliver(prepared);
    }

    void deliver(PendingVerification prepared) {
        try {
            emailService.sendVerificationEmail(prepared.recipient(), prepared.linkToken(), prepared.code());
        } catch (RuntimeException ex) {
            issuance.executeWithoutResult(status -> tokens.findByToken(linkDigest(prepared.linkToken()))
                    .ifPresent(row -> {
                        users.findByIdForAuthUpdate(row.getUser().getId());
                        row.setUsed(true);
                    }));
            throw new AuthFlowException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo enviar el correo de verificación. Puedes solicitar el reenvío.");
        }
    }

    public void cleanupExpiredTokens() {
        tokens.deleteExpiredTokens(LocalDateTime.now());
    }

    public boolean hasValidVerificationToken(User user) {
        return tokens.countActiveTokensByUserId(user.getId(), LocalDateTime.now()) > 0;
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
