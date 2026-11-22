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
    @Value("${app.maintenance.enabled:true}")
    private boolean maintenanceEnabled = true;

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

    @Autowired
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

    @Autowired private SecurityMailQueue securityMail;
    @Autowired private VerificationRequestLimit sharedLimits;
    @Autowired private ActivationClientAddress clientAddresses;
    @Autowired private java.time.Clock clock;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    @Value("${app.security.password-reset.cooldown-seconds:60}") private int resetCooldown;
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
        return request == null ? "127.0.0.1" : clientAddresses.resolve(request);
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

    @Transactional(readOnly=true)
    public boolean isTokenValid(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{1,512}")) return false;
        return findResetToken(token).map(row -> {
            User user = row.getUser();
            return eligible(user) && !Boolean.TRUE.equals(row.getUsed()) && row.getExpiryDate().isAfter(LocalDateTime.now(clock))
                && !java.util.Set.of("PENDING","FAILED","CANCELLED").contains(row.getDeliveryState());
        }).orElse(false);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean requestPasswordResetFromController(String email, String ipAddress, String userAgent) {
        String address = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (address.isEmpty()) return false;
        sharedLimits.check("password-reset-request", ipAddress, 30);
        requiresNewTx.execute(status -> prepareResetFromControllerInTx(address, ipAddress, ""));
        return true;
    }

    protected PendingReset prepareResetFromControllerInTx(String address, String ipAddress, String userAgent) {
        User user = userRepository.findIdByEmailForVerification(address).flatMap(userRepository::findByIdForAuthUpdate).orElse(null);
        if (!eligible(user)) return null;
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (passwordResetTokenRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(user.getId())
            .filter(row -> row.getCreatedAt().isAfter(now.minusSeconds(Math.max(30, resetCooldown)))).isPresent()
            || passwordResetTokenRepository.countByUserIdAndCreatedAtAfter(user.getId(), now.minusMinutes(5)) >= 3) return null;
        if (!sharedLimits.reserveDeliveryFor("reset-recipient-day",address)) return null;
        String token = generateSecureToken();
        LocalDateTime expires = now.plusSeconds(Math.max(60, Math.min(86400, tokenExpirationMs / 1000)));
        PasswordResetToken row = new PasswordResetToken(resetDigest(token),user,expires);
        row.setCreatedAt(now);row.setDeliveryState("PENDING");row.setIpAddress(null);row.setUserAgent(null);
        passwordResetTokenRepository.saveAndFlush(row);
        securityMail.enqueueReset(user,row.getId(),token,expires);
        return null;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean resetPasswordFromController(String token, String newPassword, String ipAddress, String userAgent) {
        sharedLimits.check("password-reset-confirm", ipAddress, 60);
        if (token == null || !token.matches("[A-Za-z0-9_-]{1,512}") || !isPasswordValid(newPassword)) return false;
        return Boolean.TRUE.equals(requiresNewTx.execute(status -> {
            PasswordResetToken row = findResetToken(token).orElse(null);
            if (row == null) return false;
            User user = userRepository.findByIdForAuthUpdate(row.getUser().getId()).orElse(null);
            entityManager.refresh(row);
            if (!eligible(user) || Boolean.TRUE.equals(row.getUsed()) || row.getUsedAt()!=null
                || !row.getExpiryDate().isAfter(LocalDateTime.now(clock))
                || java.util.Set.of("PENDING","FAILED","CANCELLED").contains(row.getDeliveryState())) return false;
            if (user.hasLocalPassword() && passwordEncoder.matches(newPassword, user.getPassword())) return false;
            LocalDateTime now=LocalDateTime.now(clock);
            user.setPassword(passwordEncoder.encode(newPassword));user.setCredentialsNonExpired(true);user.setUpdatedAt(now);
            row.markAsUsed();
            sessionManagementService.invalidateAllUserSessions(user.getEmail());
            jdbc.update("UPDATE auth.refresh_tokens SET revoked=true,revoked_at=?,revoke_reason='PASSWORD_RESET' WHERE user_id=? AND revoked=false", now, user.getId());
            jdbc.update("UPDATE security.password_reset_tokens SET used=true,used_at=? WHERE user_id=?",now,user.getId());
            jdbc.update("UPDATE auth.login_challenges SET consumed_at=?,email_code_hash=NULL WHERE user_id=? AND consumed_at IS NULL",now,user.getId());
            jdbc.update("UPDATE auth.security_mail_outbox SET payload=NULL,state='CANCELLED' WHERE user_id=? AND purpose='PASSWORD_RESET' AND state='PENDING'",user.getId());
            securityMail.enqueueNotification(user);
            try { auditLogService.log("UPDATE","PASSWORD_RESET","USER",user.getId(),null,null,"WARNING",true); }
            catch (RuntimeException ex) { logger.warn("password_reset_audit_unavailable account={}",user.getId()); }
            if (loginSecurityService!=null) loginSecurityService.clearFailedAttempts(user.getEmail());
            logger.info("password_reset_confirmed account={}",user.getId());
            return true;
        }));
    }

    private boolean eligible(User user) {
        return user != null && Boolean.TRUE.equals(user.getEnabled()) && Boolean.TRUE.equals(user.getAccountNonLocked())
            && Boolean.TRUE.equals(user.getAccountNonExpired()) && !user.isPendingSelfRegistration();
    }
    private Optional<PasswordResetToken> findResetToken(String raw) {
        return passwordResetTokenRepository.findByToken(resetDigest(raw)).or(() -> passwordResetTokenRepository.findByToken(raw));
    }
    public static String resetDigest(String token) {
        try {
            return "pr1:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("Reset token protection unavailable"); }
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
        if (!maintenanceEnabled) return;
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
