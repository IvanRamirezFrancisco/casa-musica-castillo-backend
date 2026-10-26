package com.security.service;

import com.security.entity.ActiveSession;
import com.security.entity.User;
import com.security.repository.ActiveSessionRepository;
import com.security.repository.UserRepository;
import com.security.util.LogSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Servicio para manejar sesiones activas y cumplir requisitos de rúbrica:
 * 1. Sesiones expiradas por inactividad (>15 min)
 * 2. Revocación de sesiones en múltiples dispositivos
 */
@Service
@Transactional
public class SessionManagementService {

    private static final Logger logger = LoggerFactory.getLogger(SessionManagementService.class);

    @Autowired
    private ActiveSessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    // Configuración desde application.yml
    @Value("${app.security.session.max-concurrent-sessions:3}")
    private int maxConcurrentSessions;

    @Value("${app.security.session.inactivity-timeout-minutes:15}")
    private int inactivityTimeoutMinutes;

    @jakarta.annotation.PostConstruct
    void validatePolicy() {
        if (maxConcurrentSessions < 1 || inactivityTimeoutMinutes < 1) {
            throw new IllegalStateException("La política de sesiones debe usar límites positivos");
        }
    }

    /**
     * REQUISITO: Crea nueva sesión con límite de 3 sesiones activas
     * Si count >= 3: Revoca el token más antiguo
     */
    public String createSession(String userEmail, LocalDateTime tokenExpiry, HttpServletRequest request) {
        // Buscar usuario
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado: " + userEmail));

        user = userRepository.findByIdForAuthUpdate(user.getId()).orElseThrow();
        String jti = UUID.randomUUID().toString();
        String ipAddress = getClientIpAddress(request);
        String userAgent = request.getHeader("User-Agent");

        // Contar tokens activos del usuario (no revocados y no expirados)
        List<ActiveSession> activeSessions = sessionRepository.findActiveSessionsByUser(user);
        int activeCount = activeSessions.size();

        logger.debug("Usuario {} tiene {} sesiones activas", user.getEmail(), activeCount);

        // Si count >= 3: Encontrar y revocar el token más antiguo
        while (activeCount >= maxConcurrentSessions) {
            Optional<ActiveSession> oldestSession = activeSessions.stream()
                    .min((s1, s2) -> s1.getCreatedAt().compareTo(s2.getCreatedAt()));

            if (oldestSession.isPresent()) {
                ActiveSession sessionToRevoke = oldestSession.get();
                sessionRepository.revokeSession(sessionToRevoke.getId());
                activeSessions.remove(sessionToRevoke);
                activeCount--;
                logger.info("Sesion mas antigua revocada por limite de sesiones concurrentes (JTI: {}...)",
                        sessionToRevoke.getJwtTokenId().substring(0, 8));
            }
        }

        // Crear nueva sesión
        ActiveSession newSession = new ActiveSession(user, jti, ipAddress, userAgent, tokenExpiry);
        sessionRepository.save(newSession);

        logger.info("Nueva sesion creada para {} (dispositivo: {})",
                user.getEmail(), extractDeviceInfo(userAgent));

        return jti;
    }

    /**
     * REQUISITO 1: Valida si una sesión está activa y no ha expirado por
     * inactividad
     */
    public boolean isSessionValid(String jti) {
        Optional<ActiveSession> sessionOpt = sessionRepository.findByJwtTokenId(jti);

        if (sessionOpt.isEmpty()) {
            logger.debug("Sesion no encontrada para JTI: {}...",
                    jti.length() > 8 ? jti.substring(0, 8) : jti);
            return false;
        }

        ActiveSession session = sessionOpt.get();

        if (session.getRevoked()) {
            logger.debug("Sesion revocada para JTI: {}...",
                    jti.length() > 8 ? jti.substring(0, 8) : jti);
            return false;
        }

        // Verificar expiración por inactividad (>15 min)
        if (session.isInactive(inactivityTimeoutMinutes)) {
            // Sesión expirada por inactividad
            sessionRepository.revokeSession(session.getId());
            logger.info("Sesion expirada por inactividad (>{} min) para JTI: {}...",
                    inactivityTimeoutMinutes, jti.length() > 8 ? jti.substring(0, 8) : jti);
            return false;
        }

        // Verificar expiración normal del token
        if (session.isExpired()) {
            sessionRepository.revokeSession(session.getId());
            logger.debug("Sesion expirada normalmente para JTI: {}...",
                    jti.length() > 8 ? jti.substring(0, 8) : jti);
            return false;
        }

        return true;
    }

    /**
     * REQUISITO 1: Actualiza actividad de sesión (resetea contador de inactividad)
     */
    public void updateSessionActivity(String jti) {
        LocalDateTime now = LocalDateTime.now();
        sessionRepository.updateLastActivity(jti, now);
        // Solo para debug en desarrollo
        // System.out.println("🔄 Actividad actualizada para sesión: " + jti + " a " +
        // now);
    }

    /**
     * REQUISITO 2: Cierra sesión específica (logout en un dispositivo)
     */
    public void invalidateSession(String jti) {
        sessionRepository.revokeByTokenId(jti);
        // LogSanitizer.maskToken() sanitiza y muestra solo los 8 primeros chars del JTI
        // (CWE-117)
        logger.info("Sesion cerrada manualmente para JTI: {}", LogSanitizer.maskToken(jti));
    }

    /**
     * REQUISITO 2: Cierra todas las sesiones de un usuario (logout from all
     * devices)
     */
    public void invalidateAllUserSessions(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado: " + userEmail));

        List<ActiveSession> userSessions = sessionRepository.findValidSessionsByUser(user, LocalDateTime.now());

        // Revocar todas en la base de datos
        sessionRepository.revokeAllUserSessions(user);

        logger.info("Todas las sesiones cerradas para {} ({} sesiones)",
                userEmail, userSessions.size());
    }

    /**
     * Obtiene información de sesiones activas para un usuario
     */
    public List<ActiveSession> getUserActiveSessions(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado: " + userEmail));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime inactivityThreshold = now.minusMinutes(inactivityTimeoutMinutes);

        return sessionRepository.findValidAndActiveSessionsByUser(user, now, inactivityThreshold);
    }

    /**
     * Cuenta sesiones activas para un usuario
     */
    public long getActiveSessionCount(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado: " + userEmail));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime inactivityThreshold = now.minusMinutes(inactivityTimeoutMinutes);

        return sessionRepository.countActiveAndNotInactiveSessionsByUserId(
                user.getId(), now, inactivityThreshold);
    }

    /**
     * SCHEDULED TASK: Limpia sesiones expiradas automáticamente cada 5 minutos
     * REQUISITO 1: Implementa la expiración automática por inactividad
     */
    @Scheduled(fixedRate = 300000) // 5 minutos
    public void cleanupExpiredSessions() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime inactivityThreshold = now.minusMinutes(inactivityTimeoutMinutes);

        List<ActiveSession> expiredSessions = sessionRepository.findExpiredOrInactiveSessions(now, inactivityThreshold);

        for (ActiveSession session : expiredSessions) {
            sessionRepository.revokeSession(session.getId());
        }

        if (!expiredSessions.isEmpty()) {
            logger.info("Limpieza automatica: {} sesiones expiradas eliminadas",
                    expiredSessions.size());
        }

        // Limpieza de registros muy antiguos (más de 7 días)
        LocalDateTime cutoffDate = now.minusDays(7);
        sessionRepository.deleteOldRevokedSessions(cutoffDate);
    }

    /**
     * Obtiene información detallada de una sesión para debugging/auditoría
     */
    public Optional<ActiveSession> getSessionInfo(String jti) {
        return sessionRepository.findByJwtTokenId(jti);
    }

    public int getMaxConcurrentSessions() { return maxConcurrentSessions; }

    public boolean belongsToUser(String jti, Long userId) {
        return sessionRepository.findByJwtTokenId(jti)
                .map(session -> session.getUser().getId().equals(userId)).orElse(false);
    }

    public boolean revokeOwnedSession(String jti, String email) {
        var session = sessionRepository.findByJwtTokenId(jti);
        if (session.isEmpty() || !session.get().getUser().getEmail().equals(email)) return false;
        sessionRepository.revokeByTokenId(jti);
        return true;
    }

    // Utilidades privadas
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

    private String extractDeviceInfo(String userAgent) {
        if (userAgent == null)
            return "Desconocido";

        if (userAgent.contains("Mobile") || userAgent.contains("Android") || userAgent.contains("iPhone")) {
            return "Móvil";
        } else if (userAgent.contains("Tablet") || userAgent.contains("iPad")) {
            return "Tablet";
        } else {
            return "Escritorio";
        }
    }
}
