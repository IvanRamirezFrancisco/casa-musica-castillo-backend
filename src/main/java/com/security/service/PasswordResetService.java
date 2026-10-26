package com.security.service;

import com.security.entity.PasswordResetToken;
import com.security.entity.PasswordRecoveryAttempt;
import com.security.entity.User;
import com.security.exception.RateLimitExceededException;
import com.security.repository.PasswordResetTokenRepository;
import com.security.repository.PasswordRecoveryAttemptRepository;
import com.security.repository.UserRepository;
import com.security.util.LogSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
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

    @Value("${app.base-url}")
    private String baseUrl;

    // Configuración de límites
    private static final int MAX_ATTEMPTS = 3;
    private static final int BLOCK_DURATION_MINUTES = 5;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Solicitar reset de contraseña con control de intentos (Máximo 3 intentos cada
     * 5 minutos)
     * SIEMPRE ejecuta la lógica completa para no revelar si el email existe
     */
    public void requestPasswordReset(String email, HttpServletRequest request) {
        String ipAddress = getClientIpAddress(request);

        try {
            // 1️⃣ VERIFICAR LÍMITE DE INTENTOS ANTES DE PROCESAR
            // Esta función ya verifica si el usuario existe y aplica rate limit correcto
            checkAttemptLimits(email, ipAddress);

            // 2️⃣ Verificar si el usuario existe (segunda verificación para lógica de
            // negocio)
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty()) {
                // Por seguridad, no revelamos si el email existe o no
                // REGISTRAR INTENTO para emails no existentes (rate limiting por IP)
                recordAttempt(email, ipAddress, false);
                logger.debug("Solicitud de reset para email no registrado desde IP: {}",
                        LogSanitizer.sanitize(ipAddress));

                // Simular el mismo tiempo de procesamiento
                Thread.sleep(100 + secureRandom.nextInt(200)); // 100-300ms aleatorio
                return; // Salir silenciosamente
            }

            // 3️⃣ REGISTRAR EL INTENTO para usuarios reales (rate limiting por email)
            recordAttempt(email, ipAddress, false);
            logger.debug("Intento de reset registrado para: {} desde IP: {}",
                    LogSanitizer.maskEmail(email), LogSanitizer.sanitize(ipAddress));

            User user = userOpt.get();

            // 4️⃣ Invalidar tokens anteriores del usuario
            passwordResetTokenRepository.deleteAllByUser(user);

            // 5️⃣ Generar nuevo token seguro
            String token = generateSecureToken();

            // 6️⃣ Crear y guardar el token
            PasswordResetToken resetToken = new PasswordResetToken(token, user);
            passwordResetTokenRepository.save(resetToken);

            // 7️⃣ Enviar email usando el servicio con Brevo API
            try {
                emailService.sendPasswordResetEmail(user, token);
                logger.info("Email de reset enviado para: {}", LogSanitizer.maskEmail(email));
                // NOTA: NO limpiamos intentos aquí, solo cuando se cambie la contraseña
                // exitosamente

            } catch (Exception emailError) {
                logger.error("Token generado pero error enviando email para {}: {}",
                        LogSanitizer.maskEmail(email), emailError.getClass().getSimpleName());
                // El intento ya está registrado, no agregamos otro
            }

        } catch (RateLimitExceededException e) {
            // Excepción de rate limiting - relanzar para manejo en controller
            logger.warn("Rate limit excedido para: {} desde IP: {}",
                    LogSanitizer.maskEmail(email), LogSanitizer.sanitize(ipAddress));
            throw e;
        } catch (Exception e) {
            logger.error("Error al procesar solicitud de reset: {}", e.getClass().getSimpleName());
            // NO registrar intento adicional si hay error después de verificaciones
        }
    }

    /**
     * Validar token de reset
     */
    public boolean validateResetToken(String token) {
        try {
            if (token == null || token.trim().isEmpty()) {
                return false;
            }

            Optional<PasswordResetToken> resetTokenOpt = passwordResetTokenRepository.findByTokenAndUsedFalse(token);

            if (resetTokenOpt.isEmpty()) {
                logger.debug("Token no encontrado o ya usado");
                return false;
            }

            PasswordResetToken resetToken = resetTokenOpt.get();
            boolean isExpired = resetToken.isExpired();

            if (isExpired) {
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
     * Resetear contraseña con token
     */
    public boolean resetPassword(String token, String newPassword) {
        try {
            // 1. Validar parámetros
            if (token == null || token.trim().isEmpty()) {
                logger.error("Token vacío o nulo");
                return false;
            }

            if (newPassword == null || newPassword.trim().length() < 8) {
                logger.error("Contraseña inválida");
                return false;
            }

            // 2. Buscar token válido
            Optional<PasswordResetToken> resetTokenOpt = passwordResetTokenRepository.findByTokenAndUsedFalse(token);

            if (resetTokenOpt.isEmpty()) {
                logger.error("Token no encontrado o ya usado");
                return false;
            }

            PasswordResetToken resetToken = resetTokenOpt.get();

            // 3. Verificar que no haya expirado
            if (resetToken.isExpired()) {
                logger.error("Token expirado");
                return false;
            }

            // 4. Actualizar contraseña del usuario
            User user = resetToken.getUser();
            user.setPassword(passwordEncoder.encode(newPassword));
            userRepository.save(user);

            // 5. Marcar token como usado
            resetToken.setUsed(true);
            passwordResetTokenRepository.save(resetToken);

            // 6. Limpiar intentos de recuperación al completar exitosamente el reset
            clearAttemptsForUser(user.getEmail());

            // 7. Enviar email de confirmación (opcional - la funcionalidad principal ya
            // funciona)
            try {
                // Note: We could implement a password changed notification method in
                // EmailService if needed
                logger.info("Contraseña actualizada exitosamente para: {}",
                        LogSanitizer.maskEmail(user.getEmail()));
            } catch (Exception emailError) {
                logger.error("Contraseña actualizada pero error enviando notificacion para {}: {}",
                        LogSanitizer.maskEmail(user.getEmail()), emailError.getClass().getSimpleName());
            }

            return true;

        } catch (Exception e) {
            logger.error("Error al resetear contraseña: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Generar token seguro
     */
    private String generateSecureToken() {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    /**
     * Obtener información del token (para debugging)
     */
    public Optional<User> getUserByToken(String token) {
        try {
            Optional<PasswordResetToken> resetTokenOpt = passwordResetTokenRepository.findByTokenAndUsedFalse(token);

            if (resetTokenOpt.isPresent()) {
                return Optional.of(resetTokenOpt.get().getUser());
            }

            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error obteniendo usuario por token: " + e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Limpiar tokens expirados (método de mantenimiento)
     */
    public void cleanupExpiredTokens() {
        try {
            passwordResetTokenRepository.deleteExpiredTokens(LocalDateTime.now());
            logger.info("Tokens expirados limpiados correctamente");
        } catch (Exception e) {
            logger.error("Error limpiando tokens expirados: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Contar tokens activos de un usuario
     */
    public long getActiveTokensCount(String email) {
        try {
            Optional<User> userOpt = userRepository.findByEmail(email);
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

    /**
     * ✅ Verificar límites de intentos de recuperación con lógica refactorizada:
     * 
     * CASO 1: Usuario existe en BD → Rate limit por EMAIL (persistente entre
     * navegadores/IPs)
     * CASO 2: Usuario NO existe → Rate limit por IP (evita revelar existencia)
     * 
     * Esto garantiza:
     * - Usuarios reales: Bloqueo consistente en cualquier navegador/dispositivo
     * - Emails falsos: Bloqueo por IP para evitar spam sin revelar si el email
     * existe
     */
    private void checkAttemptLimits(String email, String clientIp) {
        // 1️⃣ VERIFICAR SI EL USUARIO EXISTE EN BD
        Optional<User> userOpt = userRepository.findByEmail(email);
        boolean userExists = userOpt.isPresent();

        if (userExists) {
            // ✅ CASO 1: Usuario real → Rate limit por EMAIL (ignorando IP)
            checkEmailRateLimit(email);
        } else {
            // ✅ CASO 2: Email inexistente → Rate limit por IP (sin revelar que no existe)
            checkIpRateLimit(clientIp);
        }
    }

    /**
     * ✅ Verificar rate limit basado SOLO en EMAIL (para usuarios reales)
     * Esto hace que el bloqueo sea persistente entre navegadores y dispositivos
     */
    private void checkEmailRateLimit(String email) {
        // Buscar el registro más reciente para este email (independiente de IP)
        Optional<PasswordRecoveryAttempt> existingAttempt = passwordRecoveryAttemptRepository
                .findMostRecentByEmail(email);

        if (existingAttempt.isPresent()) {
            PasswordRecoveryAttempt attempt = existingAttempt.get();

            // Verificar si el bloqueo ya expiró y levantarlo (SIN resetear contador)
            if (attempt.isBlocked() && attempt.isBlockExpired()) {
                logger.debug("Auto-levantando bloqueo expirado para usuario: {}",
                        LogSanitizer.maskEmail(email));
                attempt.liftBlockIfExpired();
                passwordRecoveryAttemptRepository.save(attempt);
                return; // Usuario desbloqueado, puede continuar
            }

            // Si todavía está bloqueado, rechazar con tiempo restante
            if (attempt.isCurrentlyBlocked()) {
                LocalDateTime now = LocalDateTime.now();
                long minutesLeft = Math.max(0, java.time.Duration.between(now, attempt.getBlockedUntil()).toMinutes());
                long secondsLeft = Math.max(0,
                        java.time.Duration.between(now, attempt.getBlockedUntil()).toSeconds() % 60);
                long totalSecondsLeft = java.time.Duration.between(now, attempt.getBlockedUntil()).toSeconds();

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

        // No hay bloqueo activo, permitir el intento
    }

    /**
     * ✅ Verificar rate limit basado SOLO en IP (para emails inexistentes)
     * Esto evita spam masivo sin revelar qué emails existen en la BD
     */
    private void checkIpRateLimit(String clientIp) {
        // Buscar el registro más reciente para esta IP
        Optional<PasswordRecoveryAttempt> existingAttempt = passwordRecoveryAttemptRepository
                .findMostRecentByIp(clientIp);

        if (existingAttempt.isPresent()) {
            PasswordRecoveryAttempt attempt = existingAttempt.get();

            // Verificar si el bloqueo ya expiró y levantarlo (SIN resetear contador)
            if (attempt.isBlocked() && attempt.isBlockExpired()) {
                logger.debug("Auto-levantando bloqueo expirado para IP: {}",
                        LogSanitizer.sanitize(clientIp));
                attempt.liftBlockIfExpired();
                passwordRecoveryAttemptRepository.save(attempt);
                return;
            }

            // Si todavía está bloqueado, rechazar con tiempo restante
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

        // No hay bloqueo activo por IP, permitir el intento
    }

    /**
     * ✅ Registrar un intento de recuperación de contraseña
     * 
     * CASO 1: Usuario existe → Registrar por EMAIL (actualizar registro existente o
     * crear nuevo)
     * CASO 2: Usuario NO existe → Registrar por IP (para rate limiting sin revelar
     * existencia)
     */
    private void recordAttempt(String email, String clientIp, boolean successful) {
        try {
            // 1️⃣ Verificar si el usuario existe
            Optional<User> userOpt = userRepository.findByEmail(email);
            boolean userExists = userOpt.isPresent();

            if (userExists) {
                // ✅ CASO 1: Usuario real → Registrar/actualizar por EMAIL
                recordAttemptByEmail(email, clientIp);
            } else {
                // ✅ CASO 2: Email inexistente → Registrar/actualizar por IP
                recordAttemptByIp(email, clientIp);
            }

        } catch (Exception e) {
            logger.error("Error registrando intento de recuperación: " + e.getClass().getSimpleName());
        }
    }

    /**
     * ✅ Registrar intento para USUARIO REAL (por email, independiente de IP)
     */
    private void recordAttemptByEmail(String email, String clientIp) {
        String userAgent = resolveUserAgent();
        // Buscar el registro más reciente para este email
        Optional<PasswordRecoveryAttempt> existingAttempt = passwordRecoveryAttemptRepository
                .findMostRecentByEmail(email);

        PasswordRecoveryAttempt attempt;
        if (existingAttempt.isPresent()) {
            attempt = existingAttempt.get();

            // ✅ IMPORTANTE: Si ya está bloqueado, NO incrementar más el contador
            // Esto evita que el bloqueo se extienda con intentos adicionales
            if (attempt.isCurrentlyBlocked()) {
                logger.debug("Intento durante bloqueo activo ignorado para: {}",
                        LogSanitizer.maskEmail(email));
                return; // No incrementar, mantener bloqueo fijo
            }

            // Actualizar IP más reciente, user-agent y timestamp
            attempt.setIpAddress(clientIp);
            attempt.setUserAgent(userAgent);
            attempt.incrementAttempt();

            // Aplicar bloqueo si excede el límite
            if (attempt.getAttemptCount() >= MAX_ATTEMPTS) {
                attempt.applyProgressiveBlockCumulative(attempt.getAttemptCount());
                logger.warn("Usuario bloqueado por exceder {} intentos: {}",
                        MAX_ATTEMPTS, LogSanitizer.maskEmail(email));
            }
        } else {
            // Crear nuevo registro para este email
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
        logger.debug("Intento #{} registrado para: {} desde IP: {}",
                attempt.getAttemptCount(), LogSanitizer.maskEmail(email),
                LogSanitizer.sanitize(clientIp));
    }

    /**
     * ✅ Registrar intento para EMAIL INEXISTENTE (por IP, para rate limiting)
     */
    private void recordAttemptByIp(String email, String clientIp) {
        String userAgent = resolveUserAgent();
        // Buscar el registro más reciente para esta IP
        Optional<PasswordRecoveryAttempt> existingAttempt = passwordRecoveryAttemptRepository
                .findMostRecentByIp(clientIp);

        PasswordRecoveryAttempt attempt;
        if (existingAttempt.isPresent()) {
            attempt = existingAttempt.get();

            // ✅ IMPORTANTE: Si ya está bloqueado, NO incrementar más el contador
            if (attempt.isCurrentlyBlocked()) {
                logger.debug("Intento durante bloqueo activo ignorado para IP: {}",
                        LogSanitizer.sanitize(clientIp));
                return; // No incrementar, mantener bloqueo fijo
            }

            // Actualizar email intentado, user-agent y timestamp
            attempt.setEmail(email);
            attempt.setUserAgent(userAgent);
            attempt.incrementAttempt();

            // Aplicar bloqueo si excede el límite
            if (attempt.getAttemptCount() >= MAX_ATTEMPTS) {
                attempt.applyProgressiveBlockCumulative(attempt.getAttemptCount());
                logger.warn("IP bloqueada por exceder {} intentos: {}",
                        MAX_ATTEMPTS, LogSanitizer.sanitize(clientIp));
            }
        } else {
            // Crear nuevo registro para esta IP
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
        logger.debug("Intento #{} registrado para IP: {} (email inexistente)",
                attempt.getAttemptCount(), LogSanitizer.sanitize(clientIp));
    }

    /**
     * Obtiene el User-Agent del request HTTP actual vía RequestContextHolder.
     * Retorna null si se llama fuera de contexto web.
     */
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

    /**
     * Limpiar intentos de recuperación para un usuario después de un reset exitoso
     */
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

    /**
     * Obtener la dirección IP del cliente desde HttpServletRequest
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedForHeader = request.getHeader("X-Forwarded-For");
        if (xForwardedForHeader == null || xForwardedForHeader.isEmpty()) {
            return request.getRemoteAddr();
        } else {
            // El primer IP en la cadena es el IP real del cliente
            return xForwardedForHeader.split(",")[0].trim();
        }
    }

    /**
     * Limpieza automática de intentos de recuperación antiguos
     * Se ejecuta cada 30 minutos
     */
    @Scheduled(fixedRate = 30 * 60 * 1000) // 30 minutos en milisegundos
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