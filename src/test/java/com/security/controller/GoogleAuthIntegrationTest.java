package com.security.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.config.GoogleAuthProperties;
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
import com.security.service.SecurityMailQueue;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
        "cors.allowed-origins=http://localhost:4200,capacitor://localhost",
        "spring.datasource.hikari.maximum-pool-size=8"
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
    @Autowired SecurityMailQueue securityMailQueue;
    @Autowired GoogleAuthProperties googleAuthProperties;

    @MockitoBean EmailService emailService;
    @MockitoBean MercadoPagoConfiguration mercadoPagoConfiguration;
    @MockitoBean GoogleIdTokenVerifierService googleVerifier;

    @BeforeEach
    void setupRolesAndCleanState() {
        googleAuthProperties.setAllowLegacyUnauthenticatedChallenge(true);
        when(emailService.deliverGoogleLinked(any(), anyString())).thenReturn(EmailService.ActivationDelivery.ACCEPTED);
        when(emailService.deliverGoogleUnlinked(any(), anyString())).thenReturn(EmailService.ActivationDelivery.ACCEPTED);
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
        jdbc.update("DELETE FROM auth.activation_request_limits");
    }

    private String loginPassword(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .header("X-Client-Platform", "ANDROID")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .path("data").path("jwtResponse").path("accessToken").asText();
    }

    private String issueAuthenticatedChallenge(String jwt, String purpose) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/google/authenticated-challenge")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("purpose", purpose))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nonce").isString())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("nonce").asText();
    }

    private static String sha256Hex(String raw) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
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

    @Test
    void coversGoogleEdgeCasesLinkUnlinkValidationReauthMismatchAndUsernameSuggestion() throws Exception {
        mvc.perform(get("/api/auth/google/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Unverified email user with blank avatar logs in via Google and auto-populates both + replay fails on nonce
        String email = TEST_EMAIL_PREFIX + "edge1@example.com";
        String sub = "sub-edge-1";
        User edgeUser = createGoogleUser(email, "edge.user", sub);
        edgeUser.recordEmailVerification(null);
        edgeUser.setAvatarUrl("");
        users.saveAndFlush(edgeUser);

        String authNonce = issueChallenge("AUTH");
        String authId = "header.edge.auth." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(authId), eq(authNonce))).thenReturn(
                new VerifiedGoogleIdentity(sub, email, true, "Ana", "Gómez", "Ana Gómez", "https://lh3.googleusercontent.com/edge", Instant.now().plusSeconds(300)));

        MvcResult edgeAuthRes = mvc.perform(post("/api/auth/google/authenticate")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", authId, "nonce", authNonce))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("AUTHENTICATED"))
                .andReturn();
        String edgeJwt = json.readTree(edgeAuthRes.getResponse().getContentAsString())
                .path("data").path("jwtResponse").path("accessToken").asText();

        User synced = users.findById(edgeUser.getId()).orElseThrow();
        assertThat(synced.getAvatarUrl()).isEqualTo("https://lh3.googleusercontent.com/edge");
        assertThat(synced.isEmailVerified()).isTrue();

        // Reusing same nonce fails with 401
        mvc.perform(post("/api/auth/google/authenticate")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", authId, "nonce", authNonce))))
                .andExpect(status().isUnauthorized());

        // Suggest username fallback on short names & collision + malformed registration token
        createPasswordUser(TEST_EMAIL_PREFIX + "collide@example.com", "usuario.12", "Pass!123456");
        String regNonce = issueChallenge("AUTH");
        String regId = "header.edge.reg." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(regId), eq(regNonce))).thenReturn(
                new VerifiedGoogleIdentity("sub-short-!@#", TEST_EMAIL_PREFIX + "newshort@example.com", true, "__", "--", null, null, Instant.now().plusSeconds(300)));
        mvc.perform(post("/api/auth/google/authenticate")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", regId, "nonce", regNonce))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("REGISTRATION_REQUIRED"))
                .andExpect(jsonPath("$.data.profile.suggestedUsername").isString());

        // Malformed registration token -> 400
        mvc.perform(post("/api/auth/google/complete-registration")
                        .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "registrationToken", "bad",
                                "username", "valid.user9",
                                "firstName", "Ana",
                                "lastName", "Gomez",
                                "legalAcceptance", Map.of("termsAccepted", true, "privacyAccepted", true)))))
                .andExpect(status().isBadRequest());

        // Reauthenticate with different Google subject -> 403
        String mismatchNonce = issueChallenge("REAUTH");
        String mismatchId = "header.edge.mismatch." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(mismatchId), eq(mismatchNonce))).thenReturn(
                new VerifiedGoogleIdentity("sub-other-person", email, true, "Other", "Person", null, null, Instant.now().plusSeconds(300)));
        mvc.perform(post("/api/auth/google/reauthenticate")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", mismatchId, "nonce", mismatchNonce, "purpose", "UNLINK_GOOGLE"))))
                .andExpect(status().isForbidden());

        // Unlink without password -> 409, unconfirmed -> 400
        mvc.perform(post("/api/auth/google/unlink")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmed", false))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/google/unlink")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmed", true, "reauthToken", "unused"))))
                .andExpect(status().isConflict());

        // Configure local password for edgeUser using valid Google reauthToken, then test unlink error paths and link error paths
        String setPwdNonce = issueChallenge("REAUTH");
        String setPwdId = "header.edge.setpwd." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(setPwdId), eq(setPwdNonce))).thenReturn(
                new VerifiedGoogleIdentity(sub, email, true, "Ana", "Gómez", null, null, Instant.now().plusSeconds(300)));
        MvcResult rSetPwd = mvc.perform(post("/api/auth/google/reauthenticate")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", setPwdId, "nonce", setPwdNonce, "purpose", "SET_INITIAL_PASSWORD"))))
                .andExpect(status().isOk())
                .andReturn();
        String setPwdReauth = json.readTree(rSetPwd.getResponse().getContentAsString()).path("data").path("reauthToken").asText();

        mvc.perform(post("/api/users/set-initial-password")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("newPassword", "EdgePass!2026", "reauthToken", setPwdReauth))))
                .andExpect(status().isOk());

        MvcResult reloginRes = mvc.perform(post("/api/auth/login")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "EdgePass!2026"))))
                .andExpect(status().isOk())
                .andReturn();
        edgeJwt = json.readTree(reloginRes.getResponse().getContentAsString())
                .path("data").path("jwtResponse").path("accessToken").asText();

        // Link when unconfirmed -> 400; wrong password -> 400; link when already linked -> 409
        mvc.perform(post("/api/auth/google/link")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", "a.b.c", "nonce", "valid-nonce-1234567890", "currentPassword", "EdgePass!2026", "confirmed", false))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/google/link")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", "a.b.c", "nonce", "valid-nonce-1234567890", "currentPassword", "WrongPass!", "confirmed", true))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/google/link")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", "a.b.c", "nonce", "valid-nonce-1234567890", "currentPassword", "EdgePass!2026", "confirmed", true))))
                .andExpect(status().isConflict());

        // Unlink missing verification -> 400, wrong password -> 400, valid Google reauthToken -> 200
        mvc.perform(post("/api/auth/google/unlink")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmed", true))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/google/unlink")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmed", true, "currentPassword", "WrongPass!"))))
                .andExpect(status().isBadRequest());

        String unlinkNonce = issueChallenge("REAUTH");
        String unlinkId = "header.edge.unlink." + SEQ.incrementAndGet();
        when(googleVerifier.verify(eq(unlinkId), eq(unlinkNonce))).thenReturn(
                new VerifiedGoogleIdentity(sub, email, true, "Ana", "Gómez", null, null, Instant.now().plusSeconds(300)));
        MvcResult rUnlink = mvc.perform(post("/api/auth/google/reauthenticate")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", unlinkId, "nonce", unlinkNonce, "purpose", "UNLINK_GOOGLE"))))
                .andExpect(status().isOk())
                .andReturn();
        String unlinkReauth = json.readTree(rUnlink.getResponse().getContentAsString()).path("data").path("reauthToken").asText();

        mvc.perform(post("/api/auth/google/unlink")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmed", true, "reauthToken", unlinkReauth))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.googleLinked").value(false));

        // Second unlink when no link remains -> 404
        mvc.perform(post("/api/auth/google/unlink")
                        .header("Authorization", "Bearer " + edgeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmed", true, "currentPassword", "EdgePass!2026"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void twoStepLinkingWithDifferentUnusedGoogleEmailPreservesPrimaryEmailEnablesDualLoginAndSendsSecurityMail() throws Exception {
        String localEmail = TEST_EMAIL_PREFIX + "local.a@example.com";
        String googleEmail = TEST_EMAIL_PREFIX + "external.google.b@gmail.com";
        String sub = "sub-two-step-a-" + SEQ.incrementAndGet();
        String pass = "TwoStep!2026";
        User userA = createPasswordUser(localEmail, "twostep.a", pass);
        long usersBeforeLink = users.count();
        String originalHash = userA.getPassword();

        String jwt = loginPassword(localEmail, pass);
        String nonce = issueAuthenticatedChallenge(jwt, "LINK");
        String idToken = "header.twostep.ok." + SEQ.incrementAndGet();
        when(googleVerifier.verify(idToken, nonce)).thenReturn(
                new VerifiedGoogleIdentity(sub, googleEmail, true, "Iván", "Castillo", "Iván Castillo",
                        "https://lh3.googleusercontent.com/avatar-a", Instant.now().plusSeconds(300)));

        MvcResult prepRes = mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", idToken,
                                "nonce", nonce,
                                "currentPassword", pass))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationToken").isString())
                .andExpect(jsonPath("$.data.currentAccountEmail").value(localEmail))
                .andExpect(jsonPath("$.data.googleEmail").value(googleEmail))
                .andExpect(jsonPath("$.data.googleEmailVerified").value(true))
                .andExpect(jsonPath("$.data.sameEmail").value(false))
                .andReturn();

        String confirmToken = json.readTree(prepRes.getResponse().getContentAsString())
                .path("data").path("confirmationToken").asText();

        // Step 1 must NOT persist external_identities yet or store raw tokens in DB
        assertThat(externalIdentities.existsByUserIdAndProvider(userA.getId(), "GOOGLE")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth.google_auth_flows WHERE flow_type = 'LINK_CONFIRM' AND user_id = ? AND token_hash = ? AND consumed_at IS NULL",
                Integer.class, userA.getId(), sha256Hex(confirmToken))).isEqualTo(1);

        // Step 2 without confirmed=true is rejected 400 and leaves confirmation pending
        mvc.perform(post("/api/auth/google/link/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmationToken", confirmToken, "confirmed", false))))
                .andExpect(status().isBadRequest());

        // Step 2 confirmed persists link without changing primary email, password or user count
        mvc.perform(post("/api/auth/google/link/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmationToken", confirmToken, "confirmed", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.googleLinked").value(true))
                .andExpect(jsonPath("$.data.googleEmail").value(googleEmail))
                .andExpect(jsonPath("$.data.canUnlinkGoogle").value(true));

        User reloaded = users.findById(userA.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo(localEmail);
        assertThat(reloaded.getPassword()).isEqualTo(originalHash);
        assertThat(users.count()).isEqualTo(usersBeforeLink);
        assertThat(externalIdentities.findByUserIdAndProvider(userA.getId(), "GOOGLE"))
                .isPresent()
                .get()
                .satisfies(ext -> {
                    assertThat(ext.getProviderSubject()).isEqualTo(sub);
                    assertThat(ext.getProviderEmail()).isEqualTo(googleEmail);
                });

        // Reusing confirmationToken fails
        mvc.perform(post("/api/auth/google/link/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmationToken", confirmToken, "confirmed", true))))
                .andExpect(status().isBadRequest());

        // Security email queued & dispatched to primary email without secrets
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth.security_mail_outbox WHERE user_id = ? AND purpose = 'GOOGLE_LINKED' AND state = 'PENDING'",
                Integer.class, userA.getId())).isEqualTo(1);
        securityMailQueue.dispatchPending();
        verify(emailService).deliverGoogleLinked(argThat(u -> localEmail.equals(u.getEmail())), anyString());

        // Both traditional login and Google login resolve to the exact same user_id
        mvc.perform(post("/api/auth/login")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", localEmail, "password", pass))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jwtResponse.user.id").value(userA.getId()));

        JsonNode googleLogin = authenticateGoogle(
                new VerifiedGoogleIdentity(sub, googleEmail, true, "Iván", "Castillo", null, null, Instant.now().plusSeconds(300)),
                "ANDROID");
        assertThat(googleLogin.path("outcome").asText()).isEqualTo("AUTHENTICATED");
        assertThat(googleLogin.path("jwtResponse").path("user").path("id").asLong()).isEqualTo(userA.getId());
    }

    @Test
    void twoStepLinkingSupportsSameEmailAndCancellationLeavesDatabaseUnchanged() throws Exception {
        String email = TEST_EMAIL_PREFIX + "same.cancel@example.com";
        String pass = "SameCancel!2026";
        User user = createPasswordUser(email, "same.cancel", pass);
        long usersBefore = users.count();
        String jwt = loginPassword(email, pass);

        // 1. Prepare then cancel leaves zero external_identities and invalidates confirmation token
        String cancelNonce = issueAuthenticatedChallenge(jwt, "LINK");
        String cancelIdToken = "header.cancel.ok." + SEQ.incrementAndGet();
        when(googleVerifier.verify(cancelIdToken, cancelNonce)).thenReturn(
                new VerifiedGoogleIdentity("sub-cancel-1", email, true, "Ana", "Paz", null, null, Instant.now().plusSeconds(300)));

        MvcResult prepCancel = mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", cancelIdToken,
                                "nonce", cancelNonce,
                                "currentPassword", pass))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sameEmail").value(true))
                .andReturn();
        String cancelledToken = json.readTree(prepCancel.getResponse().getContentAsString())
                .path("data").path("confirmationToken").asText();

        mvc.perform(post("/api/auth/google/link/cancel")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmationToken", cancelledToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelled").value(true));

        assertThat(externalIdentities.existsByUserIdAndProvider(user.getId(), "GOOGLE")).isFalse();
        mvc.perform(post("/api/auth/google/link/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmationToken", cancelledToken, "confirmed", true))))
                .andExpect(status().isBadRequest());

        // 2. Re-prepare with fresh token and confirm same-email Google identity
        String okNonce = issueAuthenticatedChallenge(jwt, "LINK");
        String okIdToken = "header.same.ok." + SEQ.incrementAndGet();
        when(googleVerifier.verify(okIdToken, okNonce)).thenReturn(
                new VerifiedGoogleIdentity("sub-same-ok-1", email, true, "Ana", "Paz", null, null, Instant.now().plusSeconds(300)));

        MvcResult prepOk = mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", okIdToken,
                                "nonce", okNonce,
                                "currentPassword", pass))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sameEmail").value(true))
                .andReturn();
        String okToken = json.readTree(prepOk.getResponse().getContentAsString())
                .path("data").path("confirmationToken").asText();

        mvc.perform(post("/api/auth/google/link/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmationToken", okToken, "confirmed", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.googleLinked").value(true))
                .andExpect(jsonPath("$.data.googleEmail").value(email));
        assertThat(users.count()).isEqualTo(usersBefore);
        assertThat(users.findByEmailIgnoreCase(email)).contains(user);

        // Case D: same user attempting to prepare link again with same or another Google identity gets 409
        String dupNonce = issueAuthenticatedChallenge(jwt, "LINK");
        mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", "header.case.d." + SEQ.incrementAndGet(),
                                "nonce", dupNonce,
                                "currentPassword", pass))))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsCaseBGoogleEmailBelongingToAnotherLocalUserAcrossPrepareConfirmAndLegacyLink() throws Exception {
        String emailA = TEST_EMAIL_PREFIX + "linker.a@example.com";
        String emailB = TEST_EMAIL_PREFIX + "victim.b@example.com";
        String passA = "PassA!2026";
        String passB = "PassB!2026";
        User userA = createPasswordUser(emailA, "linker.a", passA);
        User userB = createPasswordUser(emailB, "victim.b", passB);
        String hashB = userB.getPassword();
        long initialUsers = users.count();

        String jwtA = loginPassword(emailA, passA);

        // 1. Two-step prepare rejects Case B with 409 and reveals no info about userB
        String boundNonce = issueAuthenticatedChallenge(jwtA, "LINK");
        String tok1 = "header.caseb.prep." + SEQ.incrementAndGet();
        when(googleVerifier.verify(tok1, boundNonce)).thenReturn(
                new VerifiedGoogleIdentity("sub-fresh-caseb-1", emailB.toUpperCase(java.util.Locale.ROOT), true,
                        "B", "Owner", null, null, Instant.now().plusSeconds(300)));

        MvcResult prepConflict = mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", tok1, "nonce", boundNonce, "currentPassword", passA))))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(prepConflict.getResponse().getContentAsString()).doesNotContain("victim.b");
        assertThat(externalIdentities.existsByUserIdAndProvider(userA.getId(), "GOOGLE")).isFalse();
        assertThat(externalIdentities.existsByUserIdAndProvider(userB.getId(), "GOOGLE")).isFalse();

        // 1b. Case C on /link/prepare: Google sub already linked to another user -> 409
        createGoogleUser(TEST_EMAIL_PREFIX + "holder.c@example.com", "holder.c", "sub-casec-owned");
        String caseCNonce = issueAuthenticatedChallenge(jwtA, "LINK");
        String tokCaseC = "header.casec.prep." + SEQ.incrementAndGet();
        when(googleVerifier.verify(tokCaseC, caseCNonce)).thenReturn(
                new VerifiedGoogleIdentity("sub-casec-owned", TEST_EMAIL_PREFIX + "alias.c@gmail.com", true,
                        "C", "Holder", null, null, Instant.now().plusSeconds(300)));
        mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", tokCaseC, "nonce", caseCNonce, "currentPassword", passA))))
                .andExpect(status().isConflict());

        // 2. Legacy v1.3 /link also rejects Case B with 409
        String legacyNonce = issueChallenge("LINK");
        String tok2 = "header.caseb.leg." + SEQ.incrementAndGet();
        when(googleVerifier.verify(tok2, legacyNonce)).thenReturn(
                new VerifiedGoogleIdentity("sub-fresh-caseb-2", emailB, true, "B", "Owner", null, null, Instant.now().plusSeconds(300)));

        mvc.perform(post("/api/auth/google/link")
                        .header("Authorization", "Bearer " + jwtA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", tok2,
                                "nonce", legacyNonce,
                                "currentPassword", passA,
                                "confirmed", true))))
                .andExpect(status().isConflict());

        // 3. Late collision between prepare and confirm (user registers with emailB after prepare) -> confirm rejects 409
        String lateEmail = TEST_EMAIL_PREFIX + "late.owner@example.com";
        String lateNonce = issueAuthenticatedChallenge(jwtA, "LINK");
        String tok3 = "header.caseb.late." + SEQ.incrementAndGet();
        when(googleVerifier.verify(tok3, lateNonce)).thenReturn(
                new VerifiedGoogleIdentity("sub-late-caseb-3", lateEmail, true, "Late", "Owner", null, null, Instant.now().plusSeconds(300)));

        MvcResult prepLate = mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", tok3, "nonce", lateNonce, "currentPassword", passA))))
                .andExpect(status().isOk())
                .andReturn();
        String lateConfirmToken = json.readTree(prepLate.getResponse().getContentAsString())
                .path("data").path("confirmationToken").asText();

        createPasswordUser(lateEmail, "late.owner", "LatePass!2026");

        mvc.perform(post("/api/auth/google/link/confirm")
                        .header("Authorization", "Bearer " + jwtA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmationToken", lateConfirmToken, "confirmed", true))))
                .andExpect(status().isConflict());

        assertThat(users.count()).isEqualTo(initialUsers + 2);
        assertThat(users.findById(userA.getId()).orElseThrow().getEmail()).isEqualTo(emailA);
        assertThat(users.findById(userB.getId()).orElseThrow().getPassword()).isEqualTo(hashB);
        assertThat(externalIdentities.existsByUserIdAndProvider(userA.getId(), "GOOGLE")).isFalse();
    }

    @Test
    void enforcesNoncePurposeUserSessionBindingExpiryAndMfaOnPrepareAndConfirm() throws Exception {
        String emailA = TEST_EMAIL_PREFIX + "sec.a@example.com";
        String emailB = TEST_EMAIL_PREFIX + "sec.b@example.com";
        String pass = "SecBind!2026";
        User userA = createPasswordUser(emailA, "sec.a", pass);
        String totpSecret = "JBSWY3DPEHPK3PXP";
        userA.setGoogleAuthSecret(totpSecret);
        userA.setGoogleAuthEnabled(true);
        userA.setTwoFactorEnabled(true);
        users.saveAndFlush(userA);
        User userB = createPasswordUser(emailB, "sec.b", pass);

        // Login userA across TWO distinct sessions
        Instant totpRef = Instant.now();
        MvcResult loginRes1 = mvc.perform(post("/api/auth/login")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", emailA, "password", pass))))
                .andExpect(status().isOk())
                .andReturn();
        String pre1 = json.readTree(loginRes1.getResponse().getContentAsString()).path("data").path("preAuthChallenge").asText();
        String code1 = String.format("%06d",
                new GoogleAuthenticator().getTotpPassword(totpSecret, totpRef.minusSeconds(30).toEpochMilli()));
        String jwtA1 = json.readTree(mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("preAuthChallenge", pre1, "method", "GOOGLE_AUTHENTICATOR", "code", code1))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("accessToken").asText();

        MvcResult loginRes2 = mvc.perform(post("/api/auth/login")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", emailA, "password", pass))))
                .andExpect(status().isOk())
                .andReturn();
        String pre2 = json.readTree(loginRes2.getResponse().getContentAsString()).path("data").path("preAuthChallenge").asText();
        String code2 = String.format("%06d",
                new GoogleAuthenticator().getTotpPassword(totpSecret, totpRef.toEpochMilli()));
        String jwtA2 = json.readTree(mvc.perform(post("/api/2fa/verify")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("preAuthChallenge", pre2, "method", "GOOGLE_AUTHENTICATOR", "code", code2))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("accessToken").asText();

        String jwtB = loginPassword(emailB, pass);

        // Unauthenticated cannot call /authenticated-challenge
        mvc.perform(post("/api/auth/google/authenticated-challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"LINK\"}"))
                .andExpect(status().isUnauthorized());

        // Cross-purpose AUTH nonce rejected on /link/prepare & REAUTH nonce rejected on /link/prepare
        String authNonce = issueChallenge("AUTH");
        String reauthBoundNonce = issueAuthenticatedChallenge(jwtA1, "REAUTH");
        String validTotp = String.format("%06d",
                new GoogleAuthenticator().getTotpPassword(totpSecret, totpRef.plusSeconds(30).toEpochMilli()));

        // Wrong MFA code rejected 400
        String linkNonceA1 = issueAuthenticatedChallenge(jwtA1, "LINK");
        mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", "header.mfa.bad",
                                "nonce", linkNonceA1,
                                "currentPassword", pass,
                                "mfaMethod", "GOOGLE_AUTHENTICATOR",
                                "mfaCode", "000000".equals(validTotp) ? "999999" : "000000"))))
                .andExpect(status().isBadRequest());

        // Unbound /challenge(LINK) rejected on /link/prepare (even during legacy transition)
        String unboundLegacyNonce = issueChallenge("LINK");
        mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", "header.unbound",
                                "nonce", unboundLegacyNonce,
                                "currentPassword", pass,
                                "mfaMethod", "GOOGLE_AUTHENTICATOR",
                                "mfaCode", validTotp))))
                .andExpect(status().isUnauthorized());

        // AUTH nonce & REAUTH nonce rejected on /link/prepare
        mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", "header.cross.auth",
                                "nonce", authNonce,
                                "currentPassword", pass,
                                "mfaMethod", "GOOGLE_AUTHENTICATOR",
                                "mfaCode", validTotp))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", "header.cross.reauth",
                                "nonce", reauthBoundNonce,
                                "currentPassword", pass,
                                "mfaMethod", "GOOGLE_AUTHENTICATOR",
                                "mfaCode", validTotp))))
                .andExpect(status().isUnauthorized());

        // User B cannot consume User A's bound nonce; User A's Session 2 cannot consume Session 1's bound nonce
        mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", "header.cross.user",
                                "nonce", linkNonceA1,
                                "currentPassword", pass))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtA2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", "header.cross.session",
                                "nonce", linkNonceA1,
                                "currentPassword", pass,
                                "mfaMethod", "GOOGLE_AUTHENTICATOR",
                                "mfaCode", validTotp))))
                .andExpect(status().isUnauthorized());

        // Expired nonce rejected 401
        String expiredNonce = issueAuthenticatedChallenge(jwtA1, "LINK");
        jdbc.update("UPDATE auth.google_auth_flows SET expires_at = ? WHERE token_hash = ?",
                LocalDateTime.now().minusMinutes(1), sha256Hex(expiredNonce));
        mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", "header.expired",
                                "nonce", expiredNonce,
                                "currentPassword", pass,
                                "mfaMethod", "GOOGLE_AUTHENTICATOR",
                                "mfaCode", validTotp))))
                .andExpect(status().isUnauthorized());

        // User A's original Session 1 CAN still use linkNonceA1 to prepare!
        String okToken = "header.prepare.a1." + SEQ.incrementAndGet();
        when(googleVerifier.verify(okToken, linkNonceA1)).thenReturn(
                new VerifiedGoogleIdentity("sub-bind-a1", TEST_EMAIL_PREFIX + "newbind@gmail.com", true,
                        "Sec", "A", null, null, Instant.now().plusSeconds(300)));
        MvcResult prepA1 = mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", okToken,
                                "nonce", linkNonceA1,
                                "currentPassword", pass,
                                "mfaMethod", "GOOGLE_AUTHENTICATOR",
                                "mfaCode", validTotp))))
                .andExpect(status().isOk())
                .andReturn();
        String confirmTokenA1 = json.readTree(prepA1.getResponse().getContentAsString())
                .path("data").path("confirmationToken").asText();

        // User B & User A's Session 2 cannot confirm using Session 1's confirmationToken
        mvc.perform(post("/api/auth/google/link/confirm")
                        .header("Authorization", "Bearer " + jwtB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmationToken", confirmTokenA1, "confirmed", true))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/google/link/confirm")
                        .header("Authorization", "Bearer " + jwtA2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmationToken", confirmTokenA1, "confirmed", true))))
                .andExpect(status().isBadRequest());
        assertThat(externalIdentities.existsByUserIdAndProvider(userA.getId(), "GOOGLE")).isFalse();
        assertThat(externalIdentities.existsByUserIdAndProvider(userB.getId(), "GOOGLE")).isFalse();

        // Session 1 confirms successfully
        mvc.perform(post("/api/auth/google/link/confirm")
                        .header("Authorization", "Bearer " + jwtA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmationToken", confirmTokenA1, "confirmed", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.googleLinked").value(true));
    }

    @Test
    void concurrentConfirmAttemptsProduceSingleWinnerAndNoDuplicatesAcrossSameTokenOrSameGoogleSubject() throws Exception {
        String emailA = TEST_EMAIL_PREFIX + "conc.a@example.com";
        String emailB = TEST_EMAIL_PREFIX + "conc.b@example.com";
        String pass = "Concurrent!2026";
        createPasswordUser(emailA, "conc.a", pass);
        createPasswordUser(emailB, "conc.b", pass);

        String jwtA = loginPassword(emailA, pass);
        String jwtB = loginPassword(emailB, pass);

        // 1. Two users prepare different idTokens for the SAME Google subject concurrently, then race on confirm
        String sharedSub = "sub-concurrent-shared-" + SEQ.incrementAndGet();
        String sharedGoogleEmail = TEST_EMAIL_PREFIX + "shared.g@gmail.com";

        String nonceA = issueAuthenticatedChallenge(jwtA, "LINK");
        String tokA = "header.conc.a." + SEQ.incrementAndGet();
        when(googleVerifier.verify(tokA, nonceA)).thenReturn(
                new VerifiedGoogleIdentity(sharedSub, sharedGoogleEmail, true, "C", "A", null, null, Instant.now().plusSeconds(300)));
        String confirmA = json.readTree(mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", tokA, "nonce", nonceA, "currentPassword", pass))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("confirmationToken").asText();

        String nonceB = issueAuthenticatedChallenge(jwtB, "LINK");
        String tokB = "header.conc.b." + SEQ.incrementAndGet();
        when(googleVerifier.verify(tokB, nonceB)).thenReturn(
                new VerifiedGoogleIdentity(sharedSub, sharedGoogleEmail, true, "C", "B", null, null, Instant.now().plusSeconds(300)));
        String confirmB = json.readTree(mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", tokB, "nonce", nonceB, "currentPassword", pass))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("confirmationToken").asText();

        var pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> f1 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return mvc.perform(post("/api/auth/google/link/confirm")
                                .header("Authorization", "Bearer " + jwtA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("confirmationToken", confirmA, "confirmed", true))))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> f2 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return mvc.perform(post("/api/auth/google/link/confirm")
                                .header("Authorization", "Bearer " + jwtB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("confirmationToken", confirmB, "confirmed", true))))
                        .andReturn().getResponse().getStatus();
            });
            ready.await();
            go.countDown();
            List<Integer> statuses = List.of(f1.get(), f2.get());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM auth.external_identities WHERE provider = 'GOOGLE' AND provider_subject = ?",
                    Integer.class, sharedSub)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // 2. Two concurrent confirm calls by the SAME user with the SAME confirmationToken
        User userC = createPasswordUser(TEST_EMAIL_PREFIX + "conc.c@example.com", "conc.c", pass);
        String jwtC = loginPassword(userC.getEmail(), pass);
        String nonceC = issueAuthenticatedChallenge(jwtC, "LINK");
        String tokC = "header.conc.c." + SEQ.incrementAndGet();
        when(googleVerifier.verify(tokC, nonceC)).thenReturn(
                new VerifiedGoogleIdentity("sub-conc-c-" + SEQ.incrementAndGet(), TEST_EMAIL_PREFIX + "conc.cg@gmail.com",
                        true, "C", "C", null, null, Instant.now().plusSeconds(300)));
        String confirmC = json.readTree(mvc.perform(post("/api/auth/google/link/prepare")
                        .header("Authorization", "Bearer " + jwtC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", tokC, "nonce", nonceC, "currentPassword", pass))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("confirmationToken").asText();

        var pool2 = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> c1 = pool2.submit(() -> {
                ready.countDown();
                go.await();
                return mvc.perform(post("/api/auth/google/link/confirm")
                                .header("Authorization", "Bearer " + jwtC)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("confirmationToken", confirmC, "confirmed", true))))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> c2 = pool2.submit(() -> {
                ready.countDown();
                go.await();
                return mvc.perform(post("/api/auth/google/link/confirm")
                                .header("Authorization", "Bearer " + jwtC)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("confirmationToken", confirmC, "confirmed", true))))
                        .andReturn().getResponse().getStatus();
            });
            ready.await();
            go.countDown();
            List<Integer> statuses = List.of(c1.get(), c2.get());
            assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == 400).count()).isEqualTo(1);
            assertThat(externalIdentities.existsByUserIdAndProvider(userC.getId(), "GOOGLE")).isTrue();
        } finally {
            pool2.shutdownNow();
        }
    }

    @Test
    void preservesPublishedAndroid13LegacyFlowDuringTransitionBlocksWhenDisabledAndToleratesMailFailure() throws Exception {
        String email = TEST_EMAIL_PREFIX + "legacy.v13@example.com";
        String pass = "LegacyV13!2026";
        User user = createPasswordUser(email, "legacy.v13", pass);
        String jwt = loginPassword(email, pass);

        // Simulate Brevo failure during link and unlink: transaction & persistence must succeed
        when(emailService.deliverGoogleLinked(any(), anyString())).thenReturn(EmailService.ActivationDelivery.UNKNOWN);
        when(emailService.deliverGoogleUnlinked(any(), anyString())).thenReturn(EmailService.ActivationDelivery.FAILED);

        // Published Android v1.3 calls public /challenge(LINK) + /link
        String legacyLinkNonce = issueChallenge("LINK");
        String legacyLinkToken = "header.v13.link." + SEQ.incrementAndGet();
        when(googleVerifier.verify(legacyLinkToken, legacyLinkNonce)).thenReturn(
                new VerifiedGoogleIdentity("sub-v13-legacy-1", TEST_EMAIL_PREFIX + "v13.g@gmail.com",
                        true, "V13", "Tester", null, null, Instant.now().plusSeconds(300)));

        mvc.perform(post("/api/auth/google/link")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", legacyLinkToken,
                                "nonce", legacyLinkNonce,
                                "currentPassword", pass,
                                "confirmed", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.googleLinked").value(true));

        securityMailQueue.dispatchPending();
        assertThat(externalIdentities.existsByUserIdAndProvider(user.getId(), "GOOGLE")).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM auth.security_mail_outbox WHERE user_id = ? AND purpose = 'GOOGLE_LINKED'",
                String.class, user.getId())).isEqualTo("UNKNOWN");

        // Published Android v1.3 calls public /challenge(REAUTH) + /reauthenticate + /unlink
        String legacyReauthNonce = issueChallenge("REAUTH");
        String legacyReauthToken = "header.v13.reauth." + SEQ.incrementAndGet();
        when(googleVerifier.verify(legacyReauthToken, legacyReauthNonce)).thenReturn(
                new VerifiedGoogleIdentity("sub-v13-legacy-1", TEST_EMAIL_PREFIX + "v13.g@gmail.com",
                        true, "V13", "Tester", null, null, Instant.now().plusSeconds(300)));
        String reauthToken = json.readTree(mvc.perform(post("/api/auth/google/reauthenticate")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idToken", legacyReauthToken,
                                "nonce", legacyReauthNonce,
                                "purpose", "UNLINK_GOOGLE"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("reauthToken").asText();

        mvc.perform(post("/api/auth/google/unlink")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reauthToken", reauthToken, "confirmed", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.googleLinked").value(false));

        securityMailQueue.dispatchPending();
        assertThat(externalIdentities.existsByUserIdAndProvider(user.getId(), "GOOGLE")).isFalse();
        verify(emailService).deliverGoogleUnlinked(argThat(u -> email.equals(u.getEmail())), anyString());

        // When transition flag is turned off, public /challenge rejects LINK/REAUTH with 401
        googleAuthProperties.setAllowLegacyUnauthenticatedChallenge(false);
        try {
            mvc.perform(post("/api/auth/google/challenge")
                            .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"purpose\":\"LINK\"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/auth/google/challenge")
                            .with(req -> { req.setRemoteAddr(remoteIp()); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"purpose\":\"REAUTH\"}"))
                    .andExpect(status().isUnauthorized());
            // AUTH continues working
            assertThat(issueChallenge("AUTH")).isNotBlank();
        } finally {
            googleAuthProperties.setAllowLegacyUnauthenticatedChallenge(true);
        }
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
