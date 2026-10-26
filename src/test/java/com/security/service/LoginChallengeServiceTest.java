package com.security.service;

import com.security.dto.request.LoginFactorRequest;
import com.security.entity.User;
import com.security.exception.AuthFlowException;
import com.security.repository.UserRepository;
import com.security.security.JwtTokenProvider;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL row locks/commits. Dedicated disposable local database, never application env. */
class LoginChallengeServiceTest {
    JdbcTemplate jdbc;
    UserRepository users;
    TwoFactorService factors;
    EmailService email;
    JwtTokenProvider jwt;
    User user;
    LoginChallengeService service;

    private static final org.springframework.jdbc.datasource.DriverManagerDataSource DATABASE =
            new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55439/auth_challenges_test", "auth_test", "synthetic-test-only");
    @BeforeAll static void migrateFullSchema() {
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(DATABASE)
                .schemas("public", "auth", "security", "catalog", "sales", "customer", "ops", "analytics")
                .locations("classpath:db/test-baseline", "classpath:db/migration")
                .initSql("SET search_path TO public,auth,security,catalog,sales,customer,ops,analytics")
                .cleanDisabled(false).load();
        // Only this fixed, dedicated disposable database is reset. Never application env.
        flyway.clean(); flyway.migrate(); flyway.validate();
    }
    @BeforeEach void setup() {
        var ds = DATABASE;
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("TRUNCATE auth.users CASCADE");
        jdbc.update("""
                INSERT INTO auth.users(id,username,first_name,last_name,email,password,enabled,
                  account_non_expired,account_non_locked,credentials_non_expired,two_factor_enabled,
                  google_auth_enabled,email_enabled,backup_codes_enabled,is_customer,created_at)
                VALUES (1,'synthetic','Synthetic','Test','synthetic@example.invalid','synthetic-hash',true,
                  true,true,true,true,false,true,false,true,now())
                """);
        users = mock(UserRepository.class); factors = mock(TwoFactorService.class);
        email = mock(EmailService.class); jwt = mock(JwtTokenProvider.class);
        user = new User(); user.setId(1L); user.setEmail("synthetic@example.invalid");
        user.setEnabled(true); user.setEmailEnabled(true);
        when(users.findByIdForAuthUpdate(1L)).thenAnswer(inv -> {
            jdbc.queryForObject("SELECT id FROM auth.users WHERE id=1 FOR UPDATE", Long.class);
            return Optional.of(user);
        });
        when(jwt.generateToken(any(), any())).thenReturn("synthetic-issued-jwt");
        var transactions = new DataSourceTransactionManager(ds);
        var target = new LoginChallengeService(jdbc, users, factors, email, new BCryptPasswordEncoder(4), jwt, mock(UserService.class), transactions);
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (LoginChallengeService) factory.getProxy();
    }
    String challenge() { return service.create(1L).preAuthChallenge(); }
    LoginFactorRequest request(String challenge, String code) { return new LoginFactorRequest(challenge, "EMAIL", code); }
    String send(String challenge) {
        service.send(request(challenge, null));
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(email, atLeastOnce()).send2FACodeEmail(argThat(recipient -> user.getEmail().equals(recipient.getEmail())), captor.capture());
        return captor.getValue();
    }
    @Test void storesOnlyHashesAndNoAccessBeforeFactor() {
        String token = challenge();
        assertEquals(43, token.length());
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(token).length);
        String code = send(token);
        var row = jdbc.queryForMap("SELECT * FROM auth.login_challenges");
        assertEquals("LOGIN_2FA", row.get("purpose"));
        assertEquals(java.time.Duration.ofMinutes(5), java.time.Duration.between(
                ((java.sql.Timestamp) row.get("created_at")).toLocalDateTime(),
                ((java.sql.Timestamp) row.get("expires_at")).toLocalDateTime()));
        assertNotEquals(token, row.get("challenge_hash"));
        assertEquals(64, row.get("challenge_hash").toString().length());
        assertNotEquals(code, row.get("email_code_hash"));
        assertNotEquals(token, challenge());
        verifyNoInteractions(jwt);
    }
    @Test void correctCodeConsumesAndCreatesAccess() {
        String token = challenge(); String code = send(token);
        assertEquals("synthetic-issued-jwt", service.verify(request(token, code), new MockHttpServletRequest()).getAccessToken());
        assertNotNull(jdbc.queryForObject("SELECT consumed_at FROM auth.login_challenges", java.sql.Timestamp.class));
        assertThrows(AuthFlowException.class, () -> service.verify(request(token, code), new MockHttpServletRequest()));
        verify(jwt, times(1)).generateToken(any(), any());
    }
    @Test void wrongCodeCommitsAttemptAndExhaustion() {
        String token = challenge();
        for (int i=0;i<5;i++) assertThrows(AuthFlowException.class, () -> service.verify(request(token,"000000"), new MockHttpServletRequest()));
        assertEquals(5, jdbc.queryForObject("SELECT attempt_count FROM auth.login_challenges", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT consumed_at FROM auth.login_challenges", java.sql.Timestamp.class));
        verifyNoInteractions(jwt);
    }
    @Test void missingAndCorruptChallengesRejected() {
        for (String token : new String[]{"corrupt", "A".repeat(43)})
            assertThrows(AuthFlowException.class, () -> service.send(request(token,null)));
        verifyNoInteractions(email, jwt);
    }
    @Test void expiredConsumedAndExhaustedRejected() {
        for (String change : new String[]{"expires_at=created_at + interval '1 second',created_at=created_at-interval '1 hour'", "consumed_at=now()", "attempt_count=5"}) {
            String token = challenge();
            // Keep the expiry constraint valid while making the record expired.
            if (change.startsWith("expires_at")) jdbc.update("UPDATE auth.login_challenges SET created_at=now()-interval '1 hour',expires_at=now()-interval '1 minute'");
            else jdbc.update("UPDATE auth.login_challenges SET " + change);
            assertThrows(AuthFlowException.class, () -> service.verify(request(token,"000000"),new MockHttpServletRequest()));
        }
        verifyNoInteractions(jwt);
    }
    @Test void schemaRejectsWrongPurposeAndForeignUser() {
        challenge();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("UPDATE auth.login_challenges SET purpose='OTHER'"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("UPDATE auth.login_challenges SET user_id=999"));
    }
    @Test void disabledDeletedOrUnconfiguredUserRejected() {
        String token=challenge();
        user.setEnabled(false);
        assertThrows(AuthFlowException.class,()->service.send(request(token,null)));
        user.setEnabled(true); user.setEmailEnabled(false);
        assertThrows(AuthFlowException.class,()->service.send(request(token,null)));
        when(users.findByIdForAuthUpdate(1L)).thenReturn(Optional.empty());
        assertThrows(AuthFlowException.class,()->service.send(request(token,null)));
        verifyNoInteractions(email,jwt);
    }
    @Test void methodsCannotBeEnabledBySendingAndSendLimitSurvivesNewChallenges() {
        String token=challenge();
        assertThrows(AuthFlowException.class,()->service.send(new LoginFactorRequest(token,"GOOGLE_AUTHENTICATOR",null)));
        send(token); send(token); send(token);
        String replacement=challenge();
        assertEquals(429,assertThrows(AuthFlowException.class,()->service.send(request(replacement,null))).getStatus().value());
        assertTrue(user.getEmailEnabled());
    }
    @Test void challengeCreationRateLimitedAndSupersedesPrior() {
        String first=challenge();
        for(int i=0;i<4;i++) challenge();
        assertEquals(429, assertThrows(AuthFlowException.class,()->challenge()).getStatus().value());
        assertThrows(AuthFlowException.class,()->service.send(request(first,null)));
    }
    @Test void factorAttemptLimitCannotBeResetByPasswordStage() {
        for(int i=0;i<5;i++) {
            String token=challenge();
            assertThrows(AuthFlowException.class,()->service.verify(request(token,"000000"),new MockHttpServletRequest()));
        }
        jdbc.update("UPDATE auth.login_factor_limits SET challenges=0");
        assertEquals(429, assertThrows(AuthFlowException.class,()->challenge()).getStatus().value());
        verifyNoInteractions(jwt);
    }
    @Test void totpAndBackupUseSameChallenge() {
        user.setGoogleAuthEnabled(true);user.setBackupCodesEnabled(true);
        when(factors.findGoogleAuthenticatorLoginSlot(1L,"123456")).thenReturn(java.util.OptionalLong.of(123456L));
        when(factors.verifyBackupCode(1L,"1234-5678")).thenReturn(true);
        service.verify(new LoginFactorRequest(challenge(),"GOOGLE_AUTHENTICATOR","123456"),new MockHttpServletRequest());
        service.verify(new LoginFactorRequest(challenge(),"BACKUP_CODE","1234-5678"),new MockHttpServletRequest());
        verify(jwt,times(2)).generateToken(any(),any());
    }
    @Test void concurrentDoubleConsumptionIssuesExactlyOneJwt() throws Exception {
        String token=challenge();String code=send(token);
        var pool=Executors.newFixedThreadPool(2); var gate=new CountDownLatch(1);
        Callable<Boolean> call=()-> { gate.await(); try {service.verify(request(token,code),new MockHttpServletRequest());return true;}catch(AuthFlowException ex){return false;} };
        try {
            var a=pool.submit(call);var b=pool.submit(call);gate.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            verify(jwt,times(1)).generateToken(any(),any());
        } finally {pool.shutdownNow();}
    }
    @Test void sessionFailureRollsBackConsumption() {
        String token=challenge();String code=send(token);
        when(jwt.generateToken(any(),any())).thenThrow(new IllegalStateException("synthetic failure"));
        assertThrows(IllegalStateException.class,()->service.verify(request(token,code),new MockHttpServletRequest()));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM auth.login_challenges",java.sql.Timestamp.class));
    }
    @Test void exhaustedAttemptsPreventCreatingAndSendingUntilWindowResets() {
        String token=challenge();
        jdbc.update("UPDATE auth.login_factor_limits SET attempts=5");
        assertEquals(429,assertThrows(AuthFlowException.class,()->challenge()).getStatus().value());
        assertEquals(429,assertThrows(AuthFlowException.class,()->service.send(request(token,null))).getStatus().value());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auth.login_challenges",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT sends FROM auth.login_factor_limits",Integer.class));
        verifyNoInteractions(email,jwt);
        jdbc.update("UPDATE auth.login_factor_limits SET window_start=now()-interval '16 minutes'");
        send(challenge());
        assertEquals(0,jdbc.queryForObject("SELECT attempts FROM auth.login_factor_limits",Integer.class));
    }
    @Test void acceptedTotpSlotSurvivesNewChallengesAndQuotaWindowReset() {
        user.setGoogleAuthEnabled(true);
        when(factors.findGoogleAuthenticatorLoginSlot(1L,"123456")).thenReturn(java.util.OptionalLong.of(2000L));
        service.verify(new LoginFactorRequest(challenge(),"GOOGLE_AUTHENTICATOR","123456"),new MockHttpServletRequest());
        jdbc.update("UPDATE auth.login_factor_limits SET window_start=now()-interval '16 minutes'");
        String next=challenge();
        assertThrows(AuthFlowException.class,()->service.verify(new LoginFactorRequest(next,"GOOGLE_AUTHENTICATOR","123456"),new MockHttpServletRequest()));
        assertEquals(2000L,jdbc.queryForObject("SELECT last_verified_totp_slot FROM auth.login_factor_limits",Long.class));
        when(factors.findGoogleAuthenticatorLoginSlot(1L,"123456")).thenReturn(java.util.OptionalLong.of(1999L));
        assertThrows(AuthFlowException.class,()->service.verify(new LoginFactorRequest(next,"GOOGLE_AUTHENTICATOR","123456"),new MockHttpServletRequest()));
        when(factors.findGoogleAuthenticatorLoginSlot(1L,"123456")).thenReturn(java.util.OptionalLong.of(2001L));
        service.verify(new LoginFactorRequest(next,"GOOGLE_AUTHENTICATOR","123456"),new MockHttpServletRequest());
        verify(jwt,times(2)).generateToken(any(),any());
    }
    @Test void emailTransportRunsAfterCommitAndOutsideRowLocks() {
        String token=challenge();
        doAnswer(invocation->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            // A separate JDBC connection can acquire the very row locked during preparation.
            var separate = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(DATABASE));
            separate.executeWithoutResult(status->jdbc.queryForObject("SELECT id FROM auth.users WHERE id=1 FOR UPDATE NOWAIT",Long.class));
            assertEquals(1,jdbc.queryForObject("SELECT sends FROM auth.login_factor_limits",Integer.class));
            assertNotNull(jdbc.queryForObject("SELECT email_code_hash FROM auth.login_challenges",String.class));
            User recipient=invocation.getArgument(0);assertNull(recipient.getPassword());assertNull(recipient.getGoogleAuthSecret());
            return null;
        }).when(email).send2FACodeEmail(any(),anyString());
        service.send(request(token,null));
    }
    @Test void ambientTransactionIsRejectedBeforeMailOrSecurityQuotaMutation() {
        String token=challenge();
        var outer=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(DATABASE));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                ()->outer.executeWithoutResult(status->service.send(request(token,null))));
        assertEquals(0,jdbc.queryForObject("SELECT sends FROM auth.login_factor_limits",Integer.class));
        verifyNoInteractions(email,jwt);
    }
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void uncertainEmailFailureCommitsQuotaAndNeverLogsCode(org.springframework.boot.test.system.CapturedOutput output) {
        String token=challenge();
        var lastCode=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(invocation->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            String code=invocation.getArgument(1);lastCode.set(code);
            throw new IllegalStateException("SyntheticProviderSensitive_"+code);
        }).when(email).send2FACodeEmail(any(),anyString());
        for(int i=0;i<3;i++) assertEquals(503,assertThrows(AuthFlowException.class,()->service.send(request(token,null))).getStatus().value());
        assertEquals(3,jdbc.queryForObject("SELECT sends FROM auth.login_factor_limits",Integer.class));
        assertEquals(429,assertThrows(AuthFlowException.class,()->service.send(request(token,null))).getStatus().value());
        verify(email,times(3)).send2FACodeEmail(any(),anyString());
        assertTrue(new BCryptPasswordEncoder(4).matches(lastCode.get(),jdbc.queryForObject("SELECT email_code_hash FROM auth.login_challenges",String.class)));
        assertFalse(output.getAll().contains("SyntheticProviderSensitive_"));
        assertFalse(output.getAll().contains(lastCode.get()));
        verifyNoInteractions(jwt);
    }

}
