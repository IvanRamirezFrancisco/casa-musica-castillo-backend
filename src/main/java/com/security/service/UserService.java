package com.security.service;

import com.security.dto.request.RegisterRequest;
import com.security.dto.response.UserResponse;
import com.security.entity.Role;
import com.security.entity.User;
import com.security.entity.VerificationToken;
import com.security.enums.TokenType;
import com.security.exception.AuthFlowException;
import com.security.exception.ResourceNotFoundException;
import com.security.exception.BadRequestException;
import com.security.repository.RoleRepository;
import com.security.repository.UserRepository;
import com.security.repository.VerificationTokenRepository;
import com.security.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class UserService {
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AdminHierarchyService adminHierarchyService;

    private TransactionTemplate requiresNewTx;

    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        if (transactionManager != null) {
            this.requiresNewTx = new TransactionTemplate(transactionManager);
            this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    private record PreparedRegistration(User savedUser, User emailRecipient, String verificationToken) {}

    /**
     * Patrón UPSERT anti-enumeración para registro de usuarios.
     *
     * Tres ramas:
     * 1. Email NO existe → creación normal (enabled=false, enviar verificación).
     * 2. Email existe + enabled=false (ghost) → actualizar datos, regenerar token,
     * reenviar email.
     * 3. Email existe + enabled=true (activo) → retorno silencioso (sin email, sin
     * error).
     *
     * La persistencia ocurre en una transacción corta antes de invocar el proveedor de correo externo,
     * evitando mantener conexiones/transacciones abiertas durante llamadas de red y asegurando que
     * la cuenta pendiente de verificación pueda recuperar su correo mediante reenvío si el proveedor falla.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public User createUser(RegisterRequest registerRequest) {
        PreparedRegistration prepared = requiresNewTx != null
                ? requiresNewTx.execute(status -> prepareRegistrationInTx(registerRequest))
                : prepareRegistrationInTx(registerRequest);

        if (prepared == null) {
            throw new IllegalStateException("No se pudo preparar el registro de usuario");
        }

        if (prepared.verificationToken() != null && prepared.emailRecipient() != null) {
            try {
                emailService.sendVerificationEmail(prepared.emailRecipient(), prepared.verificationToken());
                logger.info("Email de verificación enviado a: {}",
                        LogSanitizer.maskEmail(prepared.emailRecipient().getEmail()));
            } catch (Exception e) {
                logger.error("Cuenta registrada pendiente de verificación pero falló el envío de correo para {}: {}",
                        LogSanitizer.maskEmail(prepared.emailRecipient().getEmail()), e.getClass().getSimpleName());
                throw new AuthFlowException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Tu cuenta fue registrada como pendiente de verificación, pero no se pudo enviar el correo de confirmación en este momento. Puedes solicitar el reenvío de verificación.");
            }
        }

        return prepared.savedUser();
    }

    protected PreparedRegistration prepareRegistrationInTx(RegisterRequest registerRequest) {
        String normalizedEmail = registerRequest.getEmail() != null
                ? registerRequest.getEmail().trim().toLowerCase(Locale.ROOT)
                : "";
        String normalizedUsername = registerRequest.getUsername() != null
                ? registerRequest.getUsername().trim()
                : "";
        String normalizedFirstName = registerRequest.getFirstName() != null
                ? registerRequest.getFirstName().trim()
                : "";
        String normalizedLastName = registerRequest.getLastName() != null
                ? registerRequest.getLastName().trim()
                : "";
        String normalizedPhone = (registerRequest.getPhone() != null && !registerRequest.getPhone().trim().isEmpty())
                ? registerRequest.getPhone().trim()
                : null;

        Optional<User> existingOpt = userRepository.findByEmail(normalizedEmail);

        if (existingOpt.isPresent()) {
            User existing = existingOpt.get();

            if (Boolean.TRUE.equals(existing.getEnabled())) {
                // ── Rama 3: usuario activo → retorno silencioso ──
                logger.info("Registro silencioso (usuario activo): {}", LogSanitizer.maskEmail(existing.getEmail()));
                initializeRelations(existing);
                return new PreparedRegistration(existing, null, null);
            }

            // ── Rama 2: ghost user (enabled=false) → actualizar datos + regenerar token ──
            logger.info("Registro upsert (ghost user): {}", LogSanitizer.maskEmail(existing.getEmail()));

            Optional<User> usernameOwner = userRepository.findByUsername(normalizedUsername);
            if (usernameOwner.isPresent() && !usernameOwner.get().getId().equals(existing.getId())) {
                logger.info("Conflicto de username en upsert ghost user: {}", LogSanitizer.maskEmail(normalizedEmail));
                initializeRelations(existing);
                return new PreparedRegistration(existing, null, null);
            }

            existing.setUsername(normalizedUsername);
            existing.setFirstName(normalizedFirstName);
            existing.setLastName(normalizedLastName);
            existing.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
            existing.setPhone(normalizedPhone);
            existing.setUpdatedAt(LocalDateTime.now());

            User savedUser;
            try {
                savedUser = userRepository.save(existing);
            } catch (DataIntegrityViolationException ex) {
                logger.warn("Race condition en upsert: {}", LogSanitizer.maskEmail(normalizedEmail));
                initializeRelations(existing);
                return new PreparedRegistration(existing, null, null);
            }

            verificationTokenRepository.deleteByUser(savedUser);
            String tokenValue = persistVerificationToken(savedUser);
            initializeRelations(savedUser);
            return new PreparedRegistration(savedUser, buildEmailRecipientSnapshot(savedUser), tokenValue);
        }

        // ── Rama 1: usuario nuevo → creación normal ──
        if (userRepository.existsByUsername(normalizedUsername)) {
            logger.info("Registro silencioso (username existente): {}", LogSanitizer.maskEmail(normalizedEmail));
            User dummy = buildDummyUser(normalizedEmail, normalizedFirstName, normalizedLastName);
            return new PreparedRegistration(dummy, null, null);
        }

        User user = new User();
        user.setUsername(normalizedUsername);
        user.setFirstName(normalizedFirstName);
        user.setLastName(normalizedLastName);
        user.setEmail(normalizedEmail);
        user.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        user.setPhone(normalizedPhone);
        user.setEnabled(false); // Se habilitará únicamente tras verificar el email
        user.setTwoFactorEnabled(false);
        user.setAccountNonExpired(true);
        user.setAccountNonLocked(true);
        user.setCredentialsNonExpired(true);
        user.setIsCustomer(true);

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new RuntimeException("User Role not set."));

        user.setRoles(Collections.singleton(userRole));

        User savedUser;
        try {
            savedUser = userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            logger.warn("Race condition detectada en registro: {}", LogSanitizer.maskEmail(normalizedEmail));
            User dummy = buildDummyUser(normalizedEmail, normalizedFirstName, normalizedLastName);
            return new PreparedRegistration(dummy, null, null);
        }

        String tokenValue = persistVerificationToken(savedUser);
        initializeRelations(savedUser);
        return new PreparedRegistration(savedUser, buildEmailRecipientSnapshot(savedUser), tokenValue);
    }

    private User buildDummyUser(String email, String firstName, String lastName) {
        User dummy = new User();
        dummy.setId(0L);
        dummy.setEmail(email);
        dummy.setFirstName(firstName);
        dummy.setLastName(lastName);
        dummy.setEnabled(false);
        dummy.setIsCustomer(true);
        dummy.setRoles(Collections.emptySet());
        return dummy;
    }

    private User buildEmailRecipientSnapshot(User user) {
        User snapshot = new User();
        snapshot.setId(user.getId());
        snapshot.setEmail(user.getEmail());
        snapshot.setFirstName(user.getFirstName());
        snapshot.setLastName(user.getLastName());
        return snapshot;
    }

    private void initializeRelations(User user) {
        if (user.getRoles() != null) {
            user.getRoles().forEach(role -> {
                if (role.getPermissions() != null) {
                    role.getPermissions().size();
                }
            });
        }
    }

    private String persistVerificationToken(User user) {
        String tokenValue = generateVerificationToken();
        VerificationToken verificationToken = new VerificationToken();
        verificationToken.setToken(tokenValue);
        verificationToken.setUser(user);
        verificationToken.setTokenType(TokenType.EMAIL_VERIFICATION);
        verificationToken.setExpiryDate(LocalDateTime.now().plusHours(24));
        verificationToken.setUsed(false);
        verificationTokenRepository.save(verificationToken);
        return tokenValue;
    }

    public boolean verifyEmailToken(String token) {
        logger.debug("Verificando token de email");

        if (token == null || token.trim().isEmpty()) {
            throw new BadRequestException("Token de verificación inválido o expirado");
        }

        String cleanToken = token.trim();

        Optional<VerificationToken> verificationTokenOpt = verificationTokenRepository
                .findValidToken(cleanToken, LocalDateTime.now());

        if (verificationTokenOpt.isEmpty()) {
            logger.warn("Token de verificacion invalido o expirado");
            throw new BadRequestException("Token de verificación inválido o expirado");
        }

        VerificationToken verificationToken = verificationTokenOpt.get();
        User user = verificationToken.getUser();

        // Activar usuario
        user.setEnabled(true);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        // Marcar token como usado (single-use)
        verificationToken.setUsed(true);
        verificationTokenRepository.save(verificationToken);
        verificationTokenRepository.markTokenAsUsed(verificationToken.getId());

        logger.info("Verificacion completada para: {}", LogSanitizer.maskEmail(user.getEmail()));

        return true;
    }

    public void resendVerificationEmail(String email) {
        String normalizedEmail = email != null ? email.trim().toLowerCase(Locale.ROOT) : "";
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", normalizedEmail));

        if (Boolean.TRUE.equals(user.getEnabled())) {
            throw new BadRequestException("El usuario ya está verificado");
        }

        verificationTokenRepository.deleteByUser(user);
        String tokenValue = persistVerificationToken(user);

        try {
            emailService.sendVerificationEmail(buildEmailRecipientSnapshot(user), tokenValue);
            logger.info("Email de verificacion reenviado a: {}",
                    LogSanitizer.maskEmail(user.getEmail()));
        } catch (Exception e) {
            logger.error("Error reenviando email a {}: {}",
                    LogSanitizer.maskEmail(user.getEmail()), e.getClass().getSimpleName());
            throw new AuthFlowException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo enviar el correo de verificación en este momento. Intenta nuevamente más tarde.");
        }
    }

    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    /** Alfabeto alfanumérico para generación de tokens de verificación (32 chars = ~190 bits de entropía) */
    private static final String ALPHANUM_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private String generateVerificationToken() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(ALPHANUM_CHARS.charAt(TOKEN_RANDOM.nextInt(ALPHANUM_CHARS.length())));
        }
        return sb.toString();
    }

    ///////////////////////

    public User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }

    public User getUserByEmail(String email) {
        String normalized = email != null ? email.trim() : "";
        return userRepository.findByEmail(normalized)
                .orElseGet(() -> userRepository.findByEmail(normalized.toLowerCase(Locale.ROOT))
                        .orElseThrow(() -> new ResourceNotFoundException("User", "email", normalized)));
    }

    public UserResponse getUserResponseById(Long userId) {
        User user = getUserById(userId);
        return convertToUserResponse(user);
    }

    public Page<UserResponse> getAllUsers(Pageable pageable) {
        Page<User> users = userRepository.findAll(pageable);
        return users.map(this::convertToUserResponse);
    }

    public User updateUser(Long userId, User updatedUser) {
        User user = getUserById(userId);
        assertCanActOn(user, "UPDATE");

        if (updatedUser.getFirstName() != null) {
            user.setFirstName(updatedUser.getFirstName());
        }
        if (updatedUser.getLastName() != null) {
            user.setLastName(updatedUser.getLastName());
        }
        if (updatedUser.getPhone() != null) {
            user.setPhone(updatedUser.getPhone());
        }

        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    public void deleteUser(Long userId) {
        User user = getUserById(userId);
        assertCanActOn(user, "DELETE");
        userRepository.delete(user);
    }

    public void enableUser(Long userId) {
        User user = getUserById(userId);
        assertCanActOn(user, "ENABLE");
        user.setEnabled(true);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        try {
            auditLogService.log("UPDATE", "USER_ENABLE", "USER", userId,
                    null, null, "INFO", true);
        } catch (Exception auditEx) {
            logger.warn("⚠️ No se pudo registrar audit log para habilitación de usuario {}: {}",
                    userId, auditEx.getMessage());
        }
    }

    public void disableUser(Long userId) {
        User user = getUserById(userId);
        assertCanActOn(user, "DISABLE");
        user.setEnabled(false);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        try {
            auditLogService.log("UPDATE", "USER_DISABLE", "USER", userId,
                    null, null, "WARNING", true);
        } catch (Exception auditEx) {
            logger.warn("⚠️ No se pudo registrar audit log para deshabilitación de usuario {}: {}",
                    userId, auditEx.getMessage());
        }
    }

    public void changePassword(Long userId, String newPassword) {
        User user = getUserById(userId);
        assertCanActOn(user, "PASSWORD");
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setCredentialsNonExpired(true);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        try {
            auditLogService.log("UPDATE", "USER_PASSWORD_CHANGE", "USER", userId,
                    null, null, "WARNING", true);
        } catch (Exception auditEx) {
            logger.warn("⚠️ No se pudo registrar audit log para cambio de contraseña de usuario {}: {}",
                    userId, auditEx.getMessage());
        }
    }

    public void enableTwoFactor(Long userId, String secret) {
        User user = getUserById(userId);
        assertCanActOn(user, "UPDATE");
        user.setTwoFactorEnabled(true);
        user.setTwoFactorSecret(secret);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    public void disableTwoFactor(Long userId) {
        User user = getUserById(userId);
        assertCanActOn(user, "RESET_2FA");
        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    public Optional<User> findByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        String normalized = email.trim();
        Optional<User> direct = userRepository.findByEmail(normalized);
        if (direct.isPresent()) {
            return direct;
        }
        return userRepository.findByEmail(normalized.toLowerCase(Locale.ROOT));
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public User save(User user) {
        return userRepository.save(user);
    }

    public UserResponse convertToUserResponse(User user) {
        UserResponse userResponse = new UserResponse();
        userResponse.setId(user.getId());
        userResponse.setProtectedOwner(adminHierarchyService.isProtectedOwner(user));
        userResponse.setFirstName(user.getFirstName());
        userResponse.setLastName(user.getLastName());
        userResponse.setEmail(user.getEmail());
        userResponse.setPhone(user.getPhone());
        userResponse.setEnabled(user.getEnabled());
        userResponse.setGoogleAuthEnabled(user.getGoogleAuthEnabled());
        userResponse.setEmailEnabled(user.getEmailEnabled());
        userResponse.setBackupCodesEnabled(user.getBackupCodesEnabled());
        userResponse.setTwoFactorEnabled(user.getTwoFactorEnabled());
        userResponse.setCreatedAt(user.getCreatedAt());
        userResponse.setUpdatedAt(user.getUpdatedAt());

        Set<String> roleNames = user.getRoles() != null
                ? user.getRoles().stream().map(Role::getName).collect(Collectors.toSet())
                : Collections.emptySet();
        userResponse.setRoles(roleNames);

        Set<String> permissionNames = user.getRoles() != null
                ? user.getRoles().stream()
                        .filter(role -> role.getPermissions() != null)
                        .flatMap(role -> role.getPermissions().stream())
                        .map(permission -> permission.getName())
                        .collect(Collectors.toSet())
                : Collections.emptySet();
        userResponse.setPermissions(permissionNames);

        userResponse.setIsCustomer(user.getIsCustomer());

        return userResponse;
    }

    private void assertCanActOn(User targetUser, String action) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || "anonymousUser".equals(auth.getName()) || "SYSTEM".equals(auth.getName())) {
            return;
        }

        String email = auth.getName();
        User actor = userRepository.findByEmail(email).orElse(null);
        if (actor != null) {
            if ("DELETE".equals(action)) {
                adminHierarchyService.assertCanDeleteUser(actor, targetUser);
            } else if ("DISABLE".equals(action)) {
                adminHierarchyService.assertCanDisableUser(actor, targetUser);
            } else if ("RESET_2FA".equals(action)) {
                adminHierarchyService.assertCanResetTwoFactor(actor, targetUser);
            } else if ("PASSWORD".equals(action)) {
                adminHierarchyService.assertCanChangePasswordAdmin(actor, targetUser);
            } else {
                adminHierarchyService.assertCanManageUser(actor, targetUser);
            }
        }
    }

    public long getTotalUsersCount() {
        return userRepository.count();
    }

    public long getActiveUsersCount() {
        return userRepository.countByEnabledTrue();
    }

    public long getVerifiedUsersCount() {
        return userRepository.countByEnabledTrue();
    }

    public long getUsersWithMFACount() {
        return userRepository.countByTwoFactorEnabledTrue();
    }

    public Page<User> getAllUsersPaginated(Pageable pageable) {
        return userRepository.findAll(pageable);
    }
}
