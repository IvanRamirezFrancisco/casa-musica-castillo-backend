package com.security.service;

import com.security.entity.User;
import com.security.entity.LoginAttempt;
import com.security.repository.UserRepository;
import com.security.repository.LoginAttemptRepository;
import com.security.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Servicio de seguridad para login con bloqueo de intentos fallidos
 * y gestión de sesiones usando almacenamiento en memoria + persistencia en BD
 * 
 * NOTA: No usar @Transactional a nivel de clase para evitar rollback silencioso
 */
@Service
public class LoginSecurityService {

    private static final Logger logger = LoggerFactory.getLogger(LoginSecurityService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LoginAttemptRepository loginAttemptRepository;

    /** @Lazy para evitar dependencia circular: AuditLogService → (async context) */
    @Autowired
    @Lazy
    private AuditLogService auditLogService;

    @Value("${app.security.login.max-attempts:5}")
    private int maxLoginAttempts;

    @Value("${app.security.login.lockout-duration-minutes:30}")
    private int lockoutDurationMinutes;

    @Value("${app.security.login.progressive-delay:true}")
    private boolean progressiveDelay;

    @Value("${app.security.session.inactivity-timeout-minutes:15}")
    private int sessionInactivityTimeout;

    // Almacenamiento en memoria para intentos fallidos y bloqueos
    private final Map<String, AttemptRecord> failedAttempts = new ConcurrentHashMap<>();
    private final Map<String, LockRecord> accountLocks = new ConcurrentHashMap<>();
    private final Map<String, SessionRecord> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> globalInvalidations = new ConcurrentHashMap<>();

    // Clases internas para almacenamiento
    private static class AttemptRecord {
        int count;
        LocalDateTime lastAttempt;

        AttemptRecord(int count) {
            this.count = count;
            this.lastAttempt = LocalDateTime.now();
        }

        void increment() {
            this.count++;
            this.lastAttempt = LocalDateTime.now();
        }
    }

    private static class LockRecord {
        LocalDateTime lockedUntil;

        LockRecord(LocalDateTime lockedUntil) {
            this.lockedUntil = lockedUntil;
        }
    }

    private static class SessionRecord {
        LocalDateTime lastActivity;

        SessionRecord() {
            this.lastActivity = LocalDateTime.now();
        }

        void updateActivity() {
            this.lastActivity = LocalDateTime.now();
        }
    }

    /**
     * Registra un intento fallido de login.
     * La IP se resuelve automáticamente desde el RequestContextHolder.
     */
    public void recordFailedAttempt(String identifier) {
        recordFailedAttempt(identifier, resolveCurrentIp(), "INVALID_CREDENTIALS");
    }

    /**
     * Registra un intento fallido de login con detalles adicionales.
     * Si ipAddress es null, se resuelve automáticamente desde el
     * RequestContextHolder.
     */
    public void recordFailedAttempt(String identifier, String ipAddress, String reason) {
        try {
            String key = sanitize(identifier);

            // 1. Actualizar en memoria para respuesta rápida
            AttemptRecord record = failedAttempts.get(key);
            if (record == null) {
                record = new AttemptRecord(1);
            } else {
                record.increment();
            }
            failedAttempts.put(key, record);

            // 2. Persistir en BD para auditoría (solo si es email válido)
            if (identifier != null && identifier.contains("@")) {
                try {
                    String resolvedIp = (ipAddress != null && !ipAddress.isEmpty())
                            ? ipAddress
                            : resolveCurrentIp();

                    LoginAttempt loginAttempt = new LoginAttempt();
                    loginAttempt.setEmail(identifier);
                    loginAttempt.setIpAddress(resolvedIp);
                    loginAttempt.setSuccessful(false);
                    loginAttempt.setFailureReason(reason);
                    loginAttempt.setAttemptTime(LocalDateTime.now());
                    loginAttemptRepository.save(loginAttempt);
                } catch (Exception dbError) {
                    logger.warn("Could not persist login attempt to DB: {}", dbError.getClass().getSimpleName());
                }
            }

            logger.warn("🚨 Failed login attempt {} for identifier: {}",
                    record.count, LogSanitizer.maskEmail(identifier));

            // Si se excede el máximo, bloquear cuenta
            if (record.count >= maxLoginAttempts) {
                lockAccount(identifier, record.count);
            }

        } catch (Exception e) {
            logger.error("Error recording failed attempt: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Registra un intento exitoso de login.
     * Si ipAddress es null, se resuelve automáticamente desde el
     * RequestContextHolder.
     */
    public void recordSuccessfulAttempt(String email, String ipAddress) {
        try {
            String resolvedIp = (ipAddress != null && !ipAddress.isEmpty())
                    ? ipAddress
                    : resolveCurrentIp();

            // Persistir en BD para auditoría
            LoginAttempt loginAttempt = new LoginAttempt();
            loginAttempt.setEmail(email);
            loginAttempt.setIpAddress(resolvedIp);
            loginAttempt.setSuccessful(true);
            loginAttempt.setAttemptTime(LocalDateTime.now());
            loginAttemptRepository.save(loginAttempt);

            logger.info("✅ Successful login for: {}", LogSanitizer.maskEmail(email));
        } catch (Exception e) {
            logger.warn("Could not persist successful login attempt: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Bloquea una cuenta temporalmente
     */
    private void lockAccount(String identifier, int attempts) {
        try {
            String key = sanitize(identifier);

            // Calcular duración del bloqueo con delay progresivo
            int lockDuration = calculateLockoutDuration(attempts);

            LocalDateTime unlockTime = LocalDateTime.now().plusMinutes(lockDuration);
            accountLocks.put(key, new LockRecord(unlockTime));

            logger.warn("🔒 ACCOUNT LOCKED for identifier: {} for {} minutes due to {} failed attempts",
                    LogSanitizer.maskEmail(identifier), lockDuration, attempts);

            // Auditoría del bloqueo de cuenta — severity WARNING
            try {
                auditLogService.log(
                        "ACCOUNT_LOCK", "ACCOUNT_LOCK", "USER",
                        null, null,
                        java.util.Map.of(
                                "identifier", LogSanitizer.maskEmail(identifier),
                                "failedAttempts", attempts,
                                "lockDurationMinutes", lockDuration),
                        "WARNING", true);
            } catch (Exception auditEx) {
                logger.warn("No se pudo registrar audit log para bloqueo de cuenta: {}", auditEx.getClass().getSimpleName());
            }

        } catch (Exception e) {
            logger.error("Error locking account: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Calcula la duración del bloqueo con delay progresivo
     * Primera vez: 15 min, Segunda: 30 min, Tercera: 60 min, etc.
     */
    private int calculateLockoutDuration(int attempts) {
        if (!progressiveDelay) {
            return lockoutDurationMinutes;
        }

        // Delay progresivo: 15min, 30min, 60min, 120min...
        // Base de 15 minutos, se duplica con cada bloqueo adicional
        int baseDuration = 15;
        int multiplier = Math.min(attempts - maxLoginAttempts + 1, 6);
        return baseDuration * (int) Math.pow(2, multiplier - 1);
    }

    /**
     * Verifica si una cuenta está bloqueada
     */
    public boolean isAccountLocked(String identifier) {
        try {
            String key = sanitize(identifier);
            LockRecord lock = accountLocks.get(key);

            if (lock == null) {
                return false;
            }

            boolean isLocked = LocalDateTime.now().isBefore(lock.lockedUntil);

            if (!isLocked) {
                // El bloqueo ha expirado, limpiar
                accountLocks.remove(key);
                clearFailedAttempts(identifier);
            }

            return isLocked;

        } catch (Exception e) {
            logger.error("Error checking account lock status: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Obtiene el tiempo restante de bloqueo en minutos
     */
    public long getLockoutRemainingMinutes(String identifier) {
        try {
            String key = sanitize(identifier);
            LockRecord lock = accountLocks.get(key);

            if (lock == null) {
                return 0;
            }

            return ChronoUnit.MINUTES.between(LocalDateTime.now(), lock.lockedUntil);

        } catch (Exception e) {
            logger.error("Error getting lockout remaining time: {}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Obtiene el tiempo restante de bloqueo en segundos (para cuenta regresiva)
     */
    public long getLockoutRemainingSeconds(String identifier) {
        try {
            String key = sanitize(identifier);
            LockRecord lock = accountLocks.get(key);

            if (lock == null) {
                return 0;
            }

            long seconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), lock.lockedUntil);
            return Math.max(0, seconds); // No devolver números negativos

        } catch (Exception e) {
            logger.error("Error getting lockout remaining seconds: {}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Limpia los intentos fallidos tras un login exitoso
     */
    public void clearFailedAttempts(String identifier) {
        try {
            String key = sanitize(identifier);
            failedAttempts.remove(key);

        } catch (Exception e) {
            logger.error("Error clearing failed attempts: {}", e.getClass().getSimpleName());
        }
    }

    /** Clear failed-attempt counters only after the complete login, including 2FA. */
    public void clearAfterSuccessfulLogin(String email) {
        clearFailedAttempts(email);
        clearFailedAttempts(resolveCurrentIp());
    }

    /**
     * Registra actividad de sesión
     */
    public void recordSessionActivity(String userId) {
        try {
            SessionRecord session = activeSessions.get(userId);
            if (session == null) {
                session = new SessionRecord();
            } else {
                session.updateActivity();
            }

            activeSessions.put(userId, session);

        } catch (Exception e) {
            logger.error("Error recording session activity: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Verifica si una sesión está activa
     */
    public boolean isSessionActive(String userId) {
        try {
            SessionRecord session = activeSessions.get(userId);

            if (session == null) {
                return false;
            }

            LocalDateTime expiry = session.lastActivity.plusMinutes(sessionInactivityTimeout);
            boolean isActive = LocalDateTime.now().isBefore(expiry);

            if (!isActive) {
                activeSessions.remove(userId);
            }

            return isActive;

        } catch (Exception e) {
            logger.error("Error checking session activity: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Invalida una sesión específica
     */
    public void invalidateSession(String userId) {
        try {
            activeSessions.remove(userId);
            logger.info("Session invalidated for user: {}", userId);

        } catch (Exception e) {
            logger.error("Error invalidating session: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Invalida todas las sesiones de un usuario (logout desde todos los
     * dispositivos)
     */
    public void invalidateAllUserSessions(String userId) {
        try {
            // Marcar para invalidación global
            globalInvalidations.put(userId, LocalDateTime.now());

            // Limpiar sesiones conocidas
            invalidateSession(userId);

            logger.info("All sessions invalidated for user: {}", userId);

        } catch (Exception e) {
            logger.error("Error invalidating all user sessions: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Verifica si un token debe ser invalidado globalmente
     */
    public boolean isTokenGloballyInvalidated(String userId, LocalDateTime tokenIssuedAt) {
        try {
            LocalDateTime invalidationTime = globalInvalidations.get(userId);

            if (invalidationTime == null) {
                return false;
            }

            return tokenIssuedAt.isBefore(invalidationTime);

        } catch (Exception e) {
            logger.error("Error checking global token invalidation: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Obtiene el número de intentos fallidos
     */
    public int getFailedAttempts(String identifier) {
        try {
            String key = sanitize(identifier);
            AttemptRecord record = failedAttempts.get(key);
            return record == null ? 0 : record.count;

        } catch (Exception e) {
            logger.error("Error getting failed attempts: {}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Limpieza automática de datos antiguos
     * Se ejecuta cada hora automáticamente
     */
    @Scheduled(fixedRate = 3600000) // Cada hora
    public void cleanupOldData() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(24);

            // Limpiar intentos antiguos
            int attemptsCleaned = failedAttempts.size();
            failedAttempts.entrySet().removeIf(entry -> entry.getValue().lastAttempt.isBefore(cutoff));
            attemptsCleaned -= failedAttempts.size();

            // Limpiar bloqueos expirados
            int locksCleaned = accountLocks.size();
            accountLocks.entrySet().removeIf(entry -> LocalDateTime.now().isAfter(entry.getValue().lockedUntil));
            locksCleaned -= accountLocks.size();

            // Limpiar sesiones expiradas
            LocalDateTime sessionCutoff = LocalDateTime.now().minusMinutes(sessionInactivityTimeout * 2);
            int sessionsCleaned = activeSessions.size();
            activeSessions.entrySet().removeIf(entry -> entry.getValue().lastActivity.isBefore(sessionCutoff));
            sessionsCleaned -= activeSessions.size();

            // Limpiar invalidaciones globales antiguas (más de 7 días)
            LocalDateTime globalCutoff = LocalDateTime.now().minusDays(7);
            globalInvalidations.entrySet().removeIf(entry -> entry.getValue().isBefore(globalCutoff));

            if (attemptsCleaned > 0 || locksCleaned > 0 || sessionsCleaned > 0) {
                logger.info("🧹 Security cleanup: {} attempts, {} locks, {} sessions removed",
                        attemptsCleaned, locksCleaned, sessionsCleaned);
            }

        } catch (Exception e) {
            logger.error("Error during cleanup: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Sanitiza un identificador para uso como key
     */
    private String sanitize(String input) {
        if (input == null)
            return "";
        return input.replaceAll("[^a-zA-Z0-9@._-]", "").toLowerCase();
    }

    /**
     * Resuelve la IP real del cliente leyendo el RequestContextHolder.
     * Devuelve "UNKNOWN" si no hay request activa (ej: tarea programada).
     * Normaliza la dirección IPv6 de loopback a "127.0.0.1".
     */
    private String resolveCurrentIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "UNKNOWN";
            }
            return getClientIpAddress(attrs.getRequest());
        } catch (Exception e) {
            logger.warn("No se pudo resolver la IP del request actual: {}", e.getClass().getSimpleName());
            return "UNKNOWN";
        }
    }

    /**
     * Resuelve el User-Agent del request actual desde el RequestContextHolder.
     * Devuelve null si no hay request activa.
     */
    private String resolveCurrentUserAgent() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            return attrs.getRequest().getHeader("User-Agent");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Obtiene la IP real del cliente considerando proxies y CDNs.
     * Orden de prioridad: CF-Connecting-IP → X-Forwarded-For → X-Real-IP
     * → Proxy-Client-IP → WL-Proxy-Client-IP → HTTP_X_FORWARDED_FOR → RemoteAddr
     * Normaliza la dirección IPv6 de loopback (::1) a "127.0.0.1".
     */
    private String getClientIpAddress(HttpServletRequest request) {
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
                // X-Forwarded-For puede contener lista: "clientIp, proxy1, proxy2"
                ip = ip.split(",")[0].trim();
                return normalizeIp(ip);
            }
        }

        return normalizeIp(request.getRemoteAddr());
    }

    /**
     * Normaliza la dirección IPv6 de loopback a "127.0.0.1".
     */
    private String normalizeIp(String ip) {
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

    /**
     * Enmascara un identificador para logging
     */
    private String maskIdentifier(String identifier) {
        if (identifier == null || identifier.length() < 3) {
            return "***";
        }

        if (identifier.contains("@")) {
            // Es un email
            String[] parts = identifier.split("@");
            String localPart = parts[0];
            String domain = parts[1];
            String maskedLocal = localPart.length() > 2 ? localPart.substring(0, 2) + "***" : "***";
            return maskedLocal + "@" + domain;
        } else {
            // Es otro tipo de identificador
            return identifier.substring(0, 2) + "***" +
                    identifier.substring(Math.max(2, identifier.length() - 2));
        }
    }
}
