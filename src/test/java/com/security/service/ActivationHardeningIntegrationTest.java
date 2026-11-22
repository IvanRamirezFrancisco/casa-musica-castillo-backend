package com.security.service;

import com.security.entity.*;
import com.security.repository.*;
import com.security.dto.request.RegisterRequest;
import com.security.support.LocalPostgresIntegrationTest;
import com.security.exception.ActivationLimitException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL transactions/locks; transport outcomes injected, never claimed as real email delivery. */
@SpringBootTest(properties={"app.activation.jobs-enabled=false","logging.level.root=WARN","spring.jpa.show-sql=false"})
@ActiveProfiles("test")
@Import(ActivationHardeningIntegrationTest.TimeConfig.class)
class ActivationHardeningIntegrationTest extends LocalPostgresIntegrationTest {
    static class MutableClock extends Clock {
        Instant current = Instant.parse("2026-11-17T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return current; }
        void advance(long seconds) { current = current.plusSeconds(seconds); }
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock controlledClock() { return new MutableClock(); }
    }
    @Autowired MutableClock clock;
    @Autowired UserService registrations;
    @Autowired VerificationService verification;
    @Autowired VerificationRequestLimit limits;
    @Autowired ActivationCrypto crypto;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired VerificationTokenRepository tokens;
    @MockitoBean EmailService mail;
    @MockitoBean com.security.config.MercadoPagoConfiguration payments;
    final List<Long> accountIds = new ArrayList<>();
    final Map<String,String[]> delivered = new ConcurrentHashMap<>();
    @BeforeEach void setup() {
        clock.current = Instant.parse("2026-11-17T12:00:00Z");
        jdbc.update("DELETE FROM auth.activation_request_limits");
        if (roles.findByName("ROLE_USER").isEmpty()) { Role r=new Role();r.setName("ROLE_USER");r.setDescription("Synthetic");roles.save(r); }
        when(mail.deliverActivation(any(),anyString(),anyString())).thenAnswer(call -> {
            User u=call.getArgument(0);delivered.put(u.getEmail(),new String[]{call.getArgument(1),call.getArgument(2)});
            return EmailService.ActivationDelivery.ACCEPTED;
        });
    }
    @AfterEach void cleanup() {
        for (Long id:accountIds) {
            jdbc.update("DELETE FROM auth.verification_tokens WHERE user_id=?",id);
            users.deleteById(id);
        }
    }
    String register() {
        String tag=UUID.randomUUID().toString().replace("-","").substring(0,14);
        RegisterRequest request=new RegisterRequest();
        request.setUsername("qa_"+tag); request.setEmail(tag+"@example.invalid");
        request.setPassword("Synthetic2026!Safe");request.setFirstName("Synthetic");request.setLastName("Account");
        registrations.createUser(request);
        User user=users.findByEmail(request.getEmail()).orElseThrow();
        if(!accountIds.contains(user.getId())) accountIds.add(user.getId());
        return request.getEmail();
    }
    VerificationToken latest(String email) {
        return tokens.findByUserId(users.findByEmail(email).orElseThrow().getId()).stream()
            .max(Comparator.comparing(VerificationToken::getId)).orElseThrow();
    }
    @Test void queueIsEncryptedAndOneAttemptSurvivesTwoWorkers() throws Exception {
        String email=register(); VerificationToken row=latest(email);
        assertEquals("PENDING",row.getDeliveryState());assertNotNull(row.getDeliveryPayload());
        assertFalse(row.getDeliveryPayload().contains("@")); verifyNoInteractions(mail);
        var pool=Executors.newFixedThreadPool(2);
        try { var a=pool.submit(verification::dispatchPending);var b=pool.submit(verification::dispatchPending);a.get();b.get(); }
        finally { pool.shutdownNow(); }
        verify(mail,times(1)).deliverActivation(any(),anyString(),anyString());
        assertEquals("ACCEPTED",latest(email).getDeliveryState());assertNull(latest(email).getDeliveryPayload());
        assertFalse(users.findByEmail(email).orElseThrow().getEnabled());
    }
    @Test void confirmedFailureKeepsOldCodeAndLinkUsable() {
        String email=register();verification.dispatchPending();String[] old=delivered.get(email);
        clock.advance(31);verification.resendVerificationEmail(email);
        doReturn(EmailService.ActivationDelivery.FAILED).when(mail).deliverActivation(any(),anyString(),anyString());
        verification.dispatchPending();
        assertEquals("FAILED",latest(email).getDeliveryState());
        verification.resendVerificationEmail(email);
        assertEquals(2,tokens.findByUserId(accountIds.getFirst()).size(),
            "A confirmed delivery failure must not bypass the recipient cooldown");
        assertTrue(verification.verifyCode(email,old[1]));assertFalse(verification.verifyToken(old[0]));
    }
    @Test void uncertainAcceptanceDoesNotRetryAndPreservesOldAlternative() {
        String email=register();verification.dispatchPending();String[] old=delivered.get(email);
        clock.advance(31);verification.resendVerificationEmail(email);
        doReturn(EmailService.ActivationDelivery.UNKNOWN).when(mail).deliverActivation(any(),anyString(),anyString());
        verification.dispatchPending();verification.dispatchPending();
        verify(mail,times(2)).deliverActivation(any(),anyString(),anyString());
        assertEquals("UNKNOWN",latest(email).getDeliveryState());assertNull(latest(email).getDeliveryPayload());
        assertTrue(verification.verifyCode(email,old[1]));
    }
    @Test void restartAfterClaimDoesNotResendAndClearsCiphertext() {
        String email=register(); VerificationToken row=latest(email);
        jdbc.update("UPDATE auth.verification_tokens SET delivery_state='SENDING',delivery_started_at=? WHERE id=?",
            LocalDateTime.now(clock).minusMinutes(6),row.getId());
        verification.dispatchPending();
        verifyNoInteractions(mail);
        assertEquals("UNKNOWN",latest(email).getDeliveryState());assertNull(latest(email).getDeliveryPayload());
    }
    @Test void otpBoundaryRejectsButLinkRetainsItsLifetime() {
        String email=register();verification.dispatchPending();String[] material=delivered.get(email);
        clock.advance(900);
        assertFalse(verification.verifyCode(email,material[1]));
        assertTrue(verification.verifyToken(material[0]));assertFalse(verification.verifyToken(material[0]));
    }
    @Test void beforeOtpBoundaryAndAtLinkBoundary() {
        String email=register();verification.dispatchPending();String[] material=delivered.get(email);
        clock.advance(899);assertTrue(verification.verifyCode(email,material[1]));
        String other=register();verification.dispatchPending();String token=delivered.get(other)[0];
        clock.advance(86400);assertFalse(verification.verifyToken(token));
    }
    @Test void fiveWrongAttemptsAndCrossAccountCodeDoNotActivate() {
        String email=register();String other=register();verification.dispatchPending();
        String correct=delivered.get(email)[1];
        String wrong=correct.equals("000000")?"000001":"000000";
        for(int i=0;i<5;i++) assertFalse(verification.verifyCode(email,wrong));
        assertFalse(verification.verifyCode(email,correct));
        assertFalse(verification.verifyCode(other,correct));
        assertTrue(verification.verifyToken(delivered.get(email)[0]));
    }
    @Test void codeAndLinkConcurrentHaveExactlyOneWinner() throws Exception {
        String email=register();verification.dispatchPending();String[] material=delivered.get(email);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->verification.verifyCode(email,material[1]));
            var b=pool.submit(()->verification.verifyToken(material[0]));
            assertNotEquals(a.get(),b.get());
        } finally {pool.shutdownNow();}
        assertTrue(tokens.findByUserId(users.findByEmail(email).orElseThrow().getId()).stream().allMatch(r->r.getUsed()));
    }
    @Test void concurrentResendsAndCooldownCannotCreateMultipleEmissions() throws Exception {
        String email=register();verification.dispatchPending();
        verification.resendVerificationEmail(email);assertEquals(1,tokens.findByUserId(accountIds.getFirst()).size());
        clock.advance(31);
        var pool=Executors.newFixedThreadPool(8);
        try {var jobs=new ArrayList<Future<?>>();for(int i=0;i<8;i++) jobs.add(pool.submit(()->verification.resendVerificationEmail(email)));for(var j:jobs)j.get();}
        finally {pool.shutdownNow();}
        assertEquals(2,tokens.findByUserId(accountIds.getFirst()).size());
        verification.dispatchPending();verify(mail,times(2)).deliverActivation(any(),anyString(),anyString());
    }
    @Test void recipientAndGlobalBudgetsAreAtomicAndWindowRecovers() throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        try {
            var jobs=new ArrayList<Future<Boolean>>();
            for(int i=0;i<16;i++) jobs.add(pool.submit(()->limits.reserve("test-global","global",3,60)));
            int accepted=0;for(var j:jobs)if(j.get())accepted++;
            assertEquals(3,accepted);
        } finally {pool.shutdownNow();}
        // New Java object/connection shares the same persisted window.
        var second=new VerificationRequestLimit(jdbc,crypto,clock,new ActivationClientAddress(new org.springframework.mock.env.MockEnvironment()),
            new ActivationPolicy(new org.springframework.mock.env.MockEnvironment()));
        assertFalse(second.reserve("test-global","global",3,60));
        clock.advance(60);assertTrue(second.reserve("test-global","global",3,60));
        limits.cleanup();assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auth.activation_request_limits",Integer.class));
    }
    @Test void ipHasPreciseRetryAfterAndNoPlainAddressStored() {
        for(int i=0;i<3;i++) limits.check("test-ip","192.0.2.1",3);
        ActivationLimitException ex=assertThrows(ActivationLimitException.class,()->limits.check("test-ip","192.0.2.1",3));
        assertEquals(300,ex.getRetryAfter());
        assertFalse(jdbc.queryForObject("SELECT subject_digest FROM auth.activation_request_limits LIMIT 1",String.class).contains("192"));
    }
    @Test void administrativelyDisabledAccountCannotBeReenrolled() {
        String email=register();verification.dispatchPending();String token=delivered.get(email)[0];
        jdbc.update("UPDATE auth.users SET pending_self_registration=false WHERE id=?",accountIds.getFirst());
        clock.advance(31);verification.resendVerificationEmail(email);
        assertEquals(1,tokens.findByUserId(accountIds.getFirst()).size());
        assertFalse(verification.verifyToken(token));
    }
    @Test void administrativeDisableRevokesExistingOtpAndLegacyLinkUnderTheAccountLock() {
        String email=register();verification.dispatchPending();String[] material=delivered.get(email);
        registrations.disableUser(accountIds.getFirst());
        assertFalse(users.findByEmail(email).orElseThrow().isPendingSelfRegistration());
        assertFalse(verification.verifyCode(email,material[1]));
        assertFalse(verification.verifyToken(material[0]));
        assertTrue(latest(email).getUsed());
        clock.advance(31);verification.resendVerificationEmail(email);
        assertEquals(1,tokens.findByUserId(accountIds.getFirst()).size());
    }
    @Test void activationDuringDeliveryCannotReopenTheAccount() {
        String email=register();verification.dispatchPending();String old=delivered.get(email)[0];
        clock.advance(31);verification.resendVerificationEmail(email);
        doAnswer(call -> {
            assertTrue(verification.verifyToken(old));return EmailService.ActivationDelivery.ACCEPTED;
        }).when(mail).deliverActivation(any(),anyString(),anyString());
        verification.dispatchPending();
        assertTrue(users.findByEmail(email).orElseThrow().getEnabled());assertTrue(latest(email).getUsed());
    }
    @Test void deniedGlobalBudgetDoesNotBurnRecipientAllowance() {
        for(int i=0;i<100;i++) assertTrue(limits.reserve("delivery-minute","global",100,60));
        String email=register();
        assertTrue(tokens.findByUserId(accountIds.getFirst()).isEmpty());
        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM auth.activation_request_limits WHERE scope='recipient-day'",Integer.class));
        clock.advance(60);
        verification.resendVerificationEmail(email);
        assertEquals(1,tokens.findByUserId(accountIds.getFirst()).size());
    }
}
