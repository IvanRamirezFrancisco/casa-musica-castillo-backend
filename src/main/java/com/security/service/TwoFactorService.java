package com.security.service;

import com.security.entity.User;
import com.security.enums.TwoFactorType;
import com.security.repository.UserRepository;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TwoFactorService {
    private static final Logger log = LoggerFactory.getLogger(TwoFactorService.class);

    @Autowired
    private UserService userService;

    @Autowired
    private TotpService totpService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private BackupCodeService backupCodeService;

    // Cache temporal para códigos de email (en producción usar Redis)
    private final Map<Long, String> emailCodes = new HashMap<>();
    private final Map<Long, LocalDateTime> emailCodeExpiry = new HashMap<>();

    private static final SecureRandom secureRandom = new SecureRandom();

    // ===== GOOGLE AUTHENTICATOR (TOTP) =====

    /**
     * Setup completo de Google Authenticator.
     * Usa repositorio directo para evitar conflictos JPA/transaccional.
     */
    @Transactional
    public Map<String, Object> setupGoogleAuthenticatorComplete(Long userId) {
        log.debug("Iniciando setup Google Authenticator para usuario ID {}", userId);

        try {
            User user = userRepository.findByIdForAuthUpdate(userId)
                    .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

            if (user.getGoogleAuthEnabled() != null && user.getGoogleAuthEnabled()) {
                throw new RuntimeException("Google Authenticator ya está habilitado para este usuario");
            }

            String secret = totpService.generateSecretKey();
            String qrCodeBase64 = totpService.generateQRCodeBase64(secret, user.getEmail());

            try {
                user.setGoogleAuthSecret(secret);
                user.setTwoFactorType(TwoFactorType.GOOGLE_AUTHENTICATOR);
                user.setGoogleAuthEnabled(false); // Se activa después de confirmación
                userRepository.save(user);
                log.debug("Secret de Google Authenticator guardado en BD para usuario ID {}", userId);
            } catch (Exception saveException) {
                log.error("Error guardando configuración Google Authenticator para usuario ID {}: {}",
                        userId, saveException.getClass().getSimpleName());
                throw new RuntimeException("Error guardando configuración: " + saveException.getClass().getSimpleName());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("secret", secret);
            result.put("manualEntryKey", secret);
            result.put("qrCode", "data:image/png;base64," + qrCodeBase64);
            result.put("issuer", "AuthSystem");
            result.put("accountName", user.getEmail());
            result.put("instructions",
                    "1. Escanea el QR con Google Authenticator\n2. O ingresa la clave manual\n3. Confirma con un código de 6 dígitos");
            result.put("nextStep", "Usa POST /api/two-factor/google/confirm con el código generado");
            result.put("setupTime", System.currentTimeMillis());

            log.info("Setup Google Authenticator completado para usuario ID {}", userId);
            return result;

        } catch (Exception e) {
            log.error("Error en setup Google Authenticator para usuario ID {}: {}", userId, e.getClass().getSimpleName());
            throw new RuntimeException("Error configurando Google Authenticator: " + e.getClass().getSimpleName());
        }
    }

    @Transactional
    public Map<String, String> getPendingGoogleEnrollment(Long userId) {
        User user = pendingEnrollment(userId);
        Map<String, String> data = pendingManualEntry(user);
        try {
            data.put("qrCode", "data:image/png;base64," + totpService.generateQRCodeBase64(user.getGoogleAuthSecret(), user.getEmail()));
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar el QR de enrollment");
        }
        return data;
    }

    @Transactional
    public Map<String, String> getPendingGoogleManualEntry(Long userId) {
        return pendingManualEntry(pendingEnrollment(userId));
    }

    private User pendingEnrollment(Long userId) {
        User user = userRepository.findByIdForAuthUpdate(userId).orElseThrow();
        if (Boolean.TRUE.equals(user.getGoogleAuthEnabled())) {
            throw new com.security.exception.AuthFlowException(org.springframework.http.HttpStatus.FORBIDDEN);
        }
        if (user.getGoogleAuthSecret() == null || user.getGoogleAuthSecret().isBlank()) {
            throw new com.security.exception.AuthFlowException(org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        return user;
    }

    private Map<String, String> pendingManualEntry(User user) {
        Map<String, String> data = new HashMap<>();
        data.put("manualEntryKey", user.getGoogleAuthSecret());
        data.put("issuer", "AuthSystem"); data.put("accountName", user.getEmail());
        return data;
    }

    /**
     * Verifica código y activa Google Authenticator.
     * Usa repositorio directo para evitar conflictos JPA.
     */
    @Transactional
    public boolean confirmGoogleAuthenticator(Long userId, String code) {
        try {
            User user = userRepository.findByIdForAuthUpdate(userId)
                    .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

            if (user.getGoogleAuthSecret() == null || user.getGoogleAuthSecret().isEmpty()) {
                throw new RuntimeException("Google Authenticator no configurado. Ejecuta /setup primero.");
            }

            log.debug("Confirmando Google Authenticator para usuario ID {}", userId);
            boolean isValid = totpService.verifyCode(user.getGoogleAuthSecret(), code);

            if (isValid) {
                try {
                    user.setTwoFactorEnabled(true);
                    user.setGoogleAuthEnabled(true);
                    userRepository.save(user);
                    log.info("Google Authenticator activado para usuario ID {}", userId);
                    return true;
                } catch (Exception saveError) {
                    log.error("Error guardando activación de Google Auth para usuario ID {}: {}",
                            userId, saveError.getClass().getSimpleName());
                    throw new RuntimeException("Error activando Google Authenticator: " + saveError.getClass().getSimpleName());
                }
            } else {
                log.warn("Código Google Authenticator inválido para usuario ID {}", userId);
                return false;
            }

        } catch (Exception e) {
            log.error("Error confirmando Google Authenticator para usuario ID {}: {}", userId, e.getClass().getSimpleName());
            throw new RuntimeException("Error confirmando Google Authenticator: " + e.getClass().getSimpleName());
        }
    }

    /** Match only: the challenge transaction enforces and persists anti-replay before issuing JWT. */
    public java.util.OptionalLong findGoogleAuthenticatorLoginSlot(Long userId, String code) {
        User user = userService.getUserById(userId);
        if (!Boolean.TRUE.equals(user.getGoogleAuthEnabled()) || user.getGoogleAuthSecret() == null) {
            return java.util.OptionalLong.empty();
        }
        return totpService.findMatchingSlot(user.getGoogleAuthSecret(), code);
    }

    // ===== EMAIL 2FA =====

    public void sendEmailCode(Long userId) {
        User user = userService.getUserById(userId);

        // Genera código de 6 dígitos
        String code = String.format("%06d", secureRandom.nextInt(1000000));

        // Almacena con expiración de 5 minutos
        emailCodes.put(userId, code);
        emailCodeExpiry.put(userId, LocalDateTime.now().plusMinutes(5));

        emailService.send2FACodeEmail(user, code);
    }

    public boolean verifyEmailCode(Long userId, String code) {
        String storedCode = emailCodes.get(userId);
        LocalDateTime expiry = emailCodeExpiry.get(userId);

        if (storedCode == null || expiry == null) {
            return false;
        }

        if (LocalDateTime.now().isAfter(expiry)) {
            emailCodes.remove(userId);
            emailCodeExpiry.remove(userId);
            return false;
        }

        boolean isValid = storedCode.equals(code);

        if (isValid) {
            emailCodes.remove(userId);
            emailCodeExpiry.remove(userId);
        }

        return isValid;
    }

    public boolean verifyToken(Long userId, String token) {
        User user = userService.getUserById(userId);

        if (!user.getTwoFactorEnabled()) {
            return false;
        }

        if (user.getTwoFactorType() == null) {
            return false;
        }

        switch (user.getTwoFactorType()) {
            case GOOGLE_AUTHENTICATOR:
                return totpService.verifyCode(user.getGoogleAuthSecret(), token);
            case EMAIL:
                return verifyEmailCode(userId, token);
            default:
                return false;
        }
    }

    public void disableTwoFactor(Long userId) {
        User user = userService.getUserById(userId);
        user.setTwoFactorEnabled(false);
        user.setGoogleAuthSecret(null);
        user.setGoogleAuthEnabled(false);
        user.setEmailEnabled(false);
        user.setTwoFactorType(null);
        userService.save(user);

        emailCodes.remove(userId);
        emailCodeExpiry.remove(userId);
    }

    // ===== MÉTODOS LEGACY (para compatibilidad) =====

    public String generateSecret() {
        return totpService.generateSecretKey();
    }

    public String generateToken(Long userId) {
        String token = String.format("%06d", secureRandom.nextInt(1000000));
        emailCodes.put(userId, token);
        emailCodeExpiry.put(userId, LocalDateTime.now().plusMinutes(5));
        return token;
    }

    public void enableTwoFactor(Long userId) {
        String secret = generateSecret();
        User user = userService.getUserById(userId);
        user.setGoogleAuthSecret(secret);
        user.setTwoFactorEnabled(true);
        user.setTwoFactorType(TwoFactorType.GOOGLE_AUTHENTICATOR);
        userService.save(user);
    }

    public void enableEmailTwoFactor(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setEmailEnabled(true);
        user.setTwoFactorEnabled(true);
        user.setTwoFactorType(TwoFactorType.EMAIL);
        userRepository.save(user);

        log.info("Email 2FA habilitado para usuario ID {}", userId);
    }

    public void cleanupExpiredTokens() {
        LocalDateTime now = LocalDateTime.now();
        emailCodes.entrySet().removeIf(entry -> {
            Long userId = entry.getKey();
            LocalDateTime expiry = emailCodeExpiry.get(userId);
            return expiry == null || now.isAfter(expiry);
        });
        emailCodeExpiry.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
    }

    public void cleanupOldUsedTokens() {
        cleanupExpiredTokens();
    }

    // ===== MÉTODOS ESPECÍFICOS PARA MÚLTIPLES 2FA =====

    /**
     * Desactivar método específico de 2FA
     */
    public void disableSpecificTwoFactor(Long userId, String method) {
        User user = userService.getUserById(userId);
        boolean wasDisabled = false;

        switch (method.toUpperCase()) {
            case "GOOGLE":
            case "GOOGLE_AUTHENTICATOR":
                if (user.getGoogleAuthEnabled() != null && user.getGoogleAuthEnabled()) {
                    user.setGoogleAuthEnabled(false);
                    user.setGoogleAuthSecret(null);
                    wasDisabled = true;
                }
                break;
            case "EMAIL":
                if (user.getEmailEnabled() != null && user.getEmailEnabled()) {
                    user.setEmailEnabled(false);
                    wasDisabled = true;
                    emailCodes.remove(userId);
                    emailCodeExpiry.remove(userId);
                }
                break;
            default:
                throw new RuntimeException("Invalid 2FA method: " + method);
        }

        if (!wasDisabled) {
            throw new RuntimeException(method + " two-factor authentication is not enabled");
        }

        if (!hasAnyTwoFactorEnabled(user)) {
            user.setTwoFactorEnabled(false);
            user.setTwoFactorType(null);
        }

        userService.save(user);
    }

    /**
     * Verificar si el usuario tiene algún método 2FA activo
     */
    private boolean hasAnyTwoFactorEnabled(User user) {
        return (user.getGoogleAuthEnabled() != null && user.getGoogleAuthEnabled()) ||
                (user.getEmailEnabled() != null && user.getEmailEnabled());
    }

    /**
     * Obtener métodos 2FA disponibles para el usuario
     */
    public Map<String, Boolean> getAvailableTwoFactorMethods(Long userId) {
        User user = userService.getUserById(userId);
        Map<String, Boolean> methods = new HashMap<>();

        methods.put("GOOGLE_AUTHENTICATOR", user.getGoogleAuthEnabled() != null && user.getGoogleAuthEnabled());
        methods.put("EMAIL", user.getEmailEnabled() != null && user.getEmailEnabled());
        methods.put("BACKUP_CODES", user.getBackupCodesEnabled() != null && user.getBackupCodesEnabled());

        return methods;
    }

    // ===== BACKUP CODES =====

    public boolean verifyBackupCode(Long userId, String code) {
        return backupCodeService.verifyBackupCode(userId, code);
    }

    public List<String> generateBackupCodes(Long userId) {
        return backupCodeService.generateBackupCodes(userId);
    }

    public Map<String, Object> getBackupCodeStats(Long userId) {
        return backupCodeService.getBackupCodeStats(userId);
    }

}
