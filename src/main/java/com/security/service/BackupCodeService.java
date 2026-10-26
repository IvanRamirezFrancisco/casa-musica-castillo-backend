package com.security.service;

import com.security.entity.BackupCode;
import com.security.entity.User;
import com.security.repository.BackupCodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;


@Service
@Transactional
public class BackupCodeService {
    private static final Logger log = LoggerFactory.getLogger(BackupCodeService.class);

    @Autowired
    private BackupCodeRepository backupCodeRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final int BACKUP_CODE_COUNT = 10;
    private static final int CODE_LENGTH = 8;

    /**
     * MÉTODO PRINCIPAL: Genera 10 nuevos códigos de backup para el usuario
     * FLUJO: Eliminar códigos anteriores → Generar 10 nuevos → Hashear → Guardar en
     * BD → Activar backup codes
     */
    public List<String> generateBackupCodes(Long userId) {
        User user = userService.getUserById(userId);

        log.debug("Generando backup codes para usuario ID {}", userId);

        // Validar que el usuario tenga Google Auth activado
        if (user.getGoogleAuthEnabled() == null || !user.getGoogleAuthEnabled()) {
            throw new RuntimeException(
                    "Debes tener Google Authenticator activado primero para generar códigos de respaldo");
        }

        try {
            // PASO 1: Eliminar códigos de backup anteriores
            deleteExistingBackupCodes(userId);
            log.debug("Códigos de backup anteriores eliminados para usuario ID {}", userId);

            // PASO 2: Generar 10 códigos nuevos
            List<String> plainCodes = new ArrayList<>();
            List<BackupCode> backupCodes = new ArrayList<>();

            for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
                String plainCode = generateRandomCode();
                String hashedCode = hashCode(plainCode);

                BackupCode backupCode = new BackupCode(user, hashedCode);
                backupCodes.add(backupCode);
                // Return a user-facing formatted code (e.g. 1234-5678)
                plainCodes.add(formatCode(plainCode));
            }

            // PASO 3: Guardar en base de datos
            backupCodeRepository.saveAll(backupCodes);
            log.debug("{} backup codes guardados en BD para usuario ID {}", BACKUP_CODE_COUNT, userId);

            // PASO 4: Activar backup codes para el usuario
            user.setBackupCodesEnabled(true);
            userService.save(user);
            log.info("Backup codes generados y activados para usuario ID {}", userId);

            return plainCodes;

        } catch (Exception e) {
            log.error("Error generando backup codes para usuario ID {}: {}", userId, e.getClass().getSimpleName());
            throw new RuntimeException("Error generating backup codes: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Verifica un código de backup durante el login
     */
    public boolean verifyBackupCode(Long userId, String code) {
        try {
            log.debug("Verificando backup code para usuario ID {}", userId);

            User user = userService.getUserById(userId);

            if (user.getBackupCodesEnabled() == null || !user.getBackupCodesEnabled()) {
                log.warn("Backup codes no activos para usuario ID {}", userId);
                return false;
            }

            // Limpieza del formato del código (remover espacios y guiones)
            String cleanCode = cleanCode(code);

            // Buscar entre los códigos no usados del usuario y comparar usando
            // PasswordEncoder
            List<BackupCode> available = backupCodeRepository.findByUserIdAndUsedFalse(userId);
            for (BackupCode bc : available) {
                // Comparar el valor limpio con el hash almacenado
                if (passwordEncoder.matches(cleanCode, bc.getCodeHash())) {
                    // Marcar como usado
                    bc.markAsUsed();
                    backupCodeRepository.save(bc);

                    long remainingCodes = backupCodeRepository.countByUserIdAndUsedFalse(userId);
                    log.info("Backup code válido para usuario ID {}. Restantes: {}", userId, remainingCodes);

                    if (remainingCodes == 0) {
                        log.warn("Usuario ID {} ya no tiene backup codes disponibles", userId);
                    }

                    return true;
                }
            }

            log.warn("Backup code inválido o ya utilizado para usuario ID {}", userId);
            return false;

        } catch (Exception e) {
            log.error("Error verificando backup code para usuario ID {}: {}", userId, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Obtiene estadísticas de los códigos de backup del usuario
     */
    public Map<String, Object> getBackupCodeStats(Long userId) {
        User user = userService.getUserById(userId);
        Map<String, Object> stats = new HashMap<>();

        boolean isEnabled = user.getBackupCodesEnabled() != null && user.getBackupCodesEnabled();
        stats.put("enabled", isEnabled);

        if (isEnabled) {
            long totalCodes = backupCodeRepository.countByUserId(userId);
            long unusedCodes = backupCodeRepository.countByUserIdAndUsedFalse(userId);
            long usedCodes = totalCodes - unusedCodes;

            stats.put("total", totalCodes);
            stats.put("available", unusedCodes);
            stats.put("used", usedCodes);
            stats.put("hasActiveCodes", unusedCodes > 0);
        } else {
            stats.put("total", 0);
            stats.put("available", 0);
            stats.put("used", 0);
            stats.put("hasActiveCodes", false);
        }

        // Verificar si puede generar códigos (requiere Google Auth)
        stats.put("canGenerate", user.getGoogleAuthEnabled() != null && user.getGoogleAuthEnabled());

        return stats;
    }

    /**
     * Desactiva los códigos de backup para un usuario
     */
    public void disableBackupCodes(Long userId) {
        User user = userService.getUserById(userId);

        // Eliminar códigos existentes
        deleteExistingBackupCodes(userId);

        // Desactivar en el usuario
        user.setBackupCodesEnabled(false);
        userService.save(user);

        log.info("Backup codes desactivados para usuario ID {}", userId);
    }

    /**
     * Elimina todos los códigos de backup existentes de un usuario
     */
    private void deleteExistingBackupCodes(Long userId) {
        backupCodeRepository.deleteByUserId(userId);
    }

    /**
     * Genera un código aleatorio de 8 caracteres
     */
    private String generateRandomCode() {
        StringBuilder code = new StringBuilder();
        String chars = "0123456789"; // Usar sólo dígitos para códigos de 8 dígitos

        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(chars.charAt(secureRandom.nextInt(chars.length())));
        }

        return code.toString();
    }

    /**
     * Hashea un código usando BCrypt
     */
    private String hashCode(String code) {
        return passwordEncoder.encode(code);
    }

    /**
     * Formatea un código para mostrar al usuario (con guión en el medio)
     */
    private String formatCode(String code) {
        if (code.length() == CODE_LENGTH) {
            return code.substring(0, 4) + "-" + code.substring(4);
        }
        return code;
    }

    /**
     * Limpia el formato del código (remueve espacios y guiones)
     */
    private String cleanCode(String code) {
        return code.replaceAll("[\\s-]", "").toLowerCase();
    }

    /**
     * Enmascara un código para logging seguro
     */
    private String maskCode(String code) {
        if (code == null || code.length() < 3) {
            return "***";
        }
        return code.substring(0, 2) + "****" + code.substring(code.length() - 2);
    }

    /**
     * Verifica si un usuario tiene códigos de backup activos
     */
    public boolean hasActiveBackupCodes(Long userId) {
        return backupCodeRepository.hasActiveBackupCodes(userId);
    }

    /**
     * Obtiene el número de códigos disponibles
     */
    public long getAvailableBackupCodesCount(Long userId) {
        return backupCodeRepository.countByUserIdAndUsedFalse(userId);
    }
}