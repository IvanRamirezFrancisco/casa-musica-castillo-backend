package com.security.service;

import com.security.config.GoogleAuthProperties;
import com.security.dto.request.CompleteGoogleRegistrationRequest;
import com.security.dto.request.GoogleAuthenticateRequest;
import com.security.dto.request.GoogleReauthRequest;
import com.security.dto.request.LinkGoogleRequest;
import com.security.dto.request.UnlinkGoogleRequest;
import com.security.dto.response.JwtAuthResponse;
import com.security.dto.response.UserResponse;
import com.security.entity.ExternalIdentity;
import com.security.entity.Role;
import com.security.entity.User;
import com.security.exception.AuthFlowException;
import com.security.repository.ExternalIdentityRepository;
import com.security.repository.RoleRepository;
import com.security.repository.UserRepository;
import com.security.security.JwtTokenProvider;
import com.security.security.UserPrincipal;
import com.security.validation.BcryptPasswordValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class GoogleAuthService {
    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);
    public static final String PROVIDER_GOOGLE = "GOOGLE";
    private static final Pattern NON_USERNAME_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

    private final GoogleAuthProperties properties;
    private final GoogleIdTokenVerifierService verifier;
    private final GoogleFlowTokenService flowTokens;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ExternalIdentityRepository externalIdentities;
    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final SessionManagementService sessionManagementService;
    private final LoginChallengeService loginChallenges;
    private final TwoFactorService twoFactorService;
    private final BackupCodeService backupCodeService;
    private final RegistrationLegalPolicy legalPolicy;
    private final AuditLogService auditLogService;
    private final VerificationRequestLimit requestLimit;
    private final ActivationClientAddress clientAddress;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;
    @org.springframework.beans.factory.annotation.Value("${app.maintenance.enabled:true}")
    private boolean maintenanceEnabled = true;

    public record GoogleAuthResult(Map<String, Object> body, HttpHeaders headers) {}

    @Autowired
    public GoogleAuthService(
            GoogleAuthProperties properties,
            GoogleIdTokenVerifierService verifier,
            GoogleFlowTokenService flowTokens,
            UserRepository userRepository,
            RoleRepository roleRepository,
            ExternalIdentityRepository externalIdentities,
            UserService userService,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenService refreshTokenService,
            SessionManagementService sessionManagementService,
            LoginChallengeService loginChallenges,
            TwoFactorService twoFactorService,
            BackupCodeService backupCodeService,
            RegistrationLegalPolicy legalPolicy,
            AuditLogService auditLogService,
            VerificationRequestLimit requestLimit,
            ActivationClientAddress clientAddress,
            PasswordEncoder passwordEncoder,
            PlatformTransactionManager txManager,
            @Autowired(required = false) Clock clock) {
        this.properties = properties;
        this.verifier = verifier;
        this.flowTokens = flowTokens;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.externalIdentities = externalIdentities;
        this.userService = userService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.sessionManagementService = sessionManagementService;
        this.loginChallenges = loginChallenges;
        this.twoFactorService = twoFactorService;
        this.backupCodeService = backupCodeService;
        this.legalPolicy = legalPolicy;
        this.auditLogService = auditLogService;
        this.requestLimit = requestLimit;
        this.clientAddress = clientAddress;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> getPublicConfig() {
        Map<String, Object> data = new HashMap<>();
        data.put("enabled", properties.isConfigured());
        data.put("webClientId", properties.isConfigured() ? properties.getWebClientId() : "");
        return data;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> issueChallenge(String purpose, HttpServletRequest request) {
        if (!properties.isConfigured()) {
            throw new AuthFlowException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El acceso con Google no está configurado en este servidor.");
        }
        requestLimit.check("google-challenge", clientAddress.resolve(request), 30);
        String nonce = flowTokens.createNonceChallenge(purpose);
        return Map.of(
                "nonce", nonce,
                "expiresInSeconds", properties.getChallengeTtlSeconds());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GoogleAuthResult authenticate(GoogleAuthenticateRequest requestBody, HttpServletRequest request) {
        requestLimit.check("google-authenticate-ip", clientAddress.resolve(request), 30);
        return requiresNewTx.execute(status -> authenticateInTx(requestBody, request));
    }

    private GoogleAuthResult authenticateInTx(GoogleAuthenticateRequest requestBody, HttpServletRequest request) {
        flowTokens.consumeNonceChallenge(requestBody.nonce());
        GoogleIdTokenVerifierService.VerifiedGoogleIdentity verified = verifier.verify(
                requestBody.idToken(), requestBody.nonce());
        flowTokens.markGoogleTokenConsumed(requestBody.idToken(), verified.expiresAt());

        Optional<ExternalIdentity> existingLink = externalIdentities.findByProviderAndProviderSubject(
                PROVIDER_GOOGLE, verified.subject());
        if (existingLink.isPresent()) {
            User user = userRepository.findByIdForAuthUpdate(existingLink.get().getUser().getId())
                    .orElseThrow(() -> new AuthFlowException(HttpStatus.UNAUTHORIZED, "Cuenta no disponible."));
            assertUserAllowedToSignIn(user);

            boolean requiresTwoFactor = LoginChallengeService.requiresTwoFactor(user);
            ExternalIdentity link = existingLink.get();
            if (!requiresTwoFactor) {
                link.setLastLoginAt(LocalDateTime.now(clock));
            }
            link.setProviderEmail(verified.email());
            link.setEmailVerified(verified.emailVerified());
            if (verified.pictureUrl() != null) {
                link.setPictureUrl(verified.pictureUrl());
                if (user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()) {
                    user.setAvatarUrl(verified.pictureUrl());
                }
            }
            if (!user.isEmailVerified() && verified.email().equalsIgnoreCase(user.getEmail())) {
                user.recordEmailVerification(LocalDateTime.now(clock));
            }
            externalIdentities.save(link);

            if (requiresTwoFactor) {
                LoginChallengeService.PendingLogin pending = loginChallenges.create(user.getId());
                Map<String, Object> data = new HashMap<>();
                data.put("outcome", "TWO_FACTOR_REQUIRED");
                data.put("twoFactorRequired", pending.twoFactorRequired());
                data.put("preAuthChallenge", pending.preAuthChallenge());
                data.put("availableMethods", pending.availableMethods());
                data.put("maskedEmail", maskEmail(user.getEmail()));
                return new GoogleAuthResult(data, new HttpHeaders());
            }

            JwtAuthResponse jwtResponse = issueAppSession(user, request);
            HttpHeaders headers = refreshTokenService.issueAndAttach(jwtResponse, user, request);
            audit("LOGIN", "GOOGLE_LOGIN_SUCCESS", user.getId(), "INFO", true);

            Map<String, Object> data = new HashMap<>();
            data.put("outcome", "AUTHENTICATED");
            data.put("jwtResponse", jwtResponse);
            return new GoogleAuthResult(data, headers);
        }

        if (userRepository.findByEmailIgnoreCase(verified.email()).isPresent()) {
            // Security invariant: never auto-link or create a duplicate account on email match alone.
            throw new AuthFlowException(HttpStatus.CONFLICT,
                    "No fue posible iniciar sesión directamente con Google. Si ya tienes cuenta con correo y contraseña, "
                            + "inicia sesión normalmente para vincular Google desde Seguridad de la cuenta.");
        }

        String registrationToken = flowTokens.createPendingRegistration(verified);
        String suggestedUsername = suggestAvailableUsername(verified);

        Map<String, Object> profile = new HashMap<>();
        profile.put("email", verified.email());
        profile.put("emailVerified", verified.emailVerified());
        profile.put("firstName", verified.givenName() != null ? verified.givenName() : "");
        profile.put("lastName", verified.familyName() != null ? verified.familyName() : "");
        profile.put("pictureUrl", verified.pictureUrl());
        profile.put("suggestedUsername", suggestedUsername);

        Map<String, Object> data = new HashMap<>();
        data.put("outcome", "REGISTRATION_REQUIRED");
        data.put("registrationToken", registrationToken);
        data.put("profile", profile);
        data.put("legal", legalPolicy.documents());
        return new GoogleAuthResult(data, new HttpHeaders());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GoogleAuthResult completeRegistration(CompleteGoogleRegistrationRequest requestBody, HttpServletRequest request) {
        requestLimit.check("google-complete-reg", clientAddress.resolve(request), 20);
        legalPolicy.validate(requestBody.legalAcceptance());

        String normalizedUsername = requestBody.username().trim();
        String firstName = normalizeName(requestBody.firstName());
        String lastName = normalizeName(requestBody.lastName());

        try {
            return requiresNewTx.execute(status ->
                    completeRegistrationInTx(requestBody.registrationToken(), normalizedUsername, firstName, lastName, request));
        } catch (DataIntegrityViolationException ex) {
            if (userRepository.existsByUsernameIgnoreCase(normalizedUsername)
                    || userRepository.existsByUsername(normalizedUsername)) {
                throw new AuthFlowException(HttpStatus.CONFLICT,
                        "Ese nombre de usuario ya está en uso. Elige otro para continuar.");
            }
            throw new AuthFlowException(HttpStatus.CONFLICT,
                    "No fue posible completar el registro porque el correo ya está asociado a una cuenta.");
        }
    }

    private GoogleAuthResult completeRegistrationInTx(
            String registrationToken,
            String username,
            String firstName,
            String lastName,
            HttpServletRequest request) {
        GoogleFlowTokenService.PendingRegistrationFlow pending = flowTokens.lockPendingRegistration(registrationToken);

        if (userRepository.existsByUsernameIgnoreCase(username) || userRepository.existsByUsername(username)) {
            // Keep registrationToken unconsumed so the user can choose another username without restarting OAuth.
            throw new AuthFlowException(HttpStatus.CONFLICT,
                    "Ese nombre de usuario ya está en uso. Elige otro para continuar.");
        }

        if (externalIdentities.findByProviderAndProviderSubject(PROVIDER_GOOGLE, pending.subject()).isPresent()
                || userRepository.existsByEmailIgnoreCase(pending.email())) {
            flowTokens.markFlowConsumed(pending.flowId());
            throw new AuthFlowException(HttpStatus.CONFLICT,
                    "Ya existe una cuenta registrada con este correo. Inicia sesión con tu método habitual.");
        }

        Role role = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("User Role not set."));

        User user = new User();
        user.setUsername(username);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(pending.email().trim().toLowerCase(Locale.ROOT));
        user.setPassword(null);
        user.setAvatarUrl(pending.pictureUrl());
        user.setEnabled(true);
        user.setAccountNonExpired(true);
        user.setAccountNonLocked(true);
        user.setCredentialsNonExpired(true);
        user.setTwoFactorEnabled(false);
        user.setIsCustomer(true);
        user.recordEmailVerification(LocalDateTime.now(clock));
        user.setRoles(Collections.singleton(role));

        User saved = userRepository.saveAndFlush(user);

        ExternalIdentity identity = new ExternalIdentity();
        identity.setUser(saved);
        identity.setProvider(PROVIDER_GOOGLE);
        identity.setProviderSubject(pending.subject());
        identity.setProviderEmail(saved.getEmail());
        identity.setEmailVerified(true);
        identity.setPictureUrl(pending.pictureUrl());
        identity.setLastLoginAt(LocalDateTime.now(clock));
        externalIdentities.saveAndFlush(identity);

        legalPolicy.record(saved.getId());
        flowTokens.markFlowConsumed(pending.flowId());

        JwtAuthResponse jwtResponse = issueAppSession(saved, request);
        HttpHeaders headers = refreshTokenService.issueAndAttach(jwtResponse, saved, request);
        audit("CREATE", "GOOGLE_REGISTRATION_SUCCESS", saved.getId(), "INFO", true);

        Map<String, Object> data = new HashMap<>();
        data.put("outcome", "AUTHENTICATED");
        data.put("jwtResponse", jwtResponse);
        return new GoogleAuthResult(data, headers);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> reauthenticate(Long userId, String sessionJti, GoogleReauthRequest req) {
        if (sessionJti == null || sessionJti.isBlank()) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Sesión inválida.");
        }
        requestLimit.check("google-reauth", userId.toString(), 10);
        return requiresNewTx.execute(status -> {
            if (!sessionManagementService.isSessionValid(sessionJti)
                    || !sessionManagementService.belongsToUser(sessionJti, userId)) {
                throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Tu sesión expiró.");
            }
            User user = lockEnabledUser(userId);
            ExternalIdentity identity = externalIdentities.findByUserIdAndProvider(userId, PROVIDER_GOOGLE)
                    .orElseThrow(() -> new AuthFlowException(HttpStatus.CONFLICT,
                            "Tu cuenta no tiene una identidad de Google vinculada."));

            flowTokens.consumeNonceChallenge(req.nonce());
            GoogleIdTokenVerifierService.VerifiedGoogleIdentity verified = verifier.verify(req.idToken(), req.nonce());
            flowTokens.markGoogleTokenConsumed(req.idToken(), verified.expiresAt());

            if (!identity.getProviderSubject().equals(verified.subject())) {
                throw new AuthFlowException(HttpStatus.FORBIDDEN,
                        "La cuenta de Google seleccionada no coincide con la vinculada a este perfil.");
            }

            verifyAuthenticatedMfaIfEnabled(user, req.mfaMethod(), req.mfaCode());
            String reauthToken = flowTokens.createReauthToken(userId, sessionJti, req.purpose());
            audit("SECURITY", "GOOGLE_REAUTHENTICATED", userId, "INFO", true);

            return Map.of(
                    "reauthToken", reauthToken,
                    "expiresInSeconds", properties.getReauthTtlSeconds());
        });
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> linkGoogle(Long userId, String sessionJti, LinkGoogleRequest req) {
        if (!req.confirmed()) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "Debes confirmar explícitamente la vinculación de Google.");
        }
        if (sessionJti == null || sessionJti.isBlank()) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Sesión inválida.");
        }
        requestLimit.check("google-link", userId.toString(), 10);
        try {
            return requiresNewTx.execute(status -> {
                if (!sessionManagementService.isSessionValid(sessionJti)
                        || !sessionManagementService.belongsToUser(sessionJti, userId)) {
                    throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Tu sesión expiró.");
                }
                User user = lockEnabledUser(userId);
                if (!user.hasLocalPassword()) {
                    throw new AuthFlowException(HttpStatus.BAD_REQUEST,
                            "Esta cuenta requiere otro mecanismo de verificación antes de vincular.");
                }
                if (!BcryptPasswordValidator.isWithinLimit(req.currentPassword())
                        || !passwordEncoder.matches(req.currentPassword(), user.getPassword())) {
                    throw new AuthFlowException(HttpStatus.BAD_REQUEST, "La contraseña actual es incorrecta.");
                }
                verifyAuthenticatedMfaIfEnabled(user, req.mfaMethod(), req.mfaCode());

                if (externalIdentities.existsByUserIdAndProvider(userId, PROVIDER_GOOGLE)) {
                    throw new AuthFlowException(HttpStatus.CONFLICT, "Tu cuenta ya tiene una cuenta de Google vinculada.");
                }

                flowTokens.consumeNonceChallenge(req.nonce());
                GoogleIdTokenVerifierService.VerifiedGoogleIdentity verified = verifier.verify(req.idToken(), req.nonce());
                flowTokens.markGoogleTokenConsumed(req.idToken(), verified.expiresAt());

                Optional<ExternalIdentity> owner = externalIdentities.findByProviderAndProviderSubject(
                        PROVIDER_GOOGLE, verified.subject());
                if (owner.isPresent()) {
                    throw new AuthFlowException(HttpStatus.CONFLICT,
                            "Esta cuenta de Google ya está vinculada a otro usuario.");
                }

                ExternalIdentity identity = new ExternalIdentity();
                identity.setUser(user);
                identity.setProvider(PROVIDER_GOOGLE);
                identity.setProviderSubject(verified.subject());
                identity.setProviderEmail(verified.email());
                identity.setEmailVerified(verified.emailVerified());
                identity.setPictureUrl(verified.pictureUrl());
                externalIdentities.saveAndFlush(identity);

                if ((user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()) && verified.pictureUrl() != null) {
                    user.setAvatarUrl(verified.pictureUrl());
                    userRepository.save(user);
                }

                audit("UPDATE", "GOOGLE_IDENTITY_LINKED", userId, "WARNING", true);
                return buildSecurityCapabilities(user);
            });
        } catch (DataIntegrityViolationException ex) {
            throw new AuthFlowException(HttpStatus.CONFLICT,
                    "Esta cuenta de Google ya está vinculada o tu perfil ya tiene una cuenta de Google asociada.");
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> unlinkGoogle(Long userId, String sessionJti, UnlinkGoogleRequest req) {
        if (!req.confirmed()) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "Confirma que deseas desvincular tu cuenta de Google.");
        }
        if (sessionJti == null || sessionJti.isBlank()) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Sesión inválida.");
        }
        requestLimit.check("google-unlink", userId.toString(), 10);
        return requiresNewTx.execute(status -> {
            if (!sessionManagementService.isSessionValid(sessionJti)
                    || !sessionManagementService.belongsToUser(sessionJti, userId)) {
                throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Tu sesión expiró.");
            }
            User user = lockEnabledUser(userId);
            if (!user.hasLocalPassword()) {
                throw new AuthFlowException(HttpStatus.CONFLICT,
                        "Debes configurar una contraseña local antes de desvincular Google para no perder acceso a tu cuenta.");
            }
            ExternalIdentity identity = externalIdentities.findByUserIdAndProvider(userId, PROVIDER_GOOGLE)
                    .orElseThrow(() -> new AuthFlowException(HttpStatus.NOT_FOUND,
                            "Tu cuenta no tiene una identidad de Google vinculada."));

            if (req.reauthToken() != null && !req.reauthToken().isBlank()) {
                flowTokens.consumeReauthToken(userId, sessionJti, req.reauthToken().trim(), "UNLINK_GOOGLE");
            } else if (req.currentPassword() != null && !req.currentPassword().isBlank()) {
                if (!BcryptPasswordValidator.isWithinLimit(req.currentPassword())
                        || !passwordEncoder.matches(req.currentPassword(), user.getPassword())) {
                    throw new AuthFlowException(HttpStatus.BAD_REQUEST, "La contraseña actual es incorrecta.");
                }
                verifyAuthenticatedMfaIfEnabled(user, req.mfaMethod(), req.mfaCode());
            } else {
                throw new AuthFlowException(HttpStatus.BAD_REQUEST,
                        "Confirma tu identidad mediante contraseña actual o verificación reciente.");
            }

            externalIdentities.delete(identity);
            externalIdentities.flush();
            audit("UPDATE", "GOOGLE_IDENTITY_UNLINKED", userId, "WARNING", true);
            return buildSecurityCapabilities(user);
        });
    }

    public Map<String, Object> buildSecurityCapabilities(User user) {
        boolean hasLocalPassword = user.hasLocalPassword();
        Optional<ExternalIdentity> googleOpt = externalIdentities.findByUserIdAndProvider(user.getId(), PROVIDER_GOOGLE);
        boolean googleLinked = googleOpt.isPresent();
        boolean mfaActive = Boolean.TRUE.equals(user.getGoogleAuthEnabled()) || Boolean.TRUE.equals(user.getEmailEnabled());

        Map<String, Object> data = new HashMap<>();
        data.put("hasLocalPassword", hasLocalPassword);
        data.put("googleLinked", googleLinked);
        data.put("googleConfigured", properties.isConfigured());
        data.put("googleEmail", googleOpt.map(ExternalIdentity::getProviderEmail).orElse(null));
        data.put("googleLinkedAt", googleOpt.map(ExternalIdentity::getLinkedAt).orElse(null));
        data.put("canSetInitialPassword", !hasLocalPassword && googleLinked);
        data.put("canChangePassword", hasLocalPassword);
        data.put("canLinkGoogle", !googleLinked && hasLocalPassword && properties.isConfigured());
        data.put("canUnlinkGoogle", googleLinked && hasLocalPassword);
        data.put("hasValidAuthMethod", hasLocalPassword || googleLinked);
        data.put("mfaActive", mfaActive);
        data.put("requiresRecentAuth", true);
        data.put("passwordChangeRevokesSessions", true);
        return data;
    }

    private void verifyAuthenticatedMfaIfEnabled(User user, String method, String code) {
        boolean hasTotp = Boolean.TRUE.equals(user.getGoogleAuthEnabled());
        boolean hasEmail = Boolean.TRUE.equals(user.getEmailEnabled());
        boolean hasBackup = Boolean.TRUE.equals(user.getBackupCodesEnabled());
        if (!hasTotp && !hasEmail) return;

        if (method == null || method.isBlank() || code == null || code.isBlank()) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST,
                    "Tu cuenta tiene verificación en dos pasos activa. Ingresa tu código para continuar.");
        }
        String trimmedCode = code.trim();
        boolean valid = switch (method) {
            case "GOOGLE_AUTHENTICATOR" -> hasTotp && trimmedCode.matches("[0-9]{6}")
                    && flowTokens.acceptTotpSlot(user, trimmedCode);
            case "EMAIL" -> hasEmail && trimmedCode.matches("[0-9]{6}")
                    && twoFactorService.verifyEmailCode(user.getId(), trimmedCode);
            case "BACKUP_CODE" -> hasBackup && backupCodeService.verifyBackupCode(user.getId(), trimmedCode);
            default -> false;
        };
        if (!valid) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "El código de verificación en dos pasos es inválido.");
        }
    }

    private JwtAuthResponse issueAppSession(User user, HttpServletRequest request) {
        User loaded = userRepository.findByIdWithRolesAndPermissions(user.getId()).orElse(user);
        UserPrincipal principal = UserPrincipal.create(loaded);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        String accessToken = jwtTokenProvider.generateToken(auth, request);
        UserResponse userResponse = userService.convertToUserResponse(loaded);
        return new JwtAuthResponse(accessToken, jwtTokenProvider.getExpirationTime(), userResponse);
    }

    private User lockEnabledUser(Long userId) {
        User user = userRepository.findByIdForAuthUpdate(userId)
                .orElseThrow(() -> new AuthFlowException(HttpStatus.UNAUTHORIZED, "Usuario no disponible."));
        if (!Boolean.TRUE.equals(user.getEnabled()) || !Boolean.TRUE.equals(user.getAccountNonLocked())) {
            throw new AuthFlowException(HttpStatus.FORBIDDEN, "La cuenta está deshabilitada o bloqueada.");
        }
        return user;
    }

    private static void assertUserAllowedToSignIn(User user) {
        if (!Boolean.TRUE.equals(user.getEnabled())
                || !Boolean.TRUE.equals(user.getAccountNonLocked())
                || !Boolean.TRUE.equals(user.getAccountNonExpired())) {
            throw new AuthFlowException(HttpStatus.FORBIDDEN,
                    "Tu cuenta se encuentra deshabilitada o bloqueada temporalmente.");
        }
    }

    private String suggestAvailableUsername(GoogleIdTokenVerifierService.VerifiedGoogleIdentity verified) {
        StringBuilder raw = new StringBuilder();
        if (verified.givenName() != null) raw.append(verified.givenName());
        if (verified.familyName() != null) {
            if (!raw.isEmpty()) raw.append('.');
            raw.append(verified.familyName());
        }
        if (raw.isEmpty() && verified.email() != null) {
            int at = verified.email().indexOf('@');
            raw.append(at > 0 ? verified.email().substring(0, at) : "usuario");
        }
        String normalized = Normalizer.normalize(raw.toString().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String base = NON_USERNAME_CHARS.matcher(normalized.replace(' ', '.')).replaceAll("")
                .replaceAll("\\.{2,}", ".")
                .replaceAll("^[._-]+|[._-]+$", "");
        if (base.length() < 3) {
            base = "usuario." + Math.abs(verified.subject().hashCode() % 10000);
        }
        if (base.length() > 24) {
            base = base.substring(0, 24);
        }
        if (!userRepository.existsByUsernameIgnoreCase(base) && !userRepository.existsByUsername(base)) {
            return base;
        }
        for (int attempt = 1; attempt <= 20; attempt++) {
            String candidate = base + "." + (attempt * 7 + Math.floorMod(verified.subject().hashCode(), 90) + 10);
            if (candidate.length() > 30) candidate = candidate.substring(0, 30);
            if (!userRepository.existsByUsernameIgnoreCase(candidate) && !userRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }
        return "usuario." + Long.toString(Math.abs(clock.millis()) % 1000000L);
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "tu correo";
        int at = email.indexOf('@');
        return (at > 0 ? email.substring(0, 1) : "") + "***" + email.substring(at);
    }

    private void audit(String action, String eventType, Long userId, String severity, boolean success) {
        try {
            auditLogService.log(action, eventType, "USER", userId, null, null, severity, success);
        } catch (RuntimeException ex) {
            log.warn("google_auth_audit_unavailable account={} event={}", userId, eventType);
        }
    }

    @Scheduled(fixedDelay = 3600000)
    public void cleanupExpiredFlows() {
        if (!maintenanceEnabled) return;
        flowTokens.cleanupExpired();
    }
}
