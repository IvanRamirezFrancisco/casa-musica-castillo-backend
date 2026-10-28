package com.security.service;

import com.security.entity.User;
import com.security.entity.VerificationToken;
import com.security.enums.TokenType;
import com.security.exception.AuthFlowException;
import com.security.repository.VerificationTokenRepository;
import com.security.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Transactional
public class VerificationService {

    private static final Logger logger = LoggerFactory.getLogger(VerificationService.class);

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private EmailService emailService;

    @Value("${app.security.verification.email.expiration:86400000}")
    private long emailTokenExpirationMs;

    private TransactionTemplate requiresNewTx;

    private static final int MAX_RESENDS_PER_WINDOW = 3;
    private static final int RESEND_WINDOW_MINUTES = 5;
    private final Map<String, ResendCounter> resendCounters = new ConcurrentHashMap<>();

    private record ResendCounter(int count, LocalDateTime windowStart) {}
    private record PendingVerification(User recipient, String token) {}

    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        if (transactionManager != null) {
            this.requiresNewTx = new TransactionTemplate(transactionManager);
            this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public String generateVerificationToken(User user) {
        StringBuilder token = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            token.append(CHARACTERS.charAt(secureRandom.nextInt(CHARACTERS.length())));
        }
        String tokenValue = token.toString();

        verificationTokenRepository.deleteByUser(user);

        VerificationToken verificationToken = new VerificationToken();
        verificationToken.setToken(tokenValue);
        verificationToken.setUser(user);
        verificationToken.setTokenType(TokenType.EMAIL_VERIFICATION);
        verificationToken.setExpiryDate(LocalDateTime.now().plusSeconds(emailTokenExpirationMs / 1000));
        verificationToken.setUsed(false);

        verificationTokenRepository.save(verificationToken);

        return tokenValue;
    }

    public boolean verifyToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        Optional<VerificationToken> verificationTokenOpt = verificationTokenRepository.findValidToken(
                token.trim(), LocalDateTime.now());

        if (verificationTokenOpt.isPresent()) {
            VerificationToken verificationToken = verificationTokenOpt.get();
            User user = verificationToken.getUser();

            user.setEnabled(true);
            user.setUpdatedAt(LocalDateTime.now());
            userService.save(user);

            verificationToken.setUsed(true);
            verificationTokenRepository.save(verificationToken);
            verificationTokenRepository.markTokenAsUsed(verificationToken.getId());
            logger.info("Usuario verificado: {}", LogSanitizer.maskEmail(user.getEmail()));
            return true;
        }

        logger.warn("Token de verificacion invalido o expirado");
        return false;
    }

    public void sendVerificationEmail(User user) {
        String token = generateVerificationToken(user);
        emailService.sendVerificationEmail(user, token);
    }

    /**
     * Reenvía el correo de verificación de manera segura y anti-enumeración.
     * Si el correo no existe o ya está verificado, retorna de forma uniforme sin revelar estado.
     * Separa la preparación en BD del envío por red para no retener transacciones abiertas.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void resendVerificationEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "El correo electrónico es obligatorio");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        enforceResendRateLimit(normalizedEmail);

        PendingVerification pending = requiresNewTx != null
                ? requiresNewTx.execute(status -> prepareResendInTx(normalizedEmail))
                : prepareResendInTx(normalizedEmail);

        if (pending == null) {
            // Respuesta uniforme anti-enumeración para cuentas inexistentes o ya verificadas
            return;
        }

        try {
            emailService.sendVerificationEmail(pending.recipient(), pending.token());
            logger.info("Correo de verificación reenviado a: {}", LogSanitizer.maskEmail(normalizedEmail));
        } catch (Exception ex) {
            decrementResendCount(normalizedEmail);
            logger.error("Error enviando reenvío de verificación a {}: {}",
                    LogSanitizer.maskEmail(normalizedEmail), ex.getClass().getSimpleName());
            throw new AuthFlowException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo enviar el correo de verificación en este momento. Intenta nuevamente más tarde.");
        }
    }

    protected PendingVerification prepareResendInTx(String normalizedEmail) {
        Optional<User> userOpt = userService.findByEmail(normalizedEmail);
        if (userOpt.isEmpty()) {
            logger.debug("Solicitud de reenvío para email no registrado: {}", LogSanitizer.maskEmail(normalizedEmail));
            return null;
        }

        User user = userOpt.get();
        if (Boolean.TRUE.equals(user.getEnabled())) {
            logger.debug("Solicitud de reenvío ignorada (cuenta ya verificada): {}", LogSanitizer.maskEmail(normalizedEmail));
            return null;
        }

        String token = generateVerificationToken(user);
        User recipientSnapshot = new User();
        recipientSnapshot.setId(user.getId());
        recipientSnapshot.setEmail(user.getEmail());
        recipientSnapshot.setFirstName(user.getFirstName());
        recipientSnapshot.setLastName(user.getLastName());

        return new PendingVerification(recipientSnapshot, token);
    }

    private void enforceResendRateLimit(String normalizedEmail) {
        LocalDateTime now = LocalDateTime.now();
        resendCounters.compute(normalizedEmail, (key, existing) -> {
            if (existing == null || existing.windowStart().plusMinutes(RESEND_WINDOW_MINUTES).isBefore(now)) {
                return new ResendCounter(1, now);
            }
            if (existing.count() >= MAX_RESENDS_PER_WINDOW) {
                throw new AuthFlowException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Has solicitado demasiados reenvíos de verificación. Espera unos minutos antes de intentarlo nuevamente.");
            }
            return new ResendCounter(existing.count() + 1, existing.windowStart());
        });
    }

    private void decrementResendCount(String normalizedEmail) {
        resendCounters.computeIfPresent(normalizedEmail, (key, existing) -> {
            int nextCount = Math.max(0, existing.count() - 1);
            return nextCount == 0 ? null : new ResendCounter(nextCount, existing.windowStart());
        });
    }

    public void cleanupExpiredTokens() {
        verificationTokenRepository.deleteExpiredTokens(LocalDateTime.now());
        logger.info("Tokens de verificacion expirados eliminados");
    }

    public boolean hasValidVerificationToken(User user) {
        return verificationTokenRepository.countActiveTokensByUserId(user.getId(), LocalDateTime.now()) > 0;
    }
}
