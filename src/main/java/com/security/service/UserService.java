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
import java.util.Map;
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

    @Autowired
    private SessionManagementService sessionManagementService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private TransactionTemplate requiresNewTx;

    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        if (transactionManager != null) {
            this.requiresNewTx = new TransactionTemplate(transactionManager);
            this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    private record PreparedRegistration(User emailRecipient, String verificationToken) {}

    /**
     * Creates a pending account in a short transaction before sending verification email.
     * Repeated anonymous requests never replace an account's data or credentials.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void createUser(RegisterRequest registerRequest) {
        String email = registerRequest.getEmail().trim().toLowerCase(Locale.ROOT);
        String username = registerRequest.getUsername().trim();
        PreparedRegistration prepared;
        try {
            prepared = requiresNewTx.execute(status -> prepareRegistrationInTx(registerRequest, email, username));
        } catch (DataIntegrityViolationException ex) {
            // Check after rollback so a simultaneous registration returns the same neutral response.
            if (userRepository.existsByEmail(email) || userRepository.existsByUsername(username)) return;
            throw ex;
        }
        if (prepared == null) return;

        try {
            emailService.sendVerificationEmail(prepared.emailRecipient(), prepared.verificationToken());
            logger.info("Correo de verificación aceptado para {}", LogSanitizer.maskEmail(email));
        } catch (Exception e) {
            logger.error("Fallo de envío de verificación para {}: {}", LogSanitizer.maskEmail(email),
                    e.getClass().getSimpleName());
            throw new AuthFlowException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo enviar el correo de verificación. Puedes solicitar el reenvío.");
        }
    }

    private PreparedRegistration prepareRegistrationInTx(RegisterRequest request, String email, String username) {
        if (userRepository.existsByEmail(email) || userRepository.existsByUsername(username)) return null;

        User user = new User();
        user.setUsername(username);
        user.setFirstName(request.getFirstName().trim());
        user.setLastName(request.getLastName().trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setPhone(request.getPhone() == null || request.getPhone().isBlank() ? null : request.getPhone().trim());
        user.setEnabled(false);
        user.setTwoFactorEnabled(false);
        user.setAccountNonExpired(true);
        user.setAccountNonLocked(true);
        user.setCredentialsNonExpired(true);
        user.setIsCustomer(true);

        Role role = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("User Role not set."));
        user.setRoles(Collections.singleton(role));

        User savedUser = userRepository.saveAndFlush(user);
        String token = persistVerificationToken(savedUser);
        return new PreparedRegistration(buildEmailRecipientSnapshot(savedUser), token);
    }

    private User buildEmailRecipientSnapshot(User user) {
        User snapshot = new User();
        snapshot.setId(user.getId());
        snapshot.setEmail(user.getEmail());
        snapshot.setFirstName(user.getFirstName());
        snapshot.setLastName(user.getLastName());
        return snapshot;
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

    private User lockAccount(Long userId) {
        User user = userRepository.findByIdForAuthUpdate(userId)
                .orElseThrow(() -> new AuthFlowException(HttpStatus.UNAUTHORIZED));
        entityManager.refresh(user);
        return user;
    }

    @Transactional(noRollbackFor = AuthFlowException.class)
    public <T> T completePasswordLogin(com.security.security.UserPrincipal authenticated,
            java.util.function.Function<User, T> issueLogin) {
        User user = lockAccount(authenticated.getId());
        if (!java.util.Objects.equals(user.getPassword(), authenticated.getPassword())
                || !Boolean.TRUE.equals(user.getEnabled()) || !Boolean.TRUE.equals(user.getAccountNonLocked())
                || !Boolean.TRUE.equals(user.getAccountNonExpired()) || !Boolean.TRUE.equals(user.getCredentialsNonExpired())) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }
        return issueLogin.apply(user);
    }

    public void verifyStepUpIfPresent(Long userId, Map<String, String> body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        String currentPassword = body.get("currentPassword");
        if (currentPassword != null && !currentPassword.isBlank()) {
            User user = getUserById(userId);
            if (!com.security.validation.BcryptPasswordValidator.isWithinLimit(currentPassword)
                    || !passwordEncoder.matches(currentPassword, user.getPassword())) {
                throw new AuthFlowException(HttpStatus.BAD_REQUEST, "La contraseña actual es incorrecta");
            }
        }
    }

    public void changeOwnPassword(Long userId, String currentPassword, String newPassword) {
        User user = lockAccount(userId);
        if (!Boolean.TRUE.equals(user.getEnabled()) || !Boolean.TRUE.equals(user.getAccountNonLocked())) {
            throw new AuthFlowException(HttpStatus.FORBIDDEN);
        }
        if (!com.security.validation.BcryptPasswordValidator.isWithinLimit(currentPassword)
                || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "La contraseña actual es incorrecta");
        }
        if (currentPassword.equals(newPassword)) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "La nueva contraseña debe ser diferente a la actual");
        }
        LocalDateTime now = LocalDateTime.now();
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setCredentialsNonExpired(true);
        user.setUpdatedAt(now);
        sessionManagementService.invalidateAllUserSessions(user.getEmail());
        jdbcTemplate.update("UPDATE auth.login_challenges SET consumed_at=?,email_code_hash=NULL WHERE user_id=? AND consumed_at IS NULL",
                now, userId);
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
        userResponse.setEnabled(Boolean.TRUE.equals(user.getEnabled()));
        userResponse.setEmailVerified(Boolean.TRUE.equals(user.getEnabled()));
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
