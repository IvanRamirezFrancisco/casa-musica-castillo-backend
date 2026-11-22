package com.security.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.config.MercadoPagoConfiguration;
import com.security.entity.ExternalIdentity;
import com.security.entity.Role;
import com.security.entity.User;
import com.security.repository.ActiveSessionRepository;
import com.security.repository.ExternalIdentityRepository;
import com.security.repository.RefreshTokenRepository;
import com.security.repository.RoleRepository;
import com.security.repository.UserRepository;
import com.security.service.BackupCodeService;
import com.security.service.EmailService;
import com.security.service.GoogleAuthService;
import com.security.service.GoogleIdTokenVerifierService;
import com.security.service.GoogleIdTokenVerifierService.VerifiedGoogleIdentity;
import com.security.support.LocalPostgresIntegrationTest;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.google.enabled=true",
        "app.security.google.web-client-id=1234567890-web.apps.googleusercontent.com",
        "app.legal.registration.terms-version=v2026-test",
        "app.legal.registration.privacy-version=v2026-test",
        "cors.allowed-origins=http://localhost:4200,capacitor://localhost"
})
class GoogleAuthIntegrationTest extends LocalPostgresIntegrationTest {
    private static final String TEST_EMAIL_PREFIX = "gitest.";
    private static final AtomicInteger SEQ = new AtomicInteger(100);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired ExternalIdentityRepository externalIdentities;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired ActiveSessionRepository activeSessions;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired BackupCodeService backupCodeService;

    @MockitoBean EmailService emailService;
    @MockitoBean MercadoPagoConfiguration mercadoPagoConfiguration;
    @MockitoBean GoogleIdTokenVerifierService googleVerifier;

    @BeforeEach
    void setupRolesAndCleanState() {
        ensureRole("ROLE_USER");
        cleanTestRows();
    }

    @AfterEach
    void cleanupAfter() {
        cleanTestRows();
    }

    private void ensureRole(String roleName) {
        if (roles.findByName(roleName).isEmpty()) {
            Role role = new Role();
            role.setName(roleName);
            role.setDescription("Synthetic test role");
            roles.saveAndFlush(role);
        }
    }

    private void cleanTestRows() {
        List<Long> testUserIds = jdbc.queryForList(
                "SELECT id FROM auth.users WHERE email LIKE ?",
                Long.class, TEST_EMAIL_PREFIX + "%");
        for (Long id : testUserIds) {
            jdbc.update("DELETE FROM auth.backup_codes WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth.login_factor_limits WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth.account_deletion_requests WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth.security_mail_outbox WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth.refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth.external_identities WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth.active_sessions WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth.user_roles WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth.registration_legal_acceptances WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth.users WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM auth.google_auth_flows");
        jdbc.update("DELETE FROM auth.consumed_google_tokens");
    }

    private static String remoteIp() {
        return "10.99.0." + ((SEQ.incrementAndGet() % 200) + 1);
    }

    private String issueChallenge(String purpose) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/google/challenge")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("purpose", purpose))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nonce").isString())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("nonce").asText();
    }

    private JsonNode authenticateGoogle(VerifiedGoogleIdentity identity, String clientPlatform) throws Exception {
        String nonce = issueChallenge("AUTH");
        String idToken = "header.payload." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(idToken), eq(nonce))).thenReturn(identity);

        var reqBuilder = post("/api/auth/google/authenticate")
                .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("idToken", idToken, "nonce", nonce)));
        if (clientPlatform != null) {
            reqBuilder.header("X-Client-Platform", clientPlatform);
        }
        MvcResult result = mvc.perform(reqBuilder)
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    @Test
    void returnsPublicGoogleConfig() throws Exception {
        mvc.perform(get("/api/auth/google/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.webClientId").value("1234567890-web.apps.googleusercontent.com"));
    }

    @Test
    void completesNewGoogleRegistrationWithoutLocalPasswordEnforcesLegalAndHandlesUsernameConflicts() throws Exception {
        String email = TEST_EMAIL_PREFIX + "newuser@example.com";
        String sub = "sub-new-" + SEQ.incrementAndGet();
        // Also seed an existing user holding desired username so we test username 409 conflict & retry
        User existingUsernameHolder = createPasswordUser(TEST_EMAIL_PREFIX + "holder@example.com", "taken.user", "Clave!Segura99");

        VerifiedGoogleIdentity identity = new VerifiedGoogleIdentity(
                sub, email, true, null, null, null, "https://lh3.googleusercontent.com/pic", Instant.now().plusSeconds(300));
        JsonNode authData = authenticateGoogle(identity, "ANDROID");

        assertThat(authData.path("outcome").asText()).isEqualTo("REGISTRATION_REQUIRED");
        String regToken = authData.path("registrationToken").asText();
        assertThat(regToken).isNotBlank();
        assertThat(authData.path("legal").path("required").asBoolean()).isTrue();

        // Missing legal acceptance must reject with 400 and keep no user created
        mvc.perform(post("/api/auth/google/complete-registration")
                        .header("X-Client-Platform", "ANDROID")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "registrationToken", regToken,
                                "username", "free.name",
                                "firstName", "Valeria",
                                "lastName", "Mendoza"))))
                .andExpect(status().isBadRequest());
        assertThat(users.findByEmailIgnoreCase(email)).isEmpty();

        // Username collision returns 409 WITHOUT consuming registrationToken
        mvc.perform(post("/api/auth/google/complete-registration")
                        .header("X-Client-Platform", "ANDROID")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "registrationToken", regToken,
                                "username", existingUsernameHolder.getUsername(),
                                "firstName", "Valeria",
                                "lastName", "Mendoza",
                                "legalAcceptance", Map.of(
                                        "termsAccepted", true,
                                        "privacyRead", true,
                                        "termsVersion", "v2026-test",
                                        "privacyVersion", "v2026-test")))))
                .andExpect(status().isConflict());

        // Retrying with available username using same registrationToken succeeds!
        MvcResult completed = mvc.perform(post("/api/auth/google/complete-registration")
                        .header("X-Client-Platform", "ANDROID")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "registrationToken", regToken,
                                "username", "valeria.mendoza1",
                                "firstName", "Valeria",
                                "lastName", "Mendoza",
                                "legalAcceptance", Map.of(
                                        "termsAccepted", true,
                                        "privacyRead", true,
                                        "termsVersion", "v2026-test",
                                        "privacyVersion", "v2026-test")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.data.jwtResponse.accessToken").isString())
                .andExpect(jsonPath("$.data.jwtResponse.refreshToken").isString())
                .andExpect(jsonPath("$.data.jwtResponse.user.hasLocalPassword").value(false))
                .andExpect(jsonPath("$.data.jwtResponse.user.googleLinked").value(true))
                .andReturn();

        User created = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(created.getPassword()).isNull();
        assertThat(created.hasLocalPassword()).isFalse();
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(externalIdentities.findByUserIdAndProvider(created.getId(), GoogleAuthService.PROVIDER_GOOGLE)).isPresent();
        Integer legalCount = jdbc.queryForObject(
                "SELECT count(*) FROM auth.registration_legal_acceptances WHERE user_id = ?",
                Integer.class, created.getId());
        assertThat(legalCount).isEqualTo(1);

        // Scenario B capabilities + /change-password rejected with 409
        String accessToken = json.readTree(completed.getResponse().getContentAsString())
                .path("data").path("jwtResponse").path("accessToken").asText();
        mvc.perform(get("/api/users/security-settings").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasLocalPassword").value(false))
                .andExpect(jsonPath("$.data.googleLinked").value(true))
                .andExpect(jsonPath("$.data.canSetInitialPassword").value(true))
                .andExpect(jsonPath("$.data.canChangePassword").value(false))
                .andExpect(jsonPath("$.data.canUnlinkGoogle").value(false))
                .andExpect(jsonPath("$.data.hasValidAuthMethod").value(true));

        mvc.perform(post("/api/users/change-password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "currentPassword", "AnyPass!123",
                                "newPassword", "StrongPass!987"))))
                .andExpect(status().isConflict());

        // Subsequent Google login authenticates directly into the SAME user_id
        JsonNode secondLogin = authenticateGoogle(
                new VerifiedGoogleIdentity(sub, email, true, "Valeria", "Mendoza", "Valeria Mendoza", null, Instant.now().plusSeconds(300)),
                "ANDROID");
        assertThat(secondLogin.path("outcome").asText()).isEqualTo("AUTHENTICATED");
        assertThat(secondLogin.path("jwtResponse").path("user").path("id").asLong()).isEqualTo(created.getId());
    }

    @Test
    void preventsAutoLinkingAndDuplicateAccountsOnTraditionalEmailCollisionAndRejectsReplayEvenAfterConflict() throws Exception {
        String email = TEST_EMAIL_PREFIX + "traditional@example.com";
        User traditional = createPasswordUser(email, "trad.user.1", "TradPass!998");
        long initialUserCount = users.count();

        String nonce = issueChallenge("AUTH");
        String idToken = "header.collision." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(idToken), eq(nonce))).thenReturn(
                new VerifiedGoogleIdentity("sub-coll-1", email, true, "Ana", "Lopez", "Ana Lopez", null, Instant.now().plusSeconds(300)));

        // Unlinked traditional account receives 409 Conflict (never auto-linked or logged in)
        mvc.perform(post("/api/auth/google/authenticate")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", idToken, "nonce", nonce))))
                .andExpect(status().isConflict());

        assertThat(users.count()).isEqualTo(initialUserCount);
        assertThat(externalIdentities.existsByUserIdAndProvider(traditional.getId(), "GOOGLE")).isFalse();

        // Nonce & Google token must stay consumed after rollback (REQUIRES_NEW) — replay is rejected 401
        mvc.perform(post("/api/auth/google/authenticate")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", idToken, "nonce", nonce))))
                .andExpect(status().isUnauthorized());

        // Even with a fresh challenge nonce, replaying the same Google idToken is rejected 401
        String freshNonce = issueChallenge("AUTH");
        when(googleVerifier.verify(eq(idToken), eq(freshNonce))).thenReturn(
                new VerifiedGoogleIdentity("sub-coll-1", email, true, "Ana", "Lopez", "Ana Lopez", null, Instant.now().plusSeconds(300)));
        mvc.perform(post("/api/auth/google/authenticate")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", idToken, "nonce", freshNonce))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void enforcesDisabledAccountAndTwoFactorChallengeOnGoogleLogin() throws Exception {
        String email = TEST_EMAIL_PREFIX + "mfa@example.com";
        User mfaUser = createGoogleUser(email, "google.mfa.1", "sub-mfa-1");
        String totpSecret = "JBSWY3DPEHPK3PXP";
        mfaUser.setGoogleAuthSecret(totpSecret);
        mfaUser.setGoogleAuthEnabled(true);
        mfaUser.setTwoFactorEnabled(true);
        users.saveAndFlush(mfaUser);

        JsonNode challengeData = authenticateGoogle(
                new VerifiedGoogleIdentity("sub-mfa-1", email, true, "Mfa", "User", null, null, Instant.now().plusSeconds(300)),
                "ANDROID");
        assertThat(challengeData.path("outcome").asText()).isEqualTo("TWO_FACTOR_REQUIRED");
        String preAuth = challengeData.path("preAuthChallenge").asText();
        assertThat(preAuth).isNotBlank();

        String totpCode = String.format("%06d", new GoogleAuthenticator().getTotpPassword(totpSecret));
        mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", preAuth,
                                "method", "GOOGLE_AUTHENTICATOR",
                                "code", totpCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andExpect(jsonPath("$.data.user.id").value(mfaUser.getId()));

        // Disabled linked Google account returns 403 Forbidden
        mfaUser.setEnabled(false);
        users.saveAndFlush(mfaUser);
        String nonce = issueChallenge("AUTH");
        String disabledToken = "header.disabled." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(disabledToken), eq(nonce))).thenReturn(
                new VerifiedGoogleIdentity("sub-mfa-1", email, true, "Mfa", "User", null, null, Instant.now().plusSeconds(300)));

        mvc.perform(post("/api/auth/google/authenticate")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", disabledToken, "nonce", nonce))))
                .andExpect(status().isForbidden());
    }

    @Test
    void setsInitialPasswordReauthsRevokesPriorSessionsEnablesDualLoginAndHandlesLinkingScenarios() throws Exception {
        String email = TEST_EMAIL_PREFIX + "lifecycle@example.com";
        String sub = "sub-life-1";
        User googleOnly = createGoogleUser(email, "life.user.1", sub);

        // Login with Google -> get Scenario B session + refreshToken
        JsonNode login1 = authenticateGoogle(
                new VerifiedGoogleIdentity(sub, email, true, "Luis", "Castillo", null, null, Instant.now().plusSeconds(300)),
                "ANDROID");
        String initialJwt = login1.path("jwtResponse").path("accessToken").asText();
        String initialRefresh = login1.path("jwtResponse").path("refreshToken").asText();

        // Scenario B: cannot unlink Google before setting local password -> 409
        mvc.perform(post("/api/auth/google/unlink")
                        .header("Authorization", "Bearer " + initialJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("currentPassword", "x", "confirmed", true))))
                .andExpect(status().isConflict());

        // Step 1: Google reauth for SET_INITIAL_PASSWORD
        String reauthNonce = issueChallenge("REAUTH");
        String reauthIdToken = "header.reauth." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(reauthIdToken), eq(reauthNonce))).thenReturn(
                new VerifiedGoogleIdentity(sub, email, true, "Luis", "Castillo", null, null, Instant.now().plusSeconds(300)));

        MvcResult reauthRes = mvc.perform(post("/api/auth/google/reauthenticate")
                        .header("Authorization", "Bearer " + initialJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", reauthIdToken,
                                "nonce", reauthNonce,
                                "purpose", "SET_INITIAL_PASSWORD"))))
                .andExpect(status().isOk())
                .andReturn();
        String reauthToken = json.readTree(reauthRes.getResponse().getContentAsString())
                .path("data").path("reauthToken").asText();

        // Step 2: Set initial password
        mvc.perform(post("/api/users/set-initial-password")
                        .header("Authorization", "Bearer " + initialJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "reauthToken", reauthToken,
                                "newPassword", "DualAccess!2026"))))
                .andExpect(status().isOk());

        // Prior JWT and prior refreshToken must now both be rejected
        mvc.perform(get("/api/users/security-settings")
                        .header("Authorization", "Bearer " + initialJwt))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/session/refresh")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", initialRefresh))))
                .andExpect(status().isUnauthorized());

        // Login traditionally with password -> SAME user_id + Scenario C capabilities!
        MvcResult pwdLogin = mvc.perform(post("/api/auth/login")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "DualAccess!2026"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jwtResponse.user.id").value(googleOnly.getId()))
                .andExpect(jsonPath("$.data.jwtResponse.user.hasLocalPassword").value(true))
                .andExpect(jsonPath("$.data.jwtResponse.user.googleLinked").value(true))
                .andReturn();
        String dualJwt = json.readTree(pwdLogin.getResponse().getContentAsString())
                .path("data").path("jwtResponse").path("accessToken").asText();

        mvc.perform(get("/api/users/security-settings").header("Authorization", "Bearer " + dualJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canSetInitialPassword").value(false))
                .andExpect(jsonPath("$.data.canChangePassword").value(true))
                .andExpect(jsonPath("$.data.canUnlinkGoogle").value(true));

        // Login with Google still accesses the EXACT SAME user_id
        JsonNode googleAgain = authenticateGoogle(
                new VerifiedGoogleIdentity(sub, email, true, "Luis", "Castillo", null, null, Instant.now().plusSeconds(300)),
                null);
        assertThat(googleAgain.path("jwtResponse").path("user").path("id").asLong()).isEqualTo(googleOnly.getId());

        // Scenario D / Unlink Google now allowed with password + confirmation -> back to Scenario A
        mvc.perform(post("/api/auth/google/unlink")
                        .header("Authorization", "Bearer " + dualJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "currentPassword", "DualAccess!2026",
                                "confirmed", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.googleLinked").value(false))
                .andExpect(jsonPath("$.data.canLinkGoogle").value(true))
                .andExpect(jsonPath("$.data.canChangePassword").value(true));

        // Re-link Google verifying duplicate subject prevention + Scenario A -> C transition
        User otherGoogleHolder = createGoogleUser(TEST_EMAIL_PREFIX + "otherg@example.com", "other.g", "sub-taken-99");
        String dupLinkNonce = issueChallenge("LINK");
        String dupLinkToken = "header.link.dup." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(dupLinkToken), eq(dupLinkNonce))).thenReturn(
                new VerifiedGoogleIdentity(
                        externalIdentities.findByUserIdAndProvider(otherGoogleHolder.getId(), "GOOGLE").orElseThrow().getProviderSubject(),
                        otherGoogleHolder.getEmail(), true, "O", "H", null, null, Instant.now().plusSeconds(300)));

        mvc.perform(post("/api/auth/google/link")
                        .header("Authorization", "Bearer " + dualJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", dupLinkToken,
                                "nonce", dupLinkNonce,
                                "currentPassword", "DualAccess!2026",
                                "confirmed", true))))
                .andExpect(status().isConflict());

        // Link unique Google subject succeeds!
        String okLinkNonce = issueChallenge("LINK");
        String okLinkToken = "header.link.ok." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(okLinkToken), eq(okLinkNonce))).thenReturn(
                new VerifiedGoogleIdentity("sub-fresh-relink", "relink.google@example.com", true, "L", "C", null, null, Instant.now().plusSeconds(300)));

        mvc.perform(post("/api/auth/google/link")
                        .header("Authorization", "Bearer " + dualJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", okLinkToken,
                                "nonce", okLinkNonce,
                                "currentPassword", "DualAccess!2026",
                                "confirmed", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.googleLinked").value(true))
                .andExpect(jsonPath("$.data.hasLocalPassword").value(true));
    }

    @Test
    void allowsPasswordlessGoogleStepUpForTwoFactorAndAccountDeletion() throws Exception {
        String email = TEST_EMAIL_PREFIX + "stepup@example.com";
        String sub = "sub-stepup-1";
        User gUser = createGoogleUser(email, "stepup.user", sub);
        String totpSecret = "JBSWY3DPEHPK3PXP";
        gUser.setGoogleAuthSecret(totpSecret);
        gUser.setGoogleAuthEnabled(true);
        gUser.setTwoFactorEnabled(true);
        users.saveAndFlush(gUser);

        // Authenticate via Google + TOTP
        JsonNode ch = authenticateGoogle(
                new VerifiedGoogleIdentity(sub, email, true, "Step", "Up", null, null, Instant.now().plusSeconds(300)),
                "ANDROID");
        String code = String.format("%06d",
                new GoogleAuthenticator().getTotpPassword(totpSecret, Instant.now().minusSeconds(30).toEpochMilli()));
        MvcResult verified = mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", ch.path("preAuthChallenge").asText(),
                                "method", "GOOGLE_AUTHENTICATOR",
                                "code", code))))
                .andExpect(status().isOk())
                .andReturn();
        String jwt = json.readTree(verified.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();

        // Generate backup codes using Google reauthToken step-up + TOTP
        String reauthNonce = issueChallenge("REAUTH");
        String reauthId = "header.stepup.2fa." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(reauthId), eq(reauthNonce))).thenReturn(
                new VerifiedGoogleIdentity(sub, email, true, "Step", "Up", null, null, Instant.now().plusSeconds(300)));
        String totpForReauth = String.format("%06d",
                new GoogleAuthenticator().getTotpPassword(totpSecret, Instant.now().plusSeconds(30).toEpochMilli()));

        MvcResult r1 = mvc.perform(post("/api/auth/google/reauthenticate")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", reauthId,
                                "nonce", reauthNonce,
                                "purpose", "SECURITY_STEP_UP",
                                "mfaMethod", "GOOGLE_AUTHENTICATOR",
                                "mfaCode", totpForReauth))))
                .andExpect(status().isOk())
                .andReturn();
        String stepUpToken = json.readTree(r1.getResponse().getContentAsString()).path("data").path("reauthToken").asText();

        mvc.perform(post("/api/2fa/backup-codes/generate")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reauthToken", stepUpToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(10));

        // Account deletion request using Google reauthToken step-up
        String delNonce = issueChallenge("REAUTH");
        String delId = "header.stepup.del." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(delId), eq(delNonce))).thenReturn(
                new VerifiedGoogleIdentity(sub, email, true, "Step", "Up", null, null, Instant.now().plusSeconds(300)));
        MvcResult r2 = mvc.perform(post("/api/auth/google/reauthenticate")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", delId,
                                "nonce", delNonce,
                                "purpose", "DELETE_ACCOUNT",
                                "mfaMethod", "EMAIL",
                                "mfaCode", "000000")))) // User does not have EMAIL MFA; requires TOTP/BACKUP_CODE
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(r2.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void enforcesEmailMfaOnGoogleLoginAndBlocksPrivateResourcesBeforeCompletion() throws Exception {
        String email = TEST_EMAIL_PREFIX + "emailmfa@example.com";
        String sub = "sub-email-mfa-1";
        User user = createGoogleUser(email, "google.emailmfa.1", sub);
        user.setEmailEnabled(true);
        user.setTwoFactorEnabled(true);
        users.saveAndFlush(user);

        JsonNode challengeData = authenticateGoogle(
                new VerifiedGoogleIdentity(sub, email, true, "Email", "Mfa", null, null, Instant.now().plusSeconds(300)),
                "ANDROID");
        assertThat(challengeData.path("outcome").asText()).isEqualTo("TWO_FACTOR_REQUIRED");
        assertThat(challengeData.path("jwtResponse").isMissingNode()).isTrue();
        assertThat(challengeData.path("availableMethods").path("EMAIL").asBoolean()).isTrue();
        String preAuth = challengeData.path("preAuthChallenge").asText();

        // Private endpoint stays inaccessible before completing MFA
        mvc.perform(get("/api/users/profile"))
                .andExpect(status().isUnauthorized());

        // lastLoginAt must NOT be recorded prior to completing MFA
        ExternalIdentity linkBeforeMfa = externalIdentities.findByUserIdAndProvider(user.getId(), "GOOGLE").orElseThrow();
        assertThat(linkBeforeMfa.getLastLoginAt()).isNull();

        mvc.perform(post("/api/2fa/send-login-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("preAuthChallenge", preAuth, "method", "EMAIL"))))
                .andExpect(status().isOk());

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).send2FACodeEmail(any(User.class), codeCaptor.capture());
        String validCode = codeCaptor.getValue();
        String wrongCode = "000000".equals(validCode) ? "000001" : "000000";

        mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", preAuth,
                                "method", "EMAIL",
                                "code", wrongCode))))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", preAuth,
                                "method", "EMAIL",
                                "code", validCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andExpect(jsonPath("$.data.user.id").value(user.getId()));

        // Reusing completed challenge is rejected
        mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", preAuth,
                                "method", "EMAIL",
                                "code", validCode))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void enforcesSingleUseBackupCodesAndRejectsChallengeMismatchOnGoogleLogin() throws Exception {
        String email = TEST_EMAIL_PREFIX + "backupmfa@example.com";
        String sub = "sub-backup-mfa-1";
        User user = createGoogleUser(email, "google.backup.1", sub);
        user.setGoogleAuthSecret("JBSWY3DPEHPK3PXP");
        user.setGoogleAuthEnabled(true);
        user.setTwoFactorEnabled(true);
        users.saveAndFlush(user);

        List<String> backupCodes = backupCodeService.generateBackupCodes(user.getId());
        assertThat(backupCodes).hasSize(10);

        // Backup code without valid preAuthChallenge must never log in
        mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", "X".repeat(43),
                                "method", "BACKUP_CODE",
                                "code", backupCodes.get(0)))))
                .andExpect(status().isUnauthorized());

        JsonNode ch1 = authenticateGoogle(
                new VerifiedGoogleIdentity(sub, email, true, "Backup", "User", null, null, Instant.now().plusSeconds(300)),
                "ANDROID");
        assertThat(ch1.path("outcome").asText()).isEqualTo("TWO_FACTOR_REQUIRED");
        assertThat(ch1.path("availableMethods").path("BACKUP_CODE").asBoolean()).isTrue();

        mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", ch1.path("preAuthChallenge").asText(),
                                "method", "BACKUP_CODE",
                                "code", backupCodes.get(0)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.user.id").value(user.getId()));

        // Consumed backup code cannot be reused in a second Google login
        JsonNode ch2 = authenticateGoogle(
                new VerifiedGoogleIdentity(sub, email, true, "Backup", "User", null, null, Instant.now().plusSeconds(300)),
                "ANDROID");
        mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", ch2.path("preAuthChallenge").asText(),
                                "method", "BACKUP_CODE",
                                "code", backupCodes.get(0)))))
                .andExpect(status().isUnauthorized());

        // Second unused backup code still works
        mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", ch2.path("preAuthChallenge").asText(),
                                "method", "BACKUP_CODE",
                                "code", backupCodes.get(1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(user.getId()));
    }

    @Test
    void rejectsWrongOrReplayedTotpAndCrossUserChallengeOnGoogleLogin() throws Exception {
        String secretA = "JBSWY3DPEHPK3PXP";
        String secretB = "KRSXG5CTMVRXEZLU";
        String emailA = TEST_EMAIL_PREFIX + "totp.a@example.com";
        String emailB = TEST_EMAIL_PREFIX + "totp.b@example.com";

        User userA = createGoogleUser(emailA, "totp.a", "sub-totp-a");
        userA.setGoogleAuthSecret(secretA);
        userA.setGoogleAuthEnabled(true);
        userA.setTwoFactorEnabled(true);
        users.saveAndFlush(userA);

        User userB = createGoogleUser(emailB, "totp.b", "sub-totp-b");
        userB.setGoogleAuthSecret(secretB);
        userB.setGoogleAuthEnabled(true);
        userB.setTwoFactorEnabled(true);
        users.saveAndFlush(userB);

        JsonNode chA1 = authenticateGoogle(
                new VerifiedGoogleIdentity("sub-totp-a", emailA, true, "User", "A", null, null, Instant.now().plusSeconds(300)),
                "ANDROID");
        String preAuthA1 = chA1.path("preAuthChallenge").asText();

        String codeA = String.format("%06d", new GoogleAuthenticator().getTotpPassword(secretA));
        String codeB = String.format("%06d", new GoogleAuthenticator().getTotpPassword(secretB));
        String wrong = "000000".equals(codeA) ? "999999" : "000000";

        // Wrong TOTP fails
        mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", preAuthA1,
                                "method", "GOOGLE_AUTHENTICATOR",
                                "code", wrong))))
                .andExpect(status().isUnauthorized());

        // User B's TOTP fails for User A's challenge
        if (!codeB.equals(codeA)) {
            mvc.perform(post("/api/2fa/verify")
                            .header("X-Client-Platform", "ANDROID")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "preAuthChallenge", preAuthA1,
                                    "method", "GOOGLE_AUTHENTICATOR",
                                    "code", codeB))))
                    .andExpect(status().isUnauthorized());
        }

        // Valid TOTP succeeds once
        mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", preAuthA1,
                                "method", "GOOGLE_AUTHENTICATOR",
                                "code", codeA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(userA.getId()));

        // Replaying the same TOTP slot on a second Google challenge must fail
        JsonNode chA2 = authenticateGoogle(
                new VerifiedGoogleIdentity("sub-totp-a", emailA, true, "User", "A", null, null, Instant.now().plusSeconds(300)),
                "ANDROID");
        mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", chA2.path("preAuthChallenge").asText(),
                                "method", "GOOGLE_AUTHENTICATOR",
                                "code", codeA))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsPasswordLoginForGoogleOnlyUserAndUnifiesDualAccountMfaAcrossMethodsPreservingTotpOnUnlink() throws Exception {
        String googleOnlyEmail = TEST_EMAIL_PREFIX + "nopass@example.com";
        createGoogleUser(googleOnlyEmail, "nopass.user", "sub-nopass-1");

        mvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", googleOnlyEmail, "password", "AnyPass!2026"))))
                .andExpect(status().isUnauthorized());

        // Traditional user with TOTP + backup codes + linked Google uses same user_id and preserves MFA on unlink
        String dualEmail = TEST_EMAIL_PREFIX + "dual.mfa@example.com";
        String dualPass = "DualMfa!2026";
        String totpSecret = "JBSWY3DPEHPK3PXP";
        User dual = createPasswordUser(dualEmail, "dual.mfa.user", dualPass);
        dual.setGoogleAuthSecret(totpSecret);
        dual.setGoogleAuthEnabled(true);
        dual.setTwoFactorEnabled(true);
        dual = users.saveAndFlush(dual);
        List<String> backupCodes = backupCodeService.generateBackupCodes(dual.getId());

        ExternalIdentity identity = new ExternalIdentity();
        identity.setUser(dual);
        identity.setProvider("GOOGLE");
        identity.setProviderSubject("sub-dual-mfa-1");
        identity.setProviderEmail(dualEmail);
        identity.setEmailVerified(true);
        externalIdentities.saveAndFlush(identity);

        // Password login -> 2FA required
        MvcResult pwdRes = mvc.perform(post("/api/auth/login")
                        .header("X-Client-Platform", "ANDROID")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", dualEmail, "password", dualPass))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.twoFactorRequired").value(true))
                .andReturn();
        String pwdPreAuth = json.readTree(pwdRes.getResponse().getContentAsString()).path("data").path("preAuthChallenge").asText();
        String totpEarlier = String.format("%06d",
                new GoogleAuthenticator().getTotpPassword(totpSecret, Instant.now().minusSeconds(30).toEpochMilli()));

        MvcResult pwdMfaRes = mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", pwdPreAuth,
                                "method", "GOOGLE_AUTHENTICATOR",
                                "code", totpEarlier))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(dual.getId()))
                .andReturn();
        String jwt = json.readTree(pwdMfaRes.getResponse().getContentAsString()).path("data").path("accessToken").asText();

        // Google login -> 2FA required -> SAME user_id
        JsonNode gChallenge = authenticateGoogle(
                new VerifiedGoogleIdentity("sub-dual-mfa-1", dualEmail, true, "Dual", "Mfa", null, null, Instant.now().plusSeconds(300)),
                "ANDROID");
        assertThat(gChallenge.path("outcome").asText()).isEqualTo("TWO_FACTOR_REQUIRED");
        String totpLater = String.format("%06d",
                new GoogleAuthenticator().getTotpPassword(totpSecret, Instant.now().plusSeconds(30).toEpochMilli()));

        mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "preAuthChallenge", gChallenge.path("preAuthChallenge").asText(),
                                "method", "GOOGLE_AUTHENTICATOR",
                                "code", totpLater))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(dual.getId()));

        // Unlink Google preserving TOTP + remaining backup codes
        mvc.perform(post("/api/auth/google/unlink")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "currentPassword", dualPass,
                                "confirmed", true,
                                "mfaMethod", "BACKUP_CODE",
                                "mfaCode", backupCodes.get(0)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.googleLinked").value(false));

        User reloaded = users.findById(dual.getId()).orElseThrow();
        assertThat(reloaded.getGoogleAuthEnabled()).isTrue();
        assertThat(reloaded.getGoogleAuthSecret()).isEqualTo(totpSecret);
        assertThat(reloaded.getBackupCodesEnabled()).isTrue();
        assertThat((Long) backupCodeService.getBackupCodeStats(dual.getId()).get("available")).isEqualTo(9L);
    }

    @Test
    void rejectsLockedGoogleAccount() throws Exception {
        String email = TEST_EMAIL_PREFIX + "locked@example.com";
        User locked = createGoogleUser(email, "locked.g.user", "sub-locked-1");
        locked.setAccountNonLocked(false);
        users.saveAndFlush(locked);

        String nonce = issueChallenge("AUTH");
        String idToken = "header.locked." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(idToken), eq(nonce))).thenReturn(
                new VerifiedGoogleIdentity("sub-locked-1", email, true, "Locked", "User", null, null, Instant.now().plusSeconds(300)));

        mvc.perform(post("/api/auth/google/authenticate")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", idToken, "nonce", nonce))))
                .andExpect(status().isForbidden());
    }

    private User createPasswordUser(String email, String username, String rawPassword) {
        Role role = roles.findByName("ROLE_USER").orElseThrow();
        User u = new User();
        u.setEmail(email);
        u.setUsername(username);
        u.setFirstName("Trad");
        u.setLastName("User");
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setEnabled(true);
        u.setAccountNonExpired(true);
        u.setAccountNonLocked(true);
        u.setCredentialsNonExpired(true);
        u.setTwoFactorEnabled(false);
        u.setIsCustomer(true);
        u.recordEmailVerification(LocalDateTime.now());
        u.setRoles(Collections.singleton(role));
        return users.saveAndFlush(u);
    }

    private User createGoogleUser(String email, String username, String sub) {
        Role role = roles.findByName("ROLE_USER").orElseThrow();
        User u = new User();
        u.setEmail(email);
        u.setUsername(username);
        u.setFirstName("Google");
        u.setLastName("User");
        u.setPassword(null);
        u.setEnabled(true);
        u.setAccountNonExpired(true);
        u.setAccountNonLocked(true);
        u.setCredentialsNonExpired(true);
        u.setTwoFactorEnabled(false);
        u.setIsCustomer(true);
        u.recordEmailVerification(LocalDateTime.now());
        u.setRoles(Collections.singleton(role));
        User saved = users.saveAndFlush(u);

        ExternalIdentity identity = new ExternalIdentity();
        identity.setUser(saved);
        identity.setProvider("GOOGLE");
        identity.setProviderSubject(sub);
        identity.setProviderEmail(email);
        identity.setEmailVerified(true);
        externalIdentities.saveAndFlush(identity);
        return saved;
    }
}
