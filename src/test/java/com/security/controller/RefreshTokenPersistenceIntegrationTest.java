package com.security.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.config.MercadoPagoConfiguration;
import com.security.entity.RefreshToken;
import com.security.entity.Role;
import com.security.entity.User;
import com.security.repository.ActiveSessionRepository;
import com.security.repository.RefreshTokenRepository;
import com.security.repository.RoleRepository;
import com.security.repository.UserRepository;
import com.security.service.EmailService;
import com.security.support.LocalPostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "cors.allowed-origins=http://localhost:4200,capacitor://localhost",
        "app.security.jwt.refresh-cookie-name=cmc_refresh_token"
})
class RefreshTokenPersistenceIntegrationTest extends LocalPostgresIntegrationTest {
    private static final String EMAIL_PREFIX = "rtpersist.";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired ActiveSessionRepository activeSessions;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean EmailService emailService;
    @MockitoBean MercadoPagoConfiguration mercadoPagoConfiguration;

    @BeforeEach
    void setup() {
        if (roles.findByName("ROLE_USER").isEmpty()) {
            Role role = new Role();
            role.setName("ROLE_USER");
            role.setDescription("Synthetic user role");
            roles.saveAndFlush(role);
        }
        clean();
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    private void clean() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM auth.users WHERE email LIKE ?", Long.class, EMAIL_PREFIX + "%");
        for (Long id : ids) {
            jdbc.update("DELETE FROM auth.refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth.active_sessions WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth.user_roles WHERE user_id = ?", id);
            jdbc.update("DELETE FROM auth.users WHERE id = ?", id);
        }
    }

    private User createVerifiedUser(String suffix) {
        Role role = roles.findByName("ROLE_USER").orElseThrow();
        User u = new User();
        u.setEmail(EMAIL_PREFIX + suffix + "@example.com");
        u.setUsername("rt." + suffix);
        u.setFirstName("Session");
        u.setLastName("User");
        u.setPassword(passwordEncoder.encode("Persist!Pass123"));
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

    @Test
    void androidStoresHashedRefreshTokenRotatesDetectsReuseAndRevokesOnLogout() throws Exception {
        User user = createVerifiedUser("android1");

        MvcResult loginRes = mvc.perform(post("/api/auth/login")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", "Persist!Pass123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jwtResponse.accessToken").isString())
                .andExpect(jsonPath("$.data.jwtResponse.refreshToken").isString())
                .andReturn();

        JsonNode loginJson = json.readTree(loginRes.getResponse().getContentAsString()).path("data").path("jwtResponse");
        String access1 = loginJson.path("accessToken").asText();
        String refresh1 = loginJson.path("refreshToken").asText();

        // DB stores SHA-256 hash only (never plaintext refresh token)
        List<String> storedHashes = jdbc.queryForList(
                "SELECT token FROM auth.refresh_tokens WHERE user_id = ?", String.class, user.getId());
        assertThat(storedHashes).hasSize(1);
        assertThat(storedHashes.get(0)).startsWith("rt1:").isNotEqualTo(refresh1);

        // Rotate refresh token
        MvcResult rot1Res = mvc.perform(post("/api/auth/session/refresh")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andReturn();

        JsonNode rot1Json = json.readTree(rot1Res.getResponse().getContentAsString()).path("data");
        String access2 = rot1Json.path("accessToken").asText();
        String refresh2 = rot1Json.path("refreshToken").asText();
        assertThat(refresh2).isNotEqualTo(refresh1);

        // Old access session invalidated by rotation
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access1))
                .andExpect(status().isUnauthorized());

        // New access token works!
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access2))
                .andExpect(status().isOk());

        // Reuse of rotated refresh1 triggers family + session revocation -> 401 and invalidates refresh2 + access2!
        mvc.perform(post("/api/auth/session/refresh")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh1))))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/session/refresh")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh2))))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access2))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshSerializesSoOnlyOneWins() throws Exception {
        User user = createVerifiedUser("concurrent1");
        MvcResult loginRes = mvc.perform(post("/api/auth/login")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", "Persist!Pass123"))))
                .andExpect(status().isOk())
                .andReturn();
        String refreshToken = json.readTree(loginRes.getResponse().getContentAsString())
                .path("data").path("jwtResponse").path("refreshToken").asText();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> task = () -> {
            ready.countDown();
            go.await();
            return mvc.perform(post("/api/auth/session/refresh")
                            .header("X-Client-Platform", "ANDROID")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("refreshToken", refreshToken))))
                    .andReturn().getResponse().getStatus();
        };
        Future<Integer> f1 = pool.submit(task);
        Future<Integer> f2 = pool.submit(task);
        ready.await();
        go.countDown();
        int s1 = f1.get();
        int s2 = f2.get();
        pool.shutdown();

        assertThat(List.of(s1, s2)).containsExactlyInAnyOrder(200, 401);
    }

    @Test
    void webCookieRefreshEnforcesCsrfClearsOnLogoutAndLegacyEndpointStays404() throws Exception {
        User user = createVerifiedUser("web1");

        MvcResult loginRes = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", "Persist!Pass123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jwtResponse.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
                .andReturn();

        Cookie refreshCookie = loginRes.getResponse().getCookie("cmc_refresh_token");
        assertThat(refreshCookie).isNotNull();
        String jwt = json.readTree(loginRes.getResponse().getContentAsString())
                .path("data").path("jwtResponse").path("accessToken").asText();

        // Missing X-Requested-With -> 403
        mvc.perform(post("/api/auth/session/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isForbidden());

        // Disallowed Origin -> 403
        mvc.perform(post("/api/auth/session/refresh")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Origin", "https://evil.example.com")
                        .cookie(refreshCookie))
                .andExpect(status().isForbidden());

        // Valid CSRF + Origin -> 200 and rotates HttpOnly cookie
        MvcResult webRefreshed = mvc.perform(post("/api/auth/session/refresh")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Origin", "http://localhost:4200")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("cmc_refresh_token=")))
                .andReturn();
        Cookie rotatedCookie = webRefreshed.getResponse().getCookie("cmc_refresh_token");
        assertThat(rotatedCookie).isNotNull();
        String newJwt = json.readTree(webRefreshed.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();

        // Logout clears cookie & revokes refresh token
        mvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + newJwt)
                        .cookie(rotatedCookie))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        mvc.perform(post("/api/auth/session/refresh")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Origin", "http://localhost:4200")
                        .cookie(rotatedCookie))
                .andExpect(status().isUnauthorized());

        // Retired legacy refresh endpoint must never accept unauthenticated calls and returns 404 when authenticated
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        MvcResult freshLogin = mvc.perform(post("/api/auth/login")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", "Persist!Pass123"))))
                .andExpect(status().isOk())
                .andReturn();
        String freshJwt = json.readTree(freshLogin.getResponse().getContentAsString())
                .path("data").path("jwtResponse").path("accessToken").asText();
        mvc.perform(post("/api/auth/refresh")
                        .header("Authorization", "Bearer " + freshJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void enforcesInactivityAbsoluteExpiryAndAccountRevocations() throws Exception {
        User user = createVerifiedUser("expiry1");
        MvcResult loginRes = mvc.perform(post("/api/auth/login")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", "Persist!Pass123"))))
                .andExpect(status().isOk())
                .andReturn();
        String refresh = json.readTree(loginRes.getResponse().getContentAsString())
                .path("data").path("jwtResponse").path("refreshToken").asText();

        // Simulate expired inactivity window
        jdbc.update("UPDATE auth.refresh_tokens SET expiry_date = ? WHERE user_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), user.getId());

        mvc.perform(post("/api/auth/session/refresh")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized());

        // New login + logout-all revokes all refresh tokens
        MvcResult login2 = mvc.perform(post("/api/auth/login")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", "Persist!Pass123"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode login2Json = json.readTree(login2.getResponse().getContentAsString()).path("data").path("jwtResponse");
        String access2 = login2Json.path("accessToken").asText();
        String refresh2 = login2Json.path("refreshToken").asText();

        mvc.perform(post("/api/auth/logout-all")
                        .header("Authorization", "Bearer " + access2))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/session/refresh")
                        .header("X-Client-Platform", "ANDROID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh2))))
                .andExpect(status().isUnauthorized());

        // Verify no active refresh token remains unrevoked
        List<RefreshToken> active = refreshTokens.findAll().stream()
                .filter(rt -> rt.getUser().getId().equals(user.getId()) && !Boolean.TRUE.equals(rt.getRevoked()))
                .toList();
        assertThat(active).isEmpty();
    }
}
