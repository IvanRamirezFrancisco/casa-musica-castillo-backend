package com.security.service;

import com.security.entity.PasswordRecoveryAttempt;
import com.security.entity.PasswordResetToken;
import com.security.entity.User;
import com.security.exception.AuthFlowException;
import com.security.repository.PasswordRecoveryAttemptRepository;
import com.security.repository.PasswordResetTokenRepository;
import com.security.repository.UserRepository;
import com.security.util.PasswordValidator;
import com.security.validation.BcryptPasswordValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Servicio de recuperación de contraseña segura
 * Implementa rate limiting, tokens seguros y validación sin revelar información
 */
@Service
public class SecurePasswordResetService {

    private static final Logger logger = LoggerFactory.getLogger(SecurePasswordResetService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordRecoveryAttemptRepository recoveryAttemptRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired(required = false)
    private SessionManagementService sessionManagementService;

    @Autowired(required = false)
    private LoginSecurityService loginSecurityService;

    @Autowired(required = false)
    private PasswordValidator passwordValidator;

    @Value("${app.security.password-reset.token-expiration:3600000}")
    private long tokenExpirationMs;

    @Value("${app.base-url:http://localhost:4200}")
    private String baseUrl;

    private TransactionTemplate requiresNewTx;

    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        if (transactionManager != null) {
            this.requiresNewTx = new TransactionTemplate(transactionManager);
            this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    private record PendingReset(User recipient, String token) {}

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Solicita el restablecimiento de contraseña de forma segura.
     * NO revela si el email existe o no.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean requestPasswordReset(String email, HttpServletRequest request) {
        String ipAddress = getClientIpAddress(request);
        String userAgent = request != null ? request.getHeader("User-Agent") : null;
        return requestPasswordResetFromController(email, ipAddress, userAgent);
    }

    /**
     * Valida y resetea la contraseña usando el token
     */
    @Transactional
    public boolean resetPassword(String token, String newPassword, HttpServletRequest request) {
        String ipAddress = getClientIpAddress(request);
        String userAgent = request != null ? request.getHeader("User-Agent") : null;
        return resetPasswordFromController(token, newPassword, ipAddress, userAgent);
    }

    private void registrarIntento(String email, String ipAddress, String userAgent) {
        LocalDateTime now = LocalDateTime.now();

        try {
            HttpServletRequest currentRequest = ((ServletRequestAttributes) RequestContextHolder
                    .currentRequestAttributes()).getRequest();
            String freshUserAgent = currentRequest.getHeader("User-Agent");
            if (freshUserAgent != null && freshUserAgent.length() > 500) {
                freshUserAgent = freshUserAgent.substring(0, 500);
            }
            if (freshUserAgent != null) {
                userAgent = freshUserAgent;
            }
        } catch (Exception ignored) {
        }

        Optional<PasswordRecoveryAttempt> existingOpt = recoveryAttemptRepository.findByEmailAndIpAddress(email,
                ipAddress);

        PasswordRecoveryAttempt attempt;

        if (existingOpt.isPresent()) {
            attempt = existingOpt.get();

            if (attempt.isCurrentlyBlocked()) {
                Duration remaining = Duration.between(now, attempt.getBlockedUntil());
                long hours = remaining.toHours();
                long minutes = remaining.toMinutes() % 60;
                long seconds = remaining.getSeconds() % 60;

                String timeMsg;
                if (hours > 23) {
                    timeMsg = "más de 24 horas";
                } else if (hours > 0) {
                    timeMsg = hours + "h " + minutes + "m";
                } else if (minutes > 0) {
                    timeMsg = minutes + "m " + seconds + "s";
                } else {
                    timeMsg = seconds + " segundos";
                }

                logger.warn("Solicitud de recuperación bloqueada - Email: {}, IP: {}, Tiempo restante: {}",
                        maskEmail(email), ipAddress, timeMsg);

                throw new SecurityException(
                        "Demasiados intentos. Por favor espera " + timeMsg + " antes de intentarlo nuevamente.");
            }

            attempt.liftBlockIfExpired();
            if (userAgent != null) {
                attempt.setUserAgent(userAgent);
            }

        } else {
            attempt = new PasswordRecoveryAttempt(email, ipAddress, userAgent);
            recoveryAttemptRepository.save(attempt);
            return;
        }

        attempt.incrementAttempt();
        if (userAgent != null) {
            attempt.setUserAgent(userAgent);
        }
        int total = attempt.getAttemptCount();

        boolean shouldBlock = (total % 3 == 0);
        boolean isPermanent = (total >= 15);

        if (shouldBlock) {
            attempt.applyProgressiveBlockCumulative(total);

            if (isPermanent) {
                try {
                    Map<String, Object> detalles = new HashMap<>();
                    detalles.put("email", maskEmail(email));
                    detalles.put("ipAddress", ipAddress);
                    detalles.put("totalAttempts", total);
                    detalles.put("blockedUntil", attempt.getBlockedUntil().toString());
                    auditLogService.log(
                            "PERMANENT_BLOCK",
                            "SECURITY_EVENT",
                            "PASSWORD_RECOVERY",
                            null,
                            null,
                            detalles,
                            "CRITICAL",
                            false);
                } catch (Exception auditEx) {
                    logger.error("Error al registrar auditoría de bloqueo permanente: {}", auditEx.getMessage());
                }
            }
        }

        recoveryAttemptRepository.save(attempt);
    }

    private String generateSecureToken() {
        byte[] tokenBytes = new byte[48]; // 384 bits
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private void invalidateExistingTokens(User user) {
        List<PasswordResetToken> existingTokens = passwordResetTokenRepository.findByUser(user);
        for (PasswordResetToken token : existingTokens) {
            token.markAsUsed();
        }
        if (!existingTokens.isEmpty()) {
            passwordResetTokenRepository.saveAll(existingTokens);
        }
    }

    private boolean isPasswordValid(String password) {
        if (password == null || password.length() < 8 || password.length() > 255) {
            return false;
        }
        if (!BcryptPasswordValidator.isWithinLimit(password)) {
            return false;
        }
        if (password.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        boolean hasLower = password.matches(".*[a-z].*");
        boolean hasUpper = password.matches(".*[A-Z].*");
        boolean hasDigit = password.matches(".*\\d.*");
        boolean hasSpecial = password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*");
        if (!(hasLower && hasUpper && hasDigit && hasSpecial)) {
            return false;
        }
        if (passwordValidator != null) {
            return passwordValidator.validatePassword(password).isValid();
        }
        return true;
    }

    private String getClientIpAddress(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }
        String[] headers = {
                "CF-Connecting-IP",
                "X-Forwarded-For",
                "X-Real-IP",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP",
                "HTTP_X_FORWARDED_FOR"
        };

        for (String header : headers) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                ip = ip.split(",")[0].trim();
                return normalizeIp(ip);
            }
        }

        return normalizeIp(request.getRemoteAddr());
    }

    private String normalizeIp(String ip) {
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

    private String sanitizeInput(String input) {
        if (input == null)
            return "";
        return input.trim().replaceAll("[<>\"'&]", "");
    }

    public boolean isTokenValid(String token) {
        try {
            String sanitizedToken = sanitizeInput(token);
            if (sanitizedToken.isEmpty()) {
                return false;
            }

            Optional<PasswordResetToken> tokenOpt = passwordResetTokenRepository.findByToken(sanitizedToken);
            if (tokenOpt.isEmpty()) {
                return false;
            }

            PasswordResetToken resetToken = tokenOpt.get();
            return resetToken.isValid();

        } catch (Exception e) {
            logger.error("Error validating reset token: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean requestPasswordResetFromController(String email, String ipAddress, String userAgent) {
        String sanitizedEmail = sanitizeInput(email).toLowerCase(Locale.ROOT);
        String sanitizedIp = sanitizeInput(ipAddress);
        String sanitizedUserAgent = sanitizeInput(userAgent);

        if (sanitizedEmail.isEmpty()) {
            return false;
        }

        PendingReset pending = requiresNewTx != null
                ? requiresNewTx.execute(status -> prepareResetFromControllerInTx(sanitizedEmail, sanitizedIp, sanitizedUserAgent))
                : prepareResetFromControllerInTx(sanitizedEmail, sanitizedIp, sanitizedUserAgent);

        if (pending == null) {
            return true;
        }

        try {
            emailService.sendPasswordResetEmail(pending.recipient(), pending.token());
            logger.info("Password reset email dispatched for user: {} from IP: {}",
                    maskEmail(sanitizedEmail), sanitizedIp);
            return true;
        } catch (Exception e) {
            logger.error("Failed to send password reset email for {}: {}",
                    maskEmail(sanitizedEmail), e.getClass().getSimpleName());
            throw new AuthFlowException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo procesar el envío del correo de recuperación en este momento. Intenta nuevamente más tarde.");
        }
    }

    protected PendingReset prepareResetFromControllerInTx(String sanitizedEmail, String sanitizedIp, String sanitizedUserAgent) {
        registrarIntento(sanitizedEmail, sanitizedIp, sanitizedUserAgent);

        Optional<User> userOpt = userRepository.findByEmail(sanitizedEmail);
        if (userOpt.isEmpty()) {
            logger.info("Password reset requested for non-existent email: {}", maskEmail(sanitizedEmail));
            return null;
        }

        User user = userOpt.get();
        invalidateExistingTokens(user);

        String token = generateSecureToken();
        long validSeconds = tokenExpirationMs > 0 ? (tokenExpirationMs / 1000) : 3600;
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(validSeconds);

        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setToken(token);
        resetToken.setUser(user);
        resetToken.setExpiryDate(expiresAt);
        resetToken.setCreatedAt(LocalDateTime.now());
        resetToken.setIpAddress(sanitizedIp);
        resetToken.setUserAgent(sanitizedUserAgent);
        resetToken.setAttempts(0);
        resetToken.setUsed(false);

        passwordResetTokenRepository.save(resetToken);

        User recipientSnapshot = new User();
        recipientSnapshot.setId(user.getId());
        recipientSnapshot.setEmail(user.getEmail());
        recipientSnapshot.setFirstName(user.getFirstName());
        recipientSnapshot.setLastName(user.getLastName());

        return new PendingReset(recipientSnapshot, token);
    }

    @Transactional
    public boolean resetPasswordFromController(String token, String newPassword, String ipAddress, String userAgent) {
        try {
            String sanitizedToken = sanitizeInput(token);
            String sanitizedIp = sanitizeInput(ipAddress);
            String sanitizedUserAgent = sanitizeInput(userAgent);

            // NUNCA sanitizar ni mutar newPassword
            if (sanitizedToken.isEmpty() || !isPasswordValid(newPassword)) {
                logger.warn("Invalid input or weak password for password reset");
                return false;
            }

            Optional<PasswordResetToken> tokenOpt = passwordResetTokenRepository.findByToken(sanitizedToken);
            if (tokenOpt.isEmpty()) {
                logger.warn("Reset token not found");
                return false;
            }

            PasswordResetToken resetToken = tokenOpt.get();

            if (!resetToken.isValid() || resetToken.getUsedAt() != null || resetToken.hasExceededMaxAttempts(5)) {
                logger.warn("Invalid, expired, or already used token for reset");
                return false;
            }

            User user = resetToken.getUser();
            user.setPassword(passwordEncoder.encode(newPassword));
            user.setCredentialsNonExpired(true);
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);

            resetToken.markAsUsed();
            resetToken.setIpAddress(sanitizedIp);
            resetToken.setUserAgent(sanitizedUserAgent);
            passwordResetTokenRepository.save(resetToken);

            List<PasswordResetToken> otherTokens = passwordResetTokenRepository.findByUserAndUsed(user, false);
            for (PasswordResetToken otherToken : otherTokens) {
                if (!otherToken.getToken().equals(sanitizedToken)) {
                    otherToken.markAsUsed();
                }
            }
            if (!otherTokens.isEmpty()) {
                passwordResetTokenRepository.saveAll(otherTokens);
            }

            if (loginSecurityService != null) {
                loginSecurityService.clearFailedAttempts(user.getEmail());
            }
            if (sessionManagementService != null) {
                sessionManagementService.invalidateAllUserSessions(user.getEmail());
            }

            logger.info("Password reset successful for user: {} from IP: {}",
                    maskEmail(user.getEmail()), sanitizedIp);

            return true;

        } catch (Exception e) {
            logger.error("Error during password reset confirmation: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private String maskEmail(String email) {
        if (email == null || email.length() < 3)
            return "***";
        int atIndex = email.indexOf('@');
        if (atIndex > 0) {
            return email.substring(0, 1) + "***" + email.substring(atIndex);
        }
        return email.substring(0, 1) + "***";
    }

    @Scheduled(fixedRateString = "${app.security.password-reset.cleanup-interval:3600000}")
    @Transactional
    public void cleanupOldRecords() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(1);

            List<PasswordRecoveryAttempt> oldAttempts = recoveryAttemptRepository.findOldRecordsForCleanup(cutoff);
            if (!oldAttempts.isEmpty()) {
                recoveryAttemptRepository.deleteAll(oldAttempts);
            }

            LocalDateTime tokenCutoff = LocalDateTime.now().minusHours(48);
            List<PasswordResetToken> expiredTokens = passwordResetTokenRepository.findExpiredTokens(tokenCutoff);
            if (!expiredTokens.isEmpty()) {
                passwordResetTokenRepository.deleteAll(expiredTokens);
            }

        } catch (Exception e) {
            logger.error("Error during cleanup of old records: {}", e.getClass().getSimpleName());
        }
    }
}
