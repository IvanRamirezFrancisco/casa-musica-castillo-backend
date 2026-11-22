package com.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.config.GoogleAuthProperties;
import com.security.dto.request.CancelLinkGoogleRequest;
import com.security.dto.request.ConfirmLinkGoogleRequest;
import com.security.dto.request.GoogleReauthRequest;
import com.security.dto.request.LinkGoogleRequest;
import com.security.dto.request.PrepareLinkGoogleRequest;
import com.security.dto.request.UnlinkGoogleRequest;
import com.security.entity.ExternalIdentity;
import com.security.entity.RefreshToken;
import com.security.entity.User;
import com.security.exception.AuthFlowException;
import com.security.repository.ExternalIdentityRepository;
import com.security.repository.RoleRepository;
import com.security.repository.UserRepository;
import com.security.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.client.RestTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleAuthUnitCoverageTest {

    private static PlatformTransactionManager immediateTxManager() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return tx;
    }

    @Test
    void googleAuthPropertiesNormalizeClientsAndConfiguredState() {
        GoogleAuthProperties props = new GoogleAuthProperties();
        assertThat(props.isConfigured()).isFalse();

        props.setWebClientId(null);
        assertThat(props.getWebClientId()).isEmpty();

        props.setAllowedClientIds(null);
        assertThat(props.getAllowedClientIds()).isEmpty();

        props.setWebClientId("  web-id-1  ");
        props.setAllowedClientIds(List.of(" android-id-1 , android-id-2 ", " ", "web-id-1"));
        props.setJwkSetUri("https://www.googleapis.com/oauth2/v3/certs");
        props.setClockSkewSeconds(45);
        props.setChallengeTtlSeconds(240);
        props.setRegistrationTtlSeconds(480);
        props.setReauthTtlSeconds(180);
        props.setLinkConfirmTtlSeconds(210);
        props.setAllowLegacyUnauthenticatedChallenge(false);

        assertThat(props.isConfigured()).isTrue();
        assertThat(props.getEffectiveAllowedClientIds()).containsExactlyInAnyOrder("web-id-1", "android-id-1", "android-id-2");
        assertThat(props.getClockSkewSeconds()).isEqualTo(45);
        assertThat(props.getChallengeTtlSeconds()).isEqualTo(240);
        assertThat(props.getRegistrationTtlSeconds()).isEqualTo(480);
        assertThat(props.getReauthTtlSeconds()).isEqualTo(180);
        assertThat(props.getLinkConfirmTtlSeconds()).isEqualTo(210);
        assertThat(props.isAllowLegacyUnauthenticatedChallenge()).isFalse();
    }

    @Test
    void googleLinkDtosRedactSensitiveFieldsAndSecurityMailContentIncludesTimestampWithoutSecrets() {
        var prepareReq = new PrepareLinkGoogleRequest("secret-id-token", "secret-nonce-12345678", "SuperSecret!1", "EMAIL", "654321");
        var confirmReq = new ConfirmLinkGoogleRequest("opaque-confirm-token-1234567890", true);
        var cancelReq = new CancelLinkGoogleRequest("opaque-confirm-token-1234567890");

        assertThat(prepareReq).hasToString("PrepareLinkGoogleRequest[redacted]");
        assertThat(confirmReq).hasToString("ConfirmLinkGoogleRequest[redacted]");
        assertThat(cancelReq).hasToString("CancelLinkGoogleRequest[redacted]");

        AuthMailContent linked = AuthMailContent.googleLinked("Iván", "22/11/2026 13:15 (hora del centro de México)");
        assertThat(linked.html()).contains("22/11/2026 13:15").contains("Tu correo electrónico principal no cambió");
        assertThat(linked.text()).contains("Tu correo electrónico principal no cambió");

        AuthMailContent linkedFallback = AuthMailContent.googleLinked(null, null);
        assertThat(linkedFallback.html()).contains("recientemente");
        assertThat(AuthMailContent.googleLinked("  ", "   ").text()).contains("recientemente");

        AuthMailContent unlinked = AuthMailContent.googleUnlinked("Iván", "22/11/2026 13:20 (hora del centro de México)");
        assertThat(unlinked.html()).contains("22/11/2026 13:20").contains("desvincul&oacute; Google");
        assertThat(unlinked.text()).contains("desvinculó Google").contains("correo y contraseña local");

        AuthMailContent unlinkedFallback = AuthMailContent.googleUnlinked(null, null);
        assertThat(unlinkedFallback.html()).contains("recientemente");
        assertThat(AuthMailContent.googleUnlinked(" ", " ").text()).contains("recientemente");
    }

    @Test
    void emailServiceAndSecurityMailQueueDeliverAndEnqueueGoogleLinkNotifications() {
        EmailService service = new EmailService();
        JavaMailSender smtp = mock(JavaMailSender.class);
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer http = MockRestServiceServer.bindTo(rest).build();
        ReflectionTestUtils.setField(service, "mailSender", smtp);
        ReflectionTestUtils.setField(service, "restTemplate", rest);
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:4200/");
        ReflectionTestUtils.setField(service, "senderName", "Casa de Música Castillo");
        ReflectionTestUtils.setField(service, "senderAddress", "sender@example.invalid");
        ReflectionTestUtils.setField(service, "brevoApiKey", "synthetic-brevo-key");

        User user = new User();
        user.setId(99L);
        user.setEmail("recipient@example.invalid");
        user.setFirstName(null);

        http.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"messageId\":\"m-1\"}", MediaType.APPLICATION_JSON));
        http.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"messageId\":\"m-2\"}", MediaType.APPLICATION_JSON));

        assertThat(service.deliverGoogleLinked(user, "22/11/2026 14:00")).isEqualTo(EmailService.ActivationDelivery.ACCEPTED);
        assertThat(service.deliverGoogleUnlinked(user, "22/11/2026 14:05")).isEqualTo(EmailService.ActivationDelivery.ACCEPTED);
        http.verify();

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ActivationCrypto crypto = mock(ActivationCrypto.class);
        when(crypto.seal(anyString(), anyString())).thenReturn("sealed-payload");
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-22T20:00:00Z"), ZoneOffset.UTC);
        SecurityMailQueue queue = new SecurityMailQueue(
                jdbc,
                mock(UserRepository.class),
                service,
                crypto,
                new ObjectMapper(),
                fixedClock,
                immediateTxManager(),
                mock(VerificationRequestLimit.class));

        queue.enqueueGoogleLinked(user, null);
        user.setFirstName("Ana");
        queue.enqueueGoogleUnlinked(user, LocalDateTime.now(fixedClock));
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void googleFlowTokenServiceValidatesBoundChallengesLinkConfirmTokensAndLocks() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GoogleAuthProperties props = new GoogleAuthProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-11-22T20:00:00Z"), ZoneOffset.UTC);
        GoogleFlowTokenService service = new GoogleFlowTokenService(
                jdbc, props, mock(TotpService.class), mock(TotpSecretStorage.class), clock);

        assertThatThrownBy(() -> service.createBoundNonceChallenge("LINK", null, "jti-1"))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.createBoundNonceChallenge("LINK", 1L, null))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.createBoundNonceChallenge("LINK", 1L, "   "))
                .isInstanceOf(AuthFlowException.class);

        String unboundNonce = service.createNonceChallenge(null);
        assertThat(unboundNonce).isNotBlank();

        assertThatThrownBy(() -> service.consumeNonceChallenge(null, "AUTH", null, null, false))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.consumeNonceChallenge("short", "AUTH", null, null, false))
                .isInstanceOf(AuthFlowException.class);

        String validNonce = "valid_nonce_token_1234567890";
        LocalDateTime future = LocalDateTime.now(clock).plusMinutes(5);

        // Missing row
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(Collections.emptyList());
        assertThatThrownBy(() -> service.consumeNonceChallenge(validNonce, "AUTH", null, null, false))
                .isInstanceOf(AuthFlowException.class);

        // Null expectedPurpose
        Map<String, Object> authRow = new HashMap<>();
        authRow.put("id", 10L);
        authRow.put("purpose", "AUTH");
        authRow.put("expires_at", Timestamp.valueOf(future));
        authRow.put("consumed_at", null);
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of(authRow));
        assertThatThrownBy(() -> service.consumeNonceChallenge(validNonce, null, null, null, false))
                .isInstanceOf(AuthFlowException.class);

        // AUTH purpose with bound context must be rejected
        Map<String, Object> boundAuthRow = new HashMap<>(authRow);
        boundAuthRow.put("user_id", 7L);
        boundAuthRow.put("session_jti", "jti-7");
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of(boundAuthRow));
        assertThatThrownBy(() -> service.consumeNonceChallenge(validNonce, "AUTH", 7L, "jti-7", false))
                .isInstanceOf(AuthFlowException.class);

        // Bound LINK row with missing/blank expectedUserId or expectedSessionJti
        Map<String, Object> boundLinkRow = new HashMap<>(authRow);
        boundLinkRow.put("purpose", "LINK");
        boundLinkRow.put("user_id", 7L);
        boundLinkRow.put("session_jti", "jti-7");
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of(boundLinkRow));
        assertThatThrownBy(() -> service.consumeNonceChallenge(validNonce, "LINK", null, "jti-7", true))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.consumeNonceChallenge(validNonce, "LINK", 7L, null, true))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.consumeNonceChallenge(validNonce, "LINK", 7L, "  ", true))
                .isInstanceOf(AuthFlowException.class);

        // Unbound LINK row when requireBoundContext=true -> rejected; when false -> accepted (returns false)
        Map<String, Object> unboundLinkRow = new HashMap<>(authRow);
        unboundLinkRow.put("purpose", "LINK");
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of(unboundLinkRow));
        assertThatThrownBy(() -> service.consumeNonceChallenge(validNonce, "LINK", 7L, "jti-7", true))
                .isInstanceOf(AuthFlowException.class);
        assertThat(service.consumeNonceChallenge(validNonce, "LINK", 7L, "jti-7", false)).isFalse();

        // createPendingLinkConfirmation validation
        var verified = new GoogleIdTokenVerifierService.VerifiedGoogleIdentity(
                "sub-1", "g@example.com", true, "G", "U", "G U", null, Instant.now());
        assertThatThrownBy(() -> service.createPendingLinkConfirmation(null, "jti", verified))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.createPendingLinkConfirmation(1L, null, verified))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.createPendingLinkConfirmation(1L, " ", verified))
                .isInstanceOf(AuthFlowException.class);

        // lockPendingLinkConfirmation branches
        assertThatThrownBy(() -> service.lockPendingLinkConfirmation(1L, "jti", null))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.lockPendingLinkConfirmation(1L, "jti", "short"))
                .isInstanceOf(AuthFlowException.class);

        String validConfirmTok = "confirm_token_1234567890123456";
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(Collections.emptyList());
        assertThatThrownBy(() -> service.lockPendingLinkConfirmation(1L, "jti", validConfirmTok))
                .isInstanceOf(AuthFlowException.class);

        Map<String, Object> badConfirmRow = new HashMap<>();
        badConfirmRow.put("id", 55L);
        badConfirmRow.put("user_id", null);
        badConfirmRow.put("session_jti", "jti");
        badConfirmRow.put("purpose", "WRONG");
        badConfirmRow.put("expires_at", Timestamp.valueOf(future));
        badConfirmRow.put("consumed_at", null);
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of(badConfirmRow));
        assertThatThrownBy(() -> service.lockPendingLinkConfirmation(1L, "jti", validConfirmTok))
                .isInstanceOf(AuthFlowException.class);

        badConfirmRow.put("user_id", 1L);
        assertThatThrownBy(() -> service.lockPendingLinkConfirmation(1L, "jti", validConfirmTok))
                .isInstanceOf(AuthFlowException.class);

        badConfirmRow.put("expires_at", Timestamp.valueOf(LocalDateTime.now(clock).minusSeconds(5)));
        assertThatThrownBy(() -> service.lockPendingLinkConfirmation(1L, "jti", validConfirmTok))
                .isInstanceOf(AuthFlowException.class);

        // cancelPendingLinkConfirmation branches
        assertThat(service.cancelPendingLinkConfirmation(null, "jti", validConfirmTok)).isFalse();
        assertThat(service.cancelPendingLinkConfirmation(1L, null, validConfirmTok)).isFalse();
        assertThat(service.cancelPendingLinkConfirmation(1L, "  ", validConfirmTok)).isFalse();
        assertThat(service.cancelPendingLinkConfirmation(1L, "jti", null)).isFalse();
        assertThat(service.cancelPendingLinkConfirmation(1L, "jti", "short")).isFalse();
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
        assertThat(service.cancelPendingLinkConfirmation(1L, "jti", validConfirmTok)).isFalse();

        // acquireTransactionLock branches
        service.acquireTransactionLock(null, "k");
        service.acquireTransactionLock("ns", null);
        service.acquireTransactionLock("ns", "k");
        verify(jdbc).query(anyString(), any(ResultSetExtractor.class), any(), any());
    }

    @Test
    void googleAuthServiceValidatesSessionPreconditionsConflictsAndMailResilience() {
        GoogleAuthProperties props = new GoogleAuthProperties();
        props.setWebClientId("web-client-1");
        GoogleIdTokenVerifierService verifier = mock(GoogleIdTokenVerifierService.class);
        GoogleFlowTokenService flowTokens = mock(GoogleFlowTokenService.class);
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        ExternalIdentityRepository extRepo = mock(ExternalIdentityRepository.class);
        UserService userService = mock(UserService.class);
        JwtTokenProvider jwtProvider = mock(JwtTokenProvider.class);
        RefreshTokenService refreshService = mock(RefreshTokenService.class);
        SessionManagementService sessions = mock(SessionManagementService.class);
        LoginChallengeService loginChallenges = mock(LoginChallengeService.class);
        TwoFactorService twoFactor = mock(TwoFactorService.class);
        BackupCodeService backupCodes = mock(BackupCodeService.class);
        RegistrationLegalPolicy legal = mock(RegistrationLegalPolicy.class);
        AuditLogService audit = mock(AuditLogService.class);
        VerificationRequestLimit limits = mock(VerificationRequestLimit.class);
        ActivationClientAddress clientAddr = mock(ActivationClientAddress.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        SecurityMailQueue mailQueue = mock(SecurityMailQueue.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-22T20:00:00Z"), ZoneOffset.UTC);

        GoogleAuthService service = new GoogleAuthService(
                props, verifier, flowTokens, users, roles, extRepo, userService,
                jwtProvider, refreshService, sessions, loginChallenges, twoFactor,
                backupCodes, legal, audit, limits, clientAddr, encoder,
                immediateTxManager(), clock, mailQueue);

        // Unconfigured challenge check
        props.setWebClientId("");
        assertThatThrownBy(() -> service.issueAuthenticatedChallenge(1L, "jti", "LINK"))
                .isInstanceOf(AuthFlowException.class);
        props.setWebClientId("web-client-1");

        // Blank sessionJti or invalid purpose across methods
        assertThatThrownBy(() -> service.issueAuthenticatedChallenge(1L, null, "LINK"))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.issueAuthenticatedChallenge(1L, " ", "LINK"))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.issueAuthenticatedChallenge(1L, "jti", null))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.issueAuthenticatedChallenge(1L, "jti", "AUTH"))
                .isInstanceOf(AuthFlowException.class);

        var reauthReq = new GoogleReauthRequest("id-tok", "nonce-1234567890123456", "UNLINK_GOOGLE", null, null);
        assertThatThrownBy(() -> service.reauthenticate(1L, null, reauthReq))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.reauthenticate(1L, " ", reauthReq))
                .isInstanceOf(AuthFlowException.class);

        var prepReq = new PrepareLinkGoogleRequest("id-tok", "nonce-1234567890123456", "Pass!123", null, null);
        assertThatThrownBy(() -> service.prepareLinkGoogle(1L, null, prepReq))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.prepareLinkGoogle(1L, " ", prepReq))
                .isInstanceOf(AuthFlowException.class);

        var confReq = new ConfirmLinkGoogleRequest("confirm-tok-1234567890123456", true);
        assertThatThrownBy(() -> service.confirmLinkGoogle(1L, null, confReq))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.confirmLinkGoogle(1L, " ", confReq))
                .isInstanceOf(AuthFlowException.class);

        var cancelReq = new CancelLinkGoogleRequest("confirm-tok-1234567890123456");
        assertThatThrownBy(() -> service.cancelLinkGoogle(1L, null, cancelReq))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.cancelLinkGoogle(1L, " ", cancelReq))
                .isInstanceOf(AuthFlowException.class);

        var linkReq = new LinkGoogleRequest("id-tok", "nonce-1234567890123456", "Pass!123", true, null, null);
        assertThatThrownBy(() -> service.linkGoogle(1L, null, linkReq))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.linkGoogle(1L, " ", linkReq))
                .isInstanceOf(AuthFlowException.class);

        var unlinkReq = new UnlinkGoogleRequest("Pass!123", null, true, null, null);
        assertThatThrownBy(() -> service.unlinkGoogle(1L, null, unlinkReq))
                .isInstanceOf(AuthFlowException.class);

        // Session invalid / does not belong to user
        when(sessions.isSessionValid("jti-expired")).thenReturn(false);
        assertThatThrownBy(() -> service.cancelLinkGoogle(1L, "jti-expired", cancelReq))
                .isInstanceOf(AuthFlowException.class);
        when(sessions.isSessionValid("jti-other")).thenReturn(true);
        when(sessions.belongsToUser("jti-other", 1L)).thenReturn(false);
        assertThatThrownBy(() -> service.cancelLinkGoogle(1L, "jti-other", cancelReq))
                .isInstanceOf(AuthFlowException.class);

        // Valid session, cancel returns false
        when(sessions.isSessionValid("jti-ok")).thenReturn(true);
        when(sessions.belongsToUser("jti-ok", 1L)).thenReturn(true);
        when(flowTokens.cancelPendingLinkConfirmation(1L, "jti-ok", cancelReq.confirmationToken())).thenReturn(false);
        assertThat(service.cancelLinkGoogle(1L, "jti-ok", cancelReq)).containsEntry("cancelled", false);

        // User without local password on prepareLinkGoogle & confirmLinkGoogle
        User googleOnlyUser = new User();
        googleOnlyUser.setId(1L);
        googleOnlyUser.setEmail("gonly@example.com");
        googleOnlyUser.setEnabled(true);
        googleOnlyUser.setAccountNonLocked(true);
        googleOnlyUser.setPassword(null);
        when(users.findByIdForAuthUpdate(1L)).thenReturn(Optional.of(googleOnlyUser));
        assertThatThrownBy(() -> service.prepareLinkGoogle(1L, "jti-ok", prepReq))
                .isInstanceOf(AuthFlowException.class);
        assertThatThrownBy(() -> service.confirmLinkGoogle(1L, "jti-ok", confReq))
                .isInstanceOf(AuthFlowException.class);

        // User with local password: confirmLinkGoogle handles DataIntegrityViolationException -> 409,
        // and tolerates null or throwing securityMailQueue + existing avatarUrl
        User localUser = new User();
        localUser.setId(1L);
        localUser.setEmail("local@example.com");
        localUser.setEnabled(true);
        localUser.setAccountNonLocked(true);
        localUser.setPassword("$2a$10$hash");
        localUser.setAvatarUrl("https://existing.example/avatar.png");
        when(users.findByIdForAuthUpdate(1L)).thenReturn(Optional.of(localUser));

        var pendingFlow = new GoogleFlowTokenService.PendingLinkConfirmFlow(
                9L, 1L, "jti-ok", "sub-9", "g9@example.com", "G", "N", "https://new.example/pic.png");
        when(flowTokens.lockPendingLinkConfirmation(1L, "jti-ok", confReq.confirmationToken())).thenReturn(pendingFlow);

        // Mail queue throws RuntimeException -> caught & logged without failing confirmLinkGoogle
        doThrow(new IllegalStateException("mail down")).when(mailQueue).enqueueGoogleLinked(any(), any());
        assertThat(service.confirmLinkGoogle(1L, "jti-ok", confReq)).containsEntry("hasLocalPassword", true);

        // DataIntegrityViolationException on saveAndFlush -> converted to 409 AuthFlowException
        when(extRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
        assertThatThrownBy(() -> service.confirmLinkGoogle(1L, "jti-ok", confReq))
                .isInstanceOf(AuthFlowException.class)
                .extracting(e -> ((AuthFlowException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        // Same subject already linked to the SAME user -> 409 "ya está vinculada a tu perfil"
        ExternalIdentity selfExt = new ExternalIdentity();
        selfExt.setUser(localUser);
        selfExt.setProvider("GOOGLE");
        selfExt.setProviderSubject("sub-9");
        when(extRepo.findByProviderAndProviderSubject("GOOGLE", "sub-9")).thenReturn(Optional.of(selfExt));
        assertThatThrownBy(() -> service.confirmLinkGoogle(1L, "jti-ok", confReq))
                .isInstanceOf(AuthFlowException.class)
                .hasMessageContaining("vinculada a tu perfil");

        // Service without securityMailQueue (null)
        GoogleAuthService serviceNoMail = new GoogleAuthService(
                props, verifier, flowTokens, users, roles, extRepo, userService,
                jwtProvider, refreshService, sessions, loginChallenges, twoFactor,
                backupCodes, legal, audit, limits, clientAddr, encoder,
                immediateTxManager(), clock, null);
        when(extRepo.findByProviderAndProviderSubject("GOOGLE", "sub-9")).thenReturn(Optional.empty());
        org.mockito.Mockito.doReturn(selfExt).when(extRepo).saveAndFlush(any());
        assertThat(serviceNoMail.confirmLinkGoogle(1L, "jti-ok", confReq)).containsEntry("hasLocalPassword", true);
    }

    @Test
    void refreshTokenAndExternalIdentityEntitiesExposeLifecycleFields() {
        User user = new User();
        user.setEmail("entity@example.com");

        LocalDateTime future = LocalDateTime.now().plusHours(2);
        RefreshToken token = new RefreshToken("hash-1", user, future);
        LocalDateTime created = LocalDateTime.now();
        token.setId(15L);
        token.setCreatedAt(created);
        token.setLastUsedAt(created);
        token.setRotatedAt(created);
        token.setRevokedAt(created);
        token.setRevokeReason("ROTATED");
        token.setUserAgent("Android-App");
        token.setIpAddress("198.51.100.1");
        token.setFamilyId("fam-1");
        token.setSessionJti("jti-1");
        token.setRevoked(true);

        assertThat(token.getId()).isEqualTo(15L);
        assertThat(token.getToken()).isEqualTo("hash-1");
        assertThat(token.getUser()).isSameAs(user);
        assertThat(token.getCreatedAt()).isEqualTo(created);
        assertThat(token.getLastUsedAt()).isEqualTo(created);
        assertThat(token.getRotatedAt()).isEqualTo(created);
        assertThat(token.getRevokedAt()).isEqualTo(created);
        assertThat(token.getRevokeReason()).isEqualTo("ROTATED");
        assertThat(token.getUserAgent()).isEqualTo("Android-App");
        assertThat(token.getIpAddress()).isEqualTo("198.51.100.1");
        assertThat(token.isExpired()).isFalse();

        token.setAbsoluteExpiresAt(null);
        assertThat(token.isExpired()).isFalse();

        token.setExpiryDate(LocalDateTime.now().minusSeconds(5));
        assertThat(token.isExpired()).isTrue();

        token.setExpiryDate(future);
        token.setAbsoluteExpiresAt(LocalDateTime.now().minusSeconds(5));
        assertThat(token.isExpired()).isTrue();

        ExternalIdentity ext = new ExternalIdentity();
        ext.setId(42L);
        ext.setUser(user);
        ext.setProvider("GOOGLE");
        ext.setProviderSubject("sub-42");
        ext.setProviderEmail("ext@example.com");
        ext.setEmailVerified(true);
        ext.setPictureUrl("https://lh3.googleusercontent.com/p");
        ext.setLinkedAt(created);
        ext.setLastLoginAt(created);

        assertThat(ext.getId()).isEqualTo(42L);
        assertThat(ext.getUser()).isSameAs(user);
        assertThat(ext.getProvider()).isEqualTo("GOOGLE");
        assertThat(ext.getProviderSubject()).isEqualTo("sub-42");
        assertThat(ext.getProviderEmail()).isEqualTo("ext@example.com");
        assertThat(ext.isEmailVerified()).isTrue();
        assertThat(ext.getPictureUrl()).isEqualTo("https://lh3.googleusercontent.com/p");
        assertThat(ext.getLinkedAt()).isEqualTo(created);
        assertThat(ext.getLastLoginAt()).isEqualTo(created);
    }

    @Test
    void activationClientAddressHandlesNullUntrustedAndTrustedForwarding() {
        MockEnvironment untrustedEnv = new MockEnvironment();
        ActivationClientAddress untrusted = new ActivationClientAddress(untrustedEnv);
        assertThat(untrusted.resolve(null)).isNull();

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("198.51.100.9");
        direct.addHeader("X-Forwarded-For", "203.0.113.5");
        assertThat(untrusted.resolve(direct)).isEqualTo("198.51.100.9");

        MockEnvironment trustedEnv = new MockEnvironment().withProperty("app.activation.trusted-proxies", "10.0.0.0/8");
        ActivationClientAddress trusted = new ActivationClientAddress(trustedEnv);
        MockHttpServletRequest proxied = new MockHttpServletRequest();
        proxied.setRemoteAddr("10.1.2.3");
        proxied.addHeader("X-Forwarded-For", "203.0.113.7, 10.1.2.4");
        assertThat(trusted.resolve(proxied)).isEqualTo("203.0.113.7");

        MockEnvironment invalidEnv = new MockEnvironment().withProperty("app.activation.trusted-proxies", "0.0.0.0/0");
        assertThatThrownBy(() -> new ActivationClientAddress(invalidEnv))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authFlowExceptionCarriesHttpStatus() {
        AuthFlowException ex = new AuthFlowException(org.springframework.http.HttpStatus.CONFLICT, "conflict");
        assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(ex.getMessage()).isEqualTo("conflict");
    }
}
