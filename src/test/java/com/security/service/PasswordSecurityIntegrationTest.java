package com.security.service;

import com.security.entity.*;
import com.security.repository.*;
import com.security.support.LocalPostgresIntegrationTest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"app.activation.jobs-enabled=false","logging.level.root=WARN","spring.jpa.show-sql=false"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import(ActivationHardeningIntegrationTest.TimeConfig.class)
class PasswordSecurityIntegrationTest extends LocalPostgresIntegrationTest {
    @Autowired ActivationHardeningIntegrationTest.MutableClock clock;
    @Autowired SecurePasswordResetService recovery;
    @Autowired SecurityMailQueue queue;
    @Autowired VerificationRequestLimit limits;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordResetTokenRepository tokens;
    @Autowired ActiveSessionRepository sessions;
    @Autowired PasswordEncoder encoder;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmailService mail;
    @MockitoBean com.security.config.MercadoPagoConfiguration payments;
    final List<User> accounts=new ArrayList<>();
    final Map<String,String> received=new ConcurrentHashMap<>();
    static final String OLD="Synthetic@9zSafe", NEW="Changed@8xSafe";
    @BeforeEach void setup() {
        clock.current=Instant.now();
        jdbc.update("DELETE FROM auth.activation_request_limits");
        if(roles.findByName("ROLE_USER").isEmpty()){Role role=new Role();role.setName("ROLE_USER");role.setDescription("Synthetic");roles.save(role);}
        doAnswer(call->{User recipient=call.getArgument(0);received.put(recipient.getEmail(),call.getArgument(1));return EmailService.ActivationDelivery.ACCEPTED;})
            .when(mail).deliverPasswordReset(any(),anyString());
        when(mail.deliverPasswordChanged(any())).thenReturn(EmailService.ActivationDelivery.ACCEPTED);
    }
    @AfterEach void cleanup() {
        for(User u:accounts) {
            jdbc.update("DELETE FROM auth.login_challenges WHERE user_id=?",u.getId());
            sessions.deleteAll(sessions.findByUserId(u.getId()));tokens.deleteAllByUser(u);users.deleteById(u.getId());
        }
    }
    User account() {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,15);
        User u=new User();u.setUsername("pw_"+suffix);u.setEmail(suffix+"@gmail.com");
        u.setFirstName("Synthetic");u.setLastName("Account");u.setPassword(encoder.encode(OLD));
        u.setEnabled(true);u.setAccountNonLocked(true);u.setAccountNonExpired(true);u.setCredentialsNonExpired(true);
        u.setTwoFactorEnabled(false);u.setRoles(Set.of(roles.findByName("ROLE_USER").orElseThrow()));
        u.recordEmailVerification(LocalDateTime.now(clock));u=users.saveAndFlush(u);accounts.add(u);return u;
    }
    String request(User u) {
        assertTrue(recovery.requestPasswordResetFromController(u.getEmail(),"192.0.2.2",""));
        queue.dispatchPending();
        return received.get(u.getEmail());
    }
    String login(User u) throws Exception {
        var result=mvc.perform(post("/api/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("email",u.getEmail(),"password",OLD)))).andReturn();
        assertEquals(200,result.getResponse().getStatus());
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("jwtResponse").path("accessToken").asText();
    }
    int change(String bearer,String current,String replacement) throws Exception {
        return mvc.perform(post("/api/users/change-password").header("Authorization","Bearer "+bearer)
            .contentType("application/json").content(json.writeValueAsString(Map.of("currentPassword",current,"newPassword",replacement))))
            .andReturn().getResponse().getStatus();
    }
    @Test void resetDigestSingleUseRevokesBothSessionsAndQueuesNotice() throws Exception {
        User u=account();String a=login(u),b=login(u),raw=request(u);
        assertNotNull(raw);assertTrue(tokens.findByToken(raw).isEmpty());
        assertTrue(tokens.findByToken(SecurePasswordResetService.resetDigest(raw)).isPresent());
        assertTrue(recovery.resetPasswordFromController(raw,NEW,"192.0.2.2",""));
        assertFalse(recovery.resetPasswordFromController(raw,OLD,"192.0.2.2",""));
        assertTrue(encoder.matches(NEW,users.findById(u.getId()).orElseThrow().getPassword()));
        for(String token:List.of(a,b))assertEquals(401,mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus());
        queue.dispatchPending();verify(mail,times(1)).deliverPasswordChanged(any());
    }
    @Test void simultaneousResetHasOneWinner() throws Exception {
        User u=account();String raw=request(u);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->recovery.resetPasswordFromController(raw,NEW,"192.0.2.2",""));
            var b=pool.submit(()->recovery.resetPasswordFromController(raw,"Other@7xSafe","192.0.2.3",""));
            assertNotEquals(a.get(),b.get());
        } finally {pool.shutdownNow();}
    }
    @Test void expiryAndAccountSuspensionAreRespected() {
        User u=account();String raw=request(u);clock.advance(3600);
        assertFalse(recovery.isTokenValid(raw));assertFalse(recovery.resetPasswordFromController(raw,NEW,"192.0.2.2",""));
        User blocked=account();jdbc.update("UPDATE auth.users SET enabled=false WHERE id=?",blocked.getId());
        recovery.requestPasswordResetFromController(blocked.getEmail(),"192.0.2.2","");
        assertTrue(tokens.findByUser(blocked).isEmpty());
    }
    @Test void confirmedFailureAndUnknownKeepOldLinkAndNeverReplay() {
        User u=account();String old=request(u);clock.advance(61);
        doReturn(EmailService.ActivationDelivery.FAILED).when(mail).deliverPasswordReset(any(),anyString());
        recovery.requestPasswordResetFromController(u.getEmail(),"192.0.2.2","");queue.dispatchPending();
        assertTrue(recovery.isTokenValid(old));clock.advance(61);
        doReturn(EmailService.ActivationDelivery.UNKNOWN).when(mail).deliverPasswordReset(any(),anyString());
        recovery.requestPasswordResetFromController(u.getEmail(),"192.0.2.2","");queue.dispatchPending();queue.dispatchPending();
        verify(mail,times(3)).deliverPasswordReset(any(),anyString());
        assertTrue(recovery.isTokenValid(old));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM auth.security_mail_outbox WHERE user_id=? AND payload IS NOT NULL",Integer.class,u.getId()));
    }
    @Test void twoWorkersDispatchOneIntentAndRepeatedRequestsRespectCooldown() throws Exception {
        User u=account();recovery.requestPasswordResetFromController(u.getEmail(),"192.0.2.2","");
        var pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(queue::dispatchPending);var b=pool.submit(queue::dispatchPending);a.get();b.get();}
        finally {pool.shutdownNow();}
        recovery.requestPasswordResetFromController(u.getEmail(),"192.0.2.2","");queue.dispatchPending();
        verify(mail,times(1)).deliverPasswordReset(any(),anyString());
        assertEquals(1,tokens.findByUser(u).size());
    }
    @Test void crashedClaimBecomesUnknownWithoutSendingAgain() {
        User u=account();recovery.requestPasswordResetFromController(u.getEmail(),"192.0.2.2","");
        jdbc.update("UPDATE auth.security_mail_outbox SET state='SENDING',started_at=? WHERE user_id=?",LocalDateTime.now(clock).minusMinutes(6),u.getId());
        queue.dispatchPending();verifyNoInteractions(mail);
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM auth.security_mail_outbox WHERE user_id=?",String.class,u.getId()));
        assertNull(jdbc.queryForObject("SELECT payload FROM auth.security_mail_outbox WHERE user_id=?",String.class,u.getId()));
    }
    @Test void capabilityIsRealAndWrongPasswordNeverChangesOrRevokes() throws Exception {
        User u=account();String bearer=login(u);
        var cap=mvc.perform(get("/api/users/security-settings").header("Authorization","Bearer "+bearer)).andReturn();
        assertEquals(200,cap.getResponse().getStatus());
        assertTrue(json.readTree(cap.getResponse().getContentAsString()).path("data").path("passwordChangeRevokesSessions").asBoolean());
        assertEquals(400,change(bearer,"Wrong@9xSafe",NEW));
        assertTrue(encoder.matches(OLD,users.findById(u.getId()).orElseThrow().getPassword()));
        assertEquals(200,mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+bearer)).andReturn().getResponse().getStatus());
        assertEquals(200,change(bearer,OLD,NEW));
        assertEquals(401,mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+bearer)).andReturn().getResponse().getStatus());
        queue.dispatchPending();verify(mail,times(1)).deliverPasswordChanged(any());
    }
    @Test void failedNotificationDoesNotRollBackConfirmedPassword() throws Exception {
        User u=account();String bearer=login(u);
        doReturn(EmailService.ActivationDelivery.UNKNOWN).when(mail).deliverPasswordChanged(any());
        assertEquals(200,change(bearer,OLD,NEW));queue.dispatchPending();
        assertTrue(encoder.matches(NEW,users.findById(u.getId()).orElseThrow().getPassword()));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM auth.security_mail_outbox WHERE user_id=?",String.class,u.getId()));
        queue.dispatchPending();verify(mail,times(1)).deliverPasswordChanged(any());
    }
    @Test void stepUpBudgetPersistsAndDoesNotRevokeOnFailure() throws Exception {
        User u=account();String bearer=login(u);
        for(int i=0;i<5;i++)assertEquals(400,change(bearer,"Wrong@9xSafe",NEW));
        assertEquals(429,change(bearer,OLD,NEW));
        assertEquals(200,mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+bearer)).andReturn().getResponse().getStatus());
        assertTrue(encoder.matches(OLD,users.findById(u.getId()).orElseThrow().getPassword()));
    }
    @Test void simultaneousAuthenticatedChangesHaveOneWinner() throws Exception {
        User u=account();String a=login(u),b=login(u);var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->change(a,OLD,NEW));var second=pool.submit(()->change(b,OLD,"Other@7xSafe"));
            var codes=List.of(first.get(),second.get());assertEquals(1,codes.stream().filter(c->c==200).count());
            assertTrue(codes.contains(401));
        } finally {pool.shutdownNow();}
    }
    @Test void legacyLinksRemainUsableAndCannotCrossPurposes() {
        User u=account();String legacy="L".repeat(64);
        tokens.saveAndFlush(new PasswordResetToken(legacy,u,LocalDateTime.now(clock).plusHours(1)));
        assertTrue(recovery.isTokenValid(legacy));
        assertFalse(recovery.isTokenValid("activation-token"));
        assertTrue(recovery.resetPasswordFromController(legacy,NEW,"192.0.2.2",""));
    }
    @Test void publicRequestsAreNeutralAndIpBudgetIgnoresForgedHeaders() throws Exception {
        User eligible=account(),suspended=account();
        jdbc.update("UPDATE auth.users SET enabled=false WHERE id=?",suspended.getId());
        String response=null;
        for(String address:List.of(eligible.getEmail(),suspended.getEmail(),"missing@example.invalid")) {
            var result=mvc.perform(post("/api/auth/password-reset/request").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",address)))).andReturn().getResponse();
            assertEquals(200,result.getStatus());
            if(response==null)response=result.getContentAsString();else assertEquals(response,result.getContentAsString());
            assertNull(result.getHeader("Retry-After"));assertNull(result.getHeader("Set-Cookie"));
        }
        for(int i=3;i<30;i++)assertEquals(200,mvc.perform(post("/api/auth/password-reset/request")
            .header("X-Forwarded-For","192.0.2."+i).contentType("application/json")
            .content("{\"email\":\"missing@example.invalid\"}")).andReturn().getResponse().getStatus());
        var limited=mvc.perform(post("/api/auth/password-reset/request").header("X-Forwarded-For","198.51.100.9")
            .contentType("application/json").content("{\"email\":\"missing@example.invalid\"}")).andReturn().getResponse();
        assertEquals(429,limited.getStatus());assertNotNull(limited.getHeader("Retry-After"));
        assertTrue(tokens.findByUser(suspended).isEmpty());
        queue.dispatchPending();verify(mail,times(1)).deliverPasswordReset(any(),anyString());
    }
    @Test void dailyDeliveryBudgetAndCooldownSurviveNewServiceCalls() {
        User user=account();
        for(int i=0;i<11;i++) {
            recovery.requestPasswordResetFromController(user.getEmail(),"192.0.2.2","");
            queue.dispatchPending();clock.advance(301);
        }
        assertEquals(10,tokens.findByUser(user).size());verify(mail,times(10)).deliverPasswordReset(any(),anyString());
    }
    @Test void exhaustedNoticeBudgetDefersDeliveryWithoutRollingBackChange() throws Exception {
        User user=account();String bearer=login(user);
        // Reserve through the actual shared limiter in a transaction, without fabricating its digest.
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
            for(int i=0;i<10;i++)assertTrue(limits.reserve("notification-recipient-day",user.getEmail(),10,86400));
        });
        assertEquals(200,change(bearer,OLD,NEW));queue.dispatchPending();
        verify(mail,never()).deliverPasswordChanged(any());
        assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM auth.security_mail_outbox WHERE user_id=?",String.class,user.getId()));
        assertTrue(encoder.matches(NEW,users.findById(user.getId()).orElseThrow().getPassword()));
        User other=account();assertNotNull(request(other));
        verify(mail,times(1)).deliverPasswordReset(any(),anyString());
        verify(mail,never()).deliverPasswordChanged(any());
    }
}
