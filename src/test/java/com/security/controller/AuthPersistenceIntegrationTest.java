package com.security.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.entity.User;
import com.security.repository.ActiveSessionRepository;
import com.security.repository.UserRepository;
import com.security.security.JwtTokenProvider;
import com.security.service.EmailService;
import com.security.support.LocalPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real credentials, filter, PostgreSQL sessions, JWT, controllers and factor transaction. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthPersistenceIntegrationTest extends LocalPostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ActiveSessionRepository sessions;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtTokenProvider jwt;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockBean EmailService email;
    @MockBean com.security.config.MercadoPagoConfiguration mercadoPagoConfiguration;
    User user;
    private static final String PASSWORD = "SyntheticOnly@zK!928";

    @BeforeEach void createUser() {
        user=new User(); user.setUsername("synthetic_auth_integration");
        user.setEmail("auth-integration@example.invalid"); user.setFirstName("Synthetic");user.setLastName("Integration");
        user.setEnabled(true);user.setPassword(passwords.encode(PASSWORD));user=users.save(user);
    }
    @AfterEach void removeSyntheticUser() {
        sessions.deleteAll(sessions.findByUserId(user.getId()));
        users.deleteById(user.getId());
    }
    JsonNode login() throws Exception {
        var result=mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",user.getEmail(),"password",PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }
    String access() throws Exception { return login().get("jwtResponse").get("accessToken").asText(); }

    @Test void loginMeAndLogoutUseRealPersistentSession() throws Exception {
        String token=access();String jti=jwt.getJtiFromJWT(token);
        assertTrue(jwt.validateToken(token));assertTrue(sessions.findByJwtTokenId(jti).isPresent());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(post("/api/auth/logout").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertTrue(sessions.findByJwtTokenId(jti).orElseThrow().getRevoked());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void logoutAllRevokesBothRealSessions() throws Exception {
        String first=access();String second=access();
        mvc.perform(post("/api/auth/logout-all").header("Authorization","Bearer "+second)
                .contentType("application/json").content("{\"userId\":999999}"))
                .andExpect(status().isOk());
        assertFalse(jwt.validateToken(first));assertFalse(jwt.validateToken(second));
    }
    @Test void twoFactorCreatesNoSessionUntilChallengeAndCodeSucceed() throws Exception {
        user.setEmailEnabled(true);user.setTwoFactorEnabled(true);users.save(user);
        JsonNode pending=login();String challenge=pending.get("preAuthChallenge").asText();
        assertTrue(pending.get("twoFactorRequired").asBoolean());assertFalse(pending.has("accessToken"));assertFalse(pending.has("user"));
        assertTrue(sessions.findByUserId(user.getId()).isEmpty());
        mvc.perform(post("/api/2fa/send-login-code").contentType("application/json")
                .content(json.writeValueAsString(Map.of("preAuthChallenge",challenge,"method","EMAIL"))))
                .andExpect(status().isOk());
        var code=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(email).send2FACodeEmail(any(User.class),code.capture());
        String body=json.writeValueAsString(Map.of("preAuthChallenge",challenge,"method","EMAIL","code",code.getValue()));
        var result=mvc.perform(post("/api/2fa/verify").contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
        String token=json.readTree(result.getResponse().getContentAsString()).get("data").get("accessToken").asText();
        assertNotNull(jwt.getJtiFromJWT(token));assertTrue(jwt.validateToken(token));
        mvc.perform(post("/api/2fa/verify").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        assertEquals(1,sessions.findByUserId(user.getId()).size());
    }
    @Test void passwordFailureAndDisabledAccountCannotIssueSessions() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",user.getEmail(),"password","SyntheticWrong!"))))
                .andExpect(status().isUnauthorized());
        user.setEnabled(false);users.save(user);
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",user.getEmail(),"password",PASSWORD))))
                .andExpect(status().isUnauthorized());
        assertTrue(sessions.findByUserId(user.getId()).isEmpty());
    }
    @Test void inactiveOrDisabledUserCannotUseValidSignature() throws Exception {
        String token=access();
        var session=sessions.findByJwtTokenId(jwt.getJtiFromJWT(token)).orElseThrow();
        session.setLastActivity(java.time.LocalDateTime.now().minusMinutes(30));sessions.save(session);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        String current=access();user.setEnabled(false);users.save(user);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+current)).andExpect(status().isUnauthorized());
    }

    @Test void maximumSessionsRevokesOldestAndForeignSessionCannotBeRevoked() throws Exception {
        String oldest=access();access();access();String current=access();
        assertFalse(jwt.validateToken(oldest));assertTrue(jwt.validateToken(current));
        User other=new User();other.setEmail("other-auth@example.invalid");other.setUsername("synthetic_auth_other");
        other.setFirstName("Synthetic");other.setLastName("Other");other.setPassword("synthetic-hash");other=users.save(other);
        var foreign=sessions.save(new com.security.entity.ActiveSession(other,"synthetic-foreign-session","127.0.0.1","synthetic-test",java.time.LocalDateTime.now().plusMinutes(5)));
        try {
            mvc.perform(delete("/api/auth/sessions/synthetic-foreign-session").header("Authorization","Bearer "+current))
                    .andExpect(status().isNotFound());
            assertFalse(sessions.findById(foreign.getId()).orElseThrow().getRevoked());
        } finally {sessions.deleteById(foreign.getId());users.deleteById(other.getId());}
    }
    @Test void concurrentSameTotpIssuesExactlyOneJwt() throws Exception {
        var generator=new com.warrenstrange.googleauth.GoogleAuthenticator();
        String secret=generator.createCredentials().getKey();
        user.setGoogleAuthEnabled(true);user.setTwoFactorEnabled(true);user.setGoogleAuthSecret(secret);users.save(user);
        String first=login().get("preAuthChallenge").asText();String second=login().get("preAuthChallenge").asText();
        // Provision independent pending challenges to test replay protection beyond single-challenge consumption.
        jdbc.update("UPDATE auth.login_challenges SET consumed_at=NULL WHERE user_id=?",user.getId());
        String code=String.format(java.util.Locale.ROOT,"%06d",generator.getTotpPassword(secret));
        var gate=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.function.Function<String,java.util.concurrent.Callable<Integer>> verifyChallenge=challenge->()->{
            gate.await();
            return mvc.perform(post("/api/2fa/verify").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("preAuthChallenge",challenge,"method","GOOGLE_AUTHENTICATOR","code",code))))
                    .andReturn().getResponse().getStatus();
        };
        try {
            var a=pool.submit(verifyChallenge.apply(first));var b=pool.submit(verifyChallenge.apply(second));gate.countDown();
            var statuses=java.util.List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,statuses.stream().filter(status->status==200).count());
            assertEquals(1,statuses.stream().filter(status->status==401).count());
            assertEquals(1,sessions.findByUserId(user.getId()).size());
            assertEquals(2,jdbc.queryForObject("SELECT attempts FROM auth.login_factor_limits WHERE user_id=?",Integer.class,user.getId()));
            assertNotNull(jdbc.queryForObject("SELECT last_verified_totp_slot FROM auth.login_factor_limits WHERE user_id=?",Long.class,user.getId()));
        } finally {pool.shutdownNow();}
        String next=login().get("preAuthChallenge").asText();
        mvc.perform(post("/api/2fa/verify").contentType("application/json")
                .content(json.writeValueAsString(Map.of("preAuthChallenge",next,"method","GOOGLE_AUTHENTICATOR","code",code))))
                .andExpect(status().isUnauthorized());
        assertEquals(1,sessions.findByUserId(user.getId()).size());
    }

}
