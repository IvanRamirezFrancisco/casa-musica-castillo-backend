package com.security.service;

import com.security.entity.PasswordResetToken;
import com.security.entity.PasswordRecoveryAttempt;
import com.security.entity.User;
import com.security.exception.AuthFlowException;
import com.security.exception.RateLimitExceededException;
import com.security.repository.PasswordResetTokenRepository;
import com.security.repository.PasswordRecoveryAttemptRepository;
import com.security.repository.UserRepository;
import com.security.util.LogSanitizer;
import com.security.util.PasswordValidator;
import com.security.validation.BcryptPasswordValidator;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@Transactional
public class PasswordResetService {
    private static final Logger logger = LoggerFactory.getLogger(PasswordResetService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordRecoveryAttemptRepository passwordRecoveryAttemptRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired(required = false)
    private SessionManagementService sessionManagementService;

    @Autowired(required = false)
    private LoginSecurityService loginSecurityService;

    @Autowired(required = false)
    private PasswordValidator passwordValidator;

    @Value("${app.base-url:http://localhost:4200}")
    private String baseUrl;

    @Value("${app.security.password-reset.token-expiration:3600000}")
    private long tokenExpirationMs;

    private TransactionTemplate requiresNewTx;

    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        if (transactionManager != null) {
            this.requiresNewTx = new TransactionTemplate(transactionManager);
            this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    private record PendingResetEmail(User recipient, String token, String normalizedEmail) {}

    // Configuración de límites
    private static final int MAX_ATTEMPTS = 3;
    private static final int BLOCK_DURATION_MINUTES = 5;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Solicitar reset de contraseña con control de intentos (Máximo 3 intentos por ciclo).
     * Separa la preparación en BD del envío por red para no retener conexiones abiertas,
     * mantiene comportamiento anti-enumeración para cuentas inexistentes y propaga 503
     * si el proveedor de correo falla sin penalizar el contador del usuario.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void requestPasswordReset(String email, HttpServletRequest request) {
        if (email == null || email.trim().isEmpty()) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "El correo electrónico es obligatorio");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String ipAddress = getClientIpAddress(request);

        PendingResetEmail pending;
        try {
            pending = requiresNewTx != null
                    ? requiresNewTx.execute(status -> prepareResetInTx(normalizedEmail, ipAddress))
                    : prepareResetInTx(normalizedEmail, ipAddress);
        } catch (RateLimitExceededException e) {
            logger.warn("Rate limit excedido para: {} desde IP: {}",
                    LogSanitizer.maskEmail(normalizedEmail), LogSanitizer.sanitize(ipAddress));
            throw e;
        }

        if (pending == null) {
            // Email no registrado: simular retardo uniforme anti-enumeración fuera de transacción
            try {
                Thread.sleep(100 + secureRandom.nextInt(150));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        try {
            emailService.sendPasswordResetEmail(pending.recipient(), pending.token());
            logger.info("Email de reset enviado para: {}", LogSanitizer.maskEmail(normalizedEmail));
        } catch (Exception emailError) {
            logger.error("Error enviando email de recuperación para {}: {}",
                    LogSanitizer.maskEmail(normalizedEmail), emailError.getClass().getSimpleName());
            revertFailedDeliveryAttempt(normalizedEmail);
            throw new AuthFlowException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo procesar el envío del correo de recuperación en este momento. Intenta nuevamente más tarde.");
        }
    }

    protected PendingResetEmail prepareResetInTx(String normalizedEmail, String ipAddress) {
        checkAttemptLimits(normalizedEmail, ipAddress);

        Optional<User> userOpt = findUserByEmailNormalized(normalizedEmail);
        if (userOpt.isEmpty()) {
            recordAttempt(normalizedEmail, ipAddress, false);
            logger.debug("Solicitud de reset para email no registrado desde IP: {}",
                    LogSanitizer.sanitize(ipAddress));
            return null;
        }

        recordAttempt(normalizedEmail, ipAddress, false);
        logger.debug("Intento de reset registrado para: {} desde IP: {}",
                LogSanitizer.maskEmail(normalizedEmail), LogSanitizer.sanitize(ipAddress));

        User user = userOpt.get();

        // Invalidar tokens anteriores del usuario
        passwordResetTokenRepository.deleteAllByUser(user);

        // Generar nuevo token seguro (256 bits) con vigencia de 1 hora (3600000 ms por defecto)
        String token = generateSecureToken();
        long validSeconds = tokenExpirationMs > 0 ? (tokenExpirationMs / 1000) : 3600;
        LocalDateTime expiryDate = LocalDateTime.now().plusSeconds(validSeconds);

        PasswordResetToken resetToken = new PasswordResetToken(
                token, user, expiryDate, ipAddress, resolveUserAgent());
        passwordResetTokenRepository.save(resetToken);

        User recipientSnapshot = new User();
        recipientSnapshot.setId(user.getId());
        recipientSnapshot.setEmail(user.getEmail());
        recipientSnapshot.setFirstName(user.getFirstName());
        recipientSnapshot.setLastName(user.getLastName());

        return new PendingResetEmail(recipientSnapshot, token, normalizedEmail);
    }

    private void revertFailedDeliveryAttempt(String normalizedEmail) {
        Runnable rollbackTask = () -> {
            Optional<PasswordRecoveryAttempt> existingOpt = passwordRecoveryAttemptRepository
                    .findMostRecentByEmail(normalizedEmail);
            if (existingOpt.isPresent()) {
                PasswordRecoveryAttempt attempt = existingOpt.get();
                int nextCount = Math.max(0, attempt.getAttemptCount() - 1);
                attempt.setAttemptCount(nextCount);
                if (nextCount < MAX_ATTEMPTS) {
                    attempt.setBlocked(false);
                    attempt.setBlockedUntil(null);
                }
                passwordRecoveryAttemptRepository.save(attempt);
            }
        };
        try {
            if (requiresNewTx != null) {
                requiresNewTx.executeWithoutResult(status -> rollbackTask.run());
            } else {
                rollbackTask.run();
            }
        } catch (Exception ignored) {
            // No ocultar el error principal de transporte
        }
    }

    private Optional<User> findUserByEmailNormalized(String normalizedEmail) {
        Optional<User> direct = userRepository.findByEmail(normalizedEmail);
        if (direct.isPresent()) {
            return direct;
        }
        return userRepository.findByEmail(normalizedEmail.trim());
    }

    /**
     * Validar token de reset
     */
    public boolean validateResetToken(String token) {
        try {
            if (token == null || token.trim().isEmpty()) {
                return false;
            }

            Optional<PasswordResetToken> resetTokenOpt = passwordResetTokenRepository.findByTokenAndUsedFalse(token.trim());

            if (resetTokenOpt.isEmpty()) {
                logger.debug("Token no encontrado o ya usado");
                return false;
            }

            PasswordResetToken resetToken = resetTokenOpt.get();
            if (resetToken.isExpired()) {
                logger.debug("Token de reset expirado");
                return false;
            }

            return true;

        } catch (Exception e) {
            logger.error("Error validando token: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Resetear contraseña con token (uso único + revocación de sesiones activas).
     */
    public boolean resetPassword(String token, String newPassword) {
        try {
            if (token == null || token.trim().isEmpty()) {
                logger.warn("Token vacío o nulo");
                return false;
            }

            if (!isStrongAndValidPassword(newPassword)) {
                logger.warn("Nueva contraseña no cumple política de seguridad en resetPassword");
                return false;
            }

            String cleanToken = token.trim();
            Optional<PasswordResetToken> resetTokenOpt = passwordResetTokenRepository.findByTokenAndUsedFalse(cleanToken);

            if (resetTokenOpt.isEmpty()) {
                logger.warn("Token no encontrado o ya usado");
                return false;
            }

            PasswordResetToken resetToken = resetTokenOpt.get();

            if (resetToken.isExpired()) {
                logger.warn("Token expirado");
                return false;
            }

            // Actualizar contraseña del usuario SIN recortar ni mutar la contraseña
            User user = resetToken.getUser();
            user.setPassword(passwordEncoder.encode(newPassword));
            user.setCredentialsNonExpired(true);
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);

            // Marcar token como usado (single-use)
            resetToken.markAsUsed();
            passwordResetTokenRepository.save(resetToken);

            // Invalidar cualquier otro token pendiente del usuario
            List<PasswordResetToken> otherTokens = passwordResetTokenRepository.findByUserAndUsed(user, false);
            for (PasswordResetToken other : otherTokens) {
                if (!other.getId().equals(resetToken.getId())) {
                    other.markAsUsed();
                }
            }
            if (!otherTokens.isEmpty()) {
                passwordResetTokenRepository.saveAll(otherTokens);
            }

            // Limpiar intentos de recuperación y bloqueos de login
            clearAttemptsForUser(user.getEmail());
            if (loginSecurityService != null) {
                loginSecurityService.clearFailedAttempts(user.getEmail());
            }

            // Revocar todas las sesiones activas tras cambio de contraseña por recuperación
            if (sessionManagementService != null) {
                sessionManagementService.invalidateAllUserSessions(user.getEmail());
            }

            logger.info("Contraseña actualizada exitosamente para: {}",
                    LogSanitizer.maskEmail(user.getEmail()));

            return true;

        } catch (Exception e) {
            logger.error("Error al resetear contraseña: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean isStrongAndValidPassword(String password) {
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
            PasswordValidator.ValidationResult result = passwordValidator.validatePassword(password);
            return result.isValid();
        }
        return true;
    }

    /**
     * Generar token seguro
     */
    private String generateSecureToken() {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    public Optional<User> getUserByToken(String token) {
        try {
            if (token == null) {
                return Optional.empty();
            }
            Optional<PasswordResetToken> resetTokenOpt = passwordResetTokenRepository.findByTokenAndUsedFalse(token.trim());
            if (resetTokenOpt.isPresent() && !resetTokenOpt.get().isExpired()) {
                return Optional.of(resetTokenOpt.get().getUser());
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error obteniendo usuario por token: " + e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void cleanupExpiredTokens() {
        try {
            passwordResetTokenRepository.deleteExpiredTokens(LocalDateTime.now());
            logger.info("Tokens expirados limpiados correctamente");
        } catch (Exception e) {
            logger.error("Error limpiando tokens expirados: " + e.getClass().getSimpleName());
        }
    }

    public long getActiveTokensCount(String email) {
        try {
            Optional<User> userOpt = findUserByEmailNormalized(email != null ? email.trim().toLowerCase(Locale.ROOT) : "");
            if (userOpt.isPresent()) {
                Optional<PasswordResetToken> tokenOpt = passwordResetTokenRepository
                        .findValidTokenByUser(userOpt.get(), LocalDateTime.now());
                return tokenOpt.isPresent() ? 1 : 0;
            }
            return 0;
        } catch (Exception e) {
            logger.error("Error contando tokens activos: " + e.getClass().getSimpleName());
            return 0;
        }
    }

    private void checkAttemptLimits(String email, String clientIp) {
        Optional<User> userOpt = findUserByEmailNormalized(email);
        boolean userExists = userOpt.isPresent();

        if (userExists) {
            checkEmailRateLimit(email);
        } else {
            checkIpRateLimit(clientIp);
        }
    }

    private void checkEmailRateLimit(String email) {
        Optional<PasswordRecoveryAttempt> existingAttempt = passwordRecoveryAttemptRepository
                .findMostRecentByEmail(email);

        if (existingAttempt.isPresent()) {
            PasswordRecoveryAttempt attempt = existingAttempt.get();

            if (attempt.isBlocked() && attempt.isBlockExpired()) {
                logger.debug("Auto-levantando bloqueo expirado para usuario: {}",
                        LogSanitizer.maskEmail(email));
                attempt.liftBlockIfExpired();
                passwordRecoveryAttemptRepository.save(attempt);
                return;
            }

            if (attempt.isCurrentlyBlocked()) {
                LocalDateTime now = LocalDateTime.now();
                long minutesLeft = Math.max(0, java.time.Duration.between(now, attempt.getBlockedUntil()).toMinutes());
                long secondsLeft = Math.max(0,
                        java.time.Duration.between(now, attempt.getBlockedUntil()).toSeconds() % 60);

                throw new RateLimitExceededException(
                        "Has excedido el límite de " + MAX_ATTEMPTS + " intentos de recuperación. " +
                                "Debes esperar " + Math.max(1, minutesLeft) + " minutos y " + secondsLeft
                                + " segundos más.",
                        minutesLeft,
                        secondsLeft,
                        attempt.getAttemptCount(),
                        MAX_ATTEMPTS);
            }
        }
    }

    private void checkIpRateLimit(String clientIp) {
        Optional<PasswordRecoveryAttempt> existingAttempt = passwordRecoveryAttemptRepository
                .findMostRecentByIp(clientIp);

        if (existingAttempt.isPresent()) {
            PasswordRecoveryAttempt attempt = existingAttempt.get();

            if (attempt.isBlocked() && attempt.isBlockExpired()) {
                logger.debug("Auto-levantando bloqueo expirado para IP: {}",
                        LogSanitizer.sanitize(clientIp));
                attempt.liftBlockIfExpired();
                passwordRecoveryAttemptRepository.save(attempt);
                return;
            }

            if (attempt.isCurrentlyBlocked()) {
                LocalDateTime now = LocalDateTime.now();
                long minutesLeft = Math.max(0, java.time.Duration.between(now, attempt.getBlockedUntil()).toMinutes());
                long secondsLeft = Math.max(0,
                        java.time.Duration.between(now, attempt.getBlockedUntil()).toSeconds() % 60);

                throw new RateLimitExceededException(
                        "Tu dirección IP ha excedido el límite de intentos. " +
                                "Debes esperar " + Math.max(1, minutesLeft) + " minutos y " + secondsLeft
                                + " segundos más.",
                        minutesLeft,
                        secondsLeft,
                        attempt.getAttemptCount(),
                        MAX_ATTEMPTS);
            }
        }
    }

    private void recordAttempt(String email, String clientIp, boolean successful) {
        try {
            Optional<User> userOpt = findUserByEmailNormalized(email);
            boolean userExists = userOpt.isPresent();

            if (userExists) {
                recordAttemptByEmail(email, clientIp);
            } else {
                recordAttemptByIp(email, clientIp);
            }

        } catch (Exception e) {
            logger.error("Error registrando intento de recuperación: " + e.getClass().getSimpleName());
        }
    }

    private void recordAttemptByEmail(String email, String clientIp) {
        String userAgent = resolveUserAgent();
        Optional<PasswordRecoveryAttempt> existingAttempt = passwordRecoveryAttemptRepository
                .findMostRecentByEmail(email);

        PasswordRecoveryAttempt attempt;
        if (existingAttempt.isPresent()) {
            attempt = existingAttempt.get();

            if (attempt.isCurrentlyBlocked()) {
                logger.debug("Intento durante bloqueo activo ignorado para: {}",
                        LogSanitizer.maskEmail(email));
                return;
            }

            attempt.setIpAddress(clientIp);
            attempt.setUserAgent(userAgent);
            attempt.incrementAttempt();

            if (attempt.getAttemptCount() >= MAX_ATTEMPTS) {
                attempt.applyProgressiveBlockCumulative(attempt.getAttemptCount());
                logger.warn("Usuario bloqueado por exceder {} intentos: {}",
                        MAX_ATTEMPTS, LogSanitizer.maskEmail(email));
            }
        } else {
            attempt = new PasswordRecoveryAttempt();
            attempt.setEmail(email);
            attempt.setIpAddress(clientIp);
            attempt.setUserAgent(userAgent);
            attempt.setAttemptCount(1);
            attempt.setLastAttempt(LocalDateTime.now());
            attempt.setCreatedAt(LocalDateTime.now());
            attempt.setBlocked(false);
        }

        passwordRecoveryAttemptRepository.save(attempt);
    }

    private void recordAttemptByIp(String email, String clientIp) {
        String userAgent = resolveUserAgent();
        Optional<PasswordRecoveryAttempt> existingAttempt = passwordRecoveryAttemptRepository
                .findMostRecentByIp(clientIp);

        PasswordRecoveryAttempt attempt;
        if (existingAttempt.isPresent()) {
            attempt = existingAttempt.get();

            if (attempt.isCurrentlyBlocked()) {
                logger.debug("Intento durante bloqueo activo ignorado para IP: {}",
                        LogSanitizer.sanitize(clientIp));
                return;
            }

            attempt.setEmail(email);
            attempt.setUserAgent(userAgent);
            attempt.incrementAttempt();

            if (attempt.getAttemptCount() >= MAX_ATTEMPTS) {
                attempt.applyProgressiveBlockCumulative(attempt.getAttemptCount());
                logger.warn("IP bloqueada por exceder {} intentos: {}",
                        MAX_ATTEMPTS, LogSanitizer.sanitize(clientIp));
            }
        } else {
            attempt = new PasswordRecoveryAttempt();
            attempt.setEmail(email);
            attempt.setIpAddress(clientIp);
            attempt.setUserAgent(userAgent);
            attempt.setAttemptCount(1);
            attempt.setLastAttempt(LocalDateTime.now());
            attempt.setCreatedAt(LocalDateTime.now());
            attempt.setBlocked(false);
        }

        passwordRecoveryAttemptRepository.save(attempt);
    }

    private String resolveUserAgent() {
        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                    .getRequest();
            String ua = request.getHeader("User-Agent");
            if (ua != null && ua.length() > 500) {
                ua = ua.substring(0, 500);
            }
            return ua;
        } catch (Exception e) {
            return null;
        }
    }

    private void clearAttemptsForUser(String email) {
        try {
            List<PasswordRecoveryAttempt> attempts = passwordRecoveryAttemptRepository
                    .findAllByEmailOrderByLastAttemptDesc(email);

            for (PasswordRecoveryAttempt attempt : attempts) {
                attempt.resetByAdmin();
                passwordRecoveryAttemptRepository.save(attempt);
            }
        } catch (Exception e) {
            logger.error("Error limpiando intentos de recuperación: " + e.getClass().getSimpleName());
        }
    }

    private String getClientIpAddress(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }
        String xForwardedForHeader = request.getHeader("X-Forwarded-For");
        if (xForwardedForHeader == null || xForwardedForHeader.isEmpty()) {
            return request.getRemoteAddr();
        } else {
            return xForwardedForHeader.split(",")[0].trim();
        }
    }

    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanupOldRecoveryAttempts() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
            List<PasswordRecoveryAttempt> oldAttempts = passwordRecoveryAttemptRepository
                    .findOldRecordsForCleanup(cutoff);

            if (!oldAttempts.isEmpty()) {
                passwordRecoveryAttemptRepository.deleteOldRecords(cutoff);
                logger.info("Limpieza automatica: {} intentos de recuperacion antiguos eliminados",
                        oldAttempts.size());
            }
        } catch (Exception e) {
            logger.error("Error en limpieza automática de intentos de recuperación: " + e.getClass().getSimpleName());
        }
    }
}
